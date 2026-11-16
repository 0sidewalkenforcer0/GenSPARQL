#!/usr/bin/env python3
"""Write the data of the recorded mode of the workbench (docs/recorded-runs.js) from the
evaluation results, so that every number the project page shows comes from a run.

  make_recorded_data.py            (from the repository root)

Sources: results/<model>/e1_*.json, e2_wc_teams.json and e3_*.json of the run of
2026-11-03, results/planner-calls.json (the calls with the planner on and off),
results/e2-embedding-scores/ (bge-large-en-v1.5 similarities, embedding_scores.py), the gold
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
Q8B = "Qwen/Qwen3-8B"
MISTRAL = "mistralai/Mistral-7B-Instruct-v0.3"
MODELS = [Q30B, Q8B, MISTRAL]
SHORT = {Q30B: "Qwen3-30B", Q8B: "Qwen3-8B", MISTRAL: "Mistral-7B"}
BUCKETS = {"goalkeeper": "Goalkeeper", "defender": "Defender", "midfielder": "Midfielder", "forward": "Forward"}


def load(*parts):
    with open(os.path.join(*parts), encoding="utf-8") as f:
        return json.load(f)


def model_dir(model):
    return os.path.join(RES, model.replace("/", "_"))


def qid(iri):
    return str(iri).rsplit("/", 1)[-1]


def short(model):
    return SHORT[model]


def pct(x):
    return f"{100 * x:.1f}%"


def declined(value):
    return value.strip() in ("", "[]", '[""]')


def graph(name):
    g = rdflib.Graph()
    g.parse(os.path.join(ROOT, "docs", name), format="turtle")
    return g


def position_answers(model):
    """The E1 answers of a model, by the QID of the player."""
    answers = {}
    for r in load(model_dir(model), "e1_wc_positions.json")["rows"]:
        answers.setdefault(qid(r["p"]), []).append(r.get("v", ""))
    return answers


def judge_position(values, gold_groups):
    """The shown answer and its state: correct, wrong, unscored (no gold) or abstain."""
    values = [v for v in values if not declined(v)]
    bucket = BUCKETS.get(values[0].strip().lower()) if len(values) == 1 else None
    if not values:
        return "no answer", "abstain"
    if gold_groups is None:
        return bucket or values[0], "unscored"
    return bucket or values[0], ("correct" if bucket in gold_groups else "wrong")


def wc_attribute(wc, summary):
    """The answers of the three LLMs for the 26 Argentine players (query of Figure 1(a)).
    The rows hold the answers of Qwen3-30B, and under "others" those of the other two."""
    gold = load(HERE, "gold", "wc_positions.json")
    answers = {m: position_answers(m) for m in MODELS}
    players = sorted(wc.subjects(EX.playsFor, WD.Q79800), key=lambda p: str(wc.value(p, RDFS.label)))
    out = []
    for p in players:
        q = qid(p)
        shown, state = judge_position(answers[Q30B].get(q, []), gold.get(q))
        others = []
        for m in MODELS[1:]:
            o_shown, o_state = judge_position(answers[m].get(q, []), gold.get(q))
            others.append({"model": short(m), "gen": o_shown, "state": o_state})
        out.append({"name": str(wc.value(p, RDFS.label)), "gen": shown, "state": state, "others": others})
    s = summary[Q30B.replace("/", "_")]["e1_wc_positions"]
    return {"model": Q30B, "otherModels": [short(m) for m in MODELS[1:]], "rows": out,
            "stat": {"a": pct(s["answer_rate"]), "b": pct(s["accuracy"])}, "players": s["entities"]}


def wc_entity():
    """The team names that Mistral-7B listed in E2 (Qwen3-30B and Qwen3-8B declined), and for the
    embedding similarity of the recorded mode the bge-large-en-v1.5 similarity of each name to
    each team (embedding_scores.py): the best team of each name, and in `scores` the row of each
    name in the order of `teams`, so that the mapping view draws every pair that reaches θ, as
    the similarity join keeps it."""
    rec = load(model_dir(MISTRAL), "e2_wc_teams.json")
    names = [r["x"] for r in rec["generated_before"]["rows"] if not declined(r.get("x", ""))]
    emb = load(RES, "e2-embedding-scores", "e2_wc_teams.json")
    assert emb["names"] == names, "the embedding scores belong to other names"
    best = {b["name"]: {"team": b["team"], "score": round(b["score"], 4)} for b in emb["best"]}
    teams = sorted(emb["teams"])
    scores = {n: [emb["scores"][n][t] for t in teams] for n in names}
    return {"model": MISTRAL, "prompt": rec["prompt"], "generated": names,
            "embedding": {"model": emb["model"], "best": best, "teams": teams, "scores": scores}}


def nobel_entity():
    """The Nobel fields that Qwen3-30B listed in E2, and for the embedding similarity of the
    recorded mode the bge-large-en-v1.5 similarity of each name to each field, as for the teams
    (embedding_scores.py)."""
    rec = load(model_dir(Q30B), "e2_nobel_fields.json")
    names = [r["x"] for r in rec["generated_before"]["rows"] if not declined(r.get("x", ""))]
    emb = load(RES, "e2-embedding-scores", "e2_nobel_fields.json")
    assert emb["names"] == names, "the embedding scores belong to other names"
    best = {b["name"]: {"team": b["team"], "score": round(b["score"], 4)} for b in emb["best"]}
    fields = sorted(emb["entities"])
    scores = {n: [emb["scores"][n][t] for t in fields] for n in names}
    return {"model": Q30B, "prompt": rec["prompt"], "generated": names,
            "embedding": {"model": emb["model"], "best": best, "teams": fields, "scores": scores}}


def wc_composition(wc, calls):
    """The defenders of Group A that Qwen3-30B generated in E3, the positions it generated for
    every player in E1 with the same prompt, and the measured calls. The page shows these
    positions on the generated nodes. For the players of Group A they agree with E3: E1 and E3
    both have the same 80 defenders."""
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
    positions = {}
    for q, values in position_answers(Q30B).items():
        values = [v for v in values if not declined(v)]
        if len(values) == 1:
            positions[q] = BUCKETS.get(values[0].strip().lower(), values[0])
    e1_defenders = {str(p) for p in ids if positions.get(qid(p)) == "Defender"}
    if e1_defenders != set(ids) or sum(
            1 for t in wc.subjects(EX.inGroup, group_a) for p in wc.subjects(EX.playsFor, t)
            if positions.get(qid(p)) == "Defender") != len(ids):
        raise SystemExit("the E1 positions of Group A disagree with the defenders of E3")
    c = calls["queries"]["wc_group_a_defenders"]["genop before the patterns for Group A"]
    return {"model": Q30B, "defenders": len(ids), "total": rec["calls"], "defenderIds": sorted(ids),
            "positions": positions,
            "genopFirst": c["calls_planner_off"], "planned": c["calls_planner_on"]}


def birth_year_answers(model):
    """The E1 birth-year answers of a model, by the QID of the laureate."""
    answers = {}
    for r in load(model_dir(model), "e1_nobel_birth_years.json")["rows"]:
        answers.setdefault(qid(r["p"]), []).append(r.get("v", ""))
    return answers


def judge_years(values, gold_years):
    """The shown years and their state: correct only for a single year that the gold data holds."""
    years = {int(y) for v in values for y in re.findall(r"\b(1[5-9]\d\d|20[0-2]\d)\b", v)}
    if not years:
        return "no answer", "abstain"
    shown = ", ".join(str(y) for y in sorted(years))
    return shown, ("correct" if len(years) == 1 and next(iter(years)) in gold_years else "wrong")


def nobel_attribute(nobel, summary):
    """The birth years that the three LLMs generated for the Physics laureates since 2020.
    The rows hold the answers of Qwen3-30B, and under "others" those of the other two."""
    gold = {q: set(v) for q, v in load(HERE, "gold", "nobel_birth_years.json").items()}
    answers = {m: birth_year_answers(m) for m in MODELS}
    year = lambda a: int(re.findall(r"\d{4}", str(nobel.value(a, EX.winningYear)))[0])
    laureates = {w for a in nobel.subjects(EX.field, WD.Q38104) if year(a) >= 2020 for w in nobel.objects(a, EX.winner)}
    out = []
    for w in sorted(laureates, key=lambda w: str(nobel.value(w, RDFS.label))):
        q = qid(w)
        shown, state = judge_years(answers[Q30B].get(q, []), gold.get(q, set()))
        others = []
        for m in MODELS[1:]:
            o_shown, o_state = judge_years(answers[m].get(q, []), gold.get(q, set()))
            others.append({"model": short(m), "gen": o_shown, "state": o_state})
        out.append({"name": str(nobel.value(w, RDFS.label)), "gen": shown, "state": state, "others": others})
    s = summary[Q30B.replace("/", "_")]["e1_nobel_birth_years"]
    return {"model": Q30B, "otherModels": [short(m) for m in MODELS[1:]], "rows": out,
            "stat": {"a": pct(s["answer_rate"]), "b": pct(s["accuracy"])}, "laureates": s["entities"]}


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
            print(k, {x: (f"[{len(y)} items]" if isinstance(y, (list, dict)) and len(y) > 4 else y)
                      for x, y in v.items()})


if __name__ == "__main__":
    main()
