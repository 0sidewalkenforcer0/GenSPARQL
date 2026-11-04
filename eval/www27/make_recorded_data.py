#!/usr/bin/env python3
"""Write the data of the recorded mode of the workbench (docs/recorded-runs.js) from the
evaluation results, so that every number the project page shows comes from a run.

  make_recorded_data.py            (from the repository root)

Sources: results/<model>/e1_*.json, e2_wc_teams.json and e3_*.json of the run of
2026-11-03, results/planner-calls.json (the calls with the planner on and off), the gold
data in gold/ and the two graphs in docs/.
"""
import json
import os
import re

import rdflib
from rdflib.namespace import RDF, RDFS

HERE = os.path.dirname(os.path.abspath(__file__))
ROOT = os.path.abspath(os.path.join(HERE, "..", ".."))
RES = os.path.join(HERE, "results")
EX = rdflib.Namespace("http://example.org/")
WD = rdflib.Namespace("http://www.wikidata.org/entity/")
Q30B = "Qwen/Qwen3-30B-A3B-Instruct-2507"
MISTRAL = "mistralai/Mistral-7B-Instruct-v0.3"
BUCKETS = {"goalkeeper": "Goalkeeper", "defender": "Defender", "midfielder": "Midfielder", "forward": "Forward"}


def load(*parts):
    with open(os.path.join(*parts), encoding="utf-8") as f:
        return json.load(f)


def model_dir(model):
    return os.path.join(RES, model.replace("/", "_"))


def qid(iri):
    return str(iri).rsplit("/", 1)[-1]


def pct(x):
    return f"{100 * x:.1f}%"


def declined(value):
    return value.strip() in ("", "[]", '[""]')


def graph(name):
    g = rdflib.Graph()
    g.parse(os.path.join(ROOT, "docs", name), format="turtle")
    return g


def wc_attribute(wc, summary):
    """The answers of Qwen3-30B for the 26 Argentine players (query of Figure 1(a))."""
    gold = load(HERE, "gold", "wc_positions.json")
    rows = load(model_dir(Q30B), "e1_wc_positions.json")["rows"]
    answers = {}
    for r in rows:
        answers.setdefault(qid(r["p"]), []).append(r.get("v", ""))
    players = sorted(wc.subjects(EX.playsFor, WD.Q79800), key=lambda p: str(wc.value(p, RDFS.label)))
    out = []
    for p in players:
        q = qid(p)
        values = [v for v in answers.get(q, []) if not declined(v)]
        bucket = BUCKETS.get(values[0].strip().lower()) if len(values) == 1 else None
        if not values:
            state, shown = "abstain", "no answer"
        elif q not in gold:
            state, shown = "unscored", bucket or values[0]
        else:
            state = "correct" if bucket in gold[q] else "wrong"
            shown = bucket or values[0]
        out.append({"name": str(wc.value(p, RDFS.label)), "gen": shown, "state": state})
    s = summary[Q30B.replace("/", "_")]["e1_wc_positions"]
    return {"model": Q30B, "rows": out, "stat": {"a": pct(s["answer_rate"]), "b": pct(s["accuracy"])},
            "players": s["entities"]}


def wc_entity():
    """The team names that Mistral-7B listed in E2 (Qwen3-30B and Qwen3-8B declined)."""
    rec = load(model_dir(MISTRAL), "e2_wc_teams.json")
    names = [r["x"] for r in rec["generated_before"]["rows"] if not declined(r.get("x", ""))]
    return {"model": MISTRAL, "prompt": rec["prompt"], "generated": names}


def nobel_entity():
    """The Nobel fields that Qwen3-30B listed in E2."""
    rec = load(model_dir(Q30B), "e2_nobel_fields.json")
    names = [r["x"] for r in rec["generated_before"]["rows"] if not declined(r.get("x", ""))]
    return {"model": Q30B, "prompt": rec["prompt"], "generated": names}


def wc_composition(wc, calls):
    """The defenders of Group A that Qwen3-30B generated in E3, and the measured calls."""
    group_a = next(wc.subjects(RDFS.label, rdflib.Literal("Group A")))
    players = {}
    for t in wc.subjects(EX.inGroup, group_a):
        for p in wc.subjects(EX.playsFor, t):
            players.setdefault(str(wc.value(p, RDFS.label)), []).append(str(p))
    rec = load(model_dir(Q30B), "e3_wc_group_a_defenders.json")
    ids = []
    for r in rec["rows"]:
        match = players.get(r["name"], [])
        if len(match) != 1:
            raise SystemExit(f"name {r['name']!r} is not unique in Group A")
        ids.append(match[0])
    c = calls["queries"]["wc_group_a_defenders"]["genop before the patterns for Group A"]
    return {"model": Q30B, "defenders": len(ids), "total": rec["calls"], "defenderIds": sorted(ids),
            "genopFirst": c["calls_planner_off"], "planned": c["calls_planner_on"]}


def nobel_attribute(nobel, summary):
    """The birth years that Qwen3-30B generated for the Physics laureates since 2020."""
    gold = {q: set(v) for q, v in load(HERE, "gold", "nobel_birth_years.json").items()}
    rows = load(model_dir(Q30B), "e1_nobel_birth_years.json")["rows"]
    answers = {}
    for r in rows:
        answers.setdefault(qid(r["p"]), []).append(r.get("v", ""))
    year = lambda a: int(re.findall(r"\d{4}", str(nobel.value(a, EX.winningYear)))[0])
    laureates = {w for a in nobel.subjects(EX.field, WD.Q38104) if year(a) >= 2020 for w in nobel.objects(a, EX.winner)}
    out = []
    for w in sorted(laureates, key=lambda w: str(nobel.value(w, RDFS.label))):
        q = qid(w)
        years = {int(y) for v in answers.get(q, []) for y in re.findall(r"\b(1[5-9]\d\d|20[0-2]\d)\b", v)}
        if not years:
            state, shown = "abstain", "no answer"
        else:
            shown = ", ".join(str(y) for y in sorted(years))
            state = "correct" if len(years) == 1 and next(iter(years)) in gold.get(q, set()) else "wrong"
        out.append({"name": str(nobel.value(w, RDFS.label)), "gen": shown, "state": state})
    s = summary[Q30B.replace("/", "_")]["e1_nobel_birth_years"]
    return {"model": Q30B, "rows": out, "stat": {"a": pct(s["answer_rate"]), "b": pct(s["accuracy"])},
            "laureates": s["entities"]}


def nobel_composition(calls):
    """The contributions that Qwen3-30B generated in E3, and the measured calls."""
    rec = load(model_dir(Q30B), "e3_nobel_physics_2020_contributions.json")
    answered = sorted(r["name"] for r in rec["rows"] if not declined(r.get("c", "")))
    c = calls["queries"]["nobel_physics_2020_contributions"]["filter written after genop"]
    return {"model": Q30B, "answered": len(answered), "total": rec["calls"], "answeredNames": answered,
            "genopFirst": c["calls_planner_off"], "planned": c["calls_planner_on"]}


def main():
    wc, nobel = graph("worldcup2026.ttl"), graph("nobel-prize.ttl")
    summary = load(RES, "summary.json")
    calls = load(RES, "planner-calls.json")
    data = {
        "source": "eval/www27/results: E1-E3 of 2026-11-03 and the planner counts of 2026-11-04",
        "wcAttribute": wc_attribute(wc, summary),
        "wcEntity": wc_entity(),
        "wcComposition": wc_composition(wc, calls),
        "nobelEntity": nobel_entity(),
        "nobelAttribute": nobel_attribute(nobel, summary),
        "nobelComposition": nobel_composition(calls),
    }
    path = os.path.join(ROOT, "docs", "recorded-runs.js")
    with open(path, "w", encoding="utf-8") as f:
        f.write("/* Generated by eval/www27/make_recorded_data.py from the evaluation results.\n"
                "   Do not edit by hand: rerun the script. */\n")
        f.write("window.RECORDED_RUNS = " + json.dumps(data, indent=1, ensure_ascii=False) + ";\n")
    print("wrote", path)
    for k, v in data.items():
        if isinstance(v, dict):
            print(k, {x: (y if not isinstance(y, list) else f"[{len(y)} items]") for x, y in v.items()})


if __name__ == "__main__":
    main()
