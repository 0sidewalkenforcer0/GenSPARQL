#!/usr/bin/env python3
"""Score the records of run_eval.py against the gold data of fetch_gold.py.

  score.py RESULTS_DIR [RESULTS_DIR ...]   one directory per model

Writes summary.json and summary.md next to the result directories and prints the tables.

E1  A player counts as answered when the generated values map to position buckets. The
    answer is correct when it maps to exactly one bucket and Wikidata lists that bucket.
    A laureate counts as answered when the values contain a year. The answer is correct
    when it contains exactly one year and Wikidata gives that year. "Unknown" or no row is
    an abstention.
E2  A generated name denotes an entity of the graph when it equals one of the entity's
    English names in Wikidata after normalization (case, accents, punctuation). A mapped
    entity is correct when one of the generated names denotes it. Precision and recall
    refer to the entities that the generated names denote. Names that denote no entity of
    the graph are listed for a manual check.
"""
import json
import os
import re
import sys
import unicodedata

HERE = os.path.dirname(os.path.abspath(__file__))
ROOT = os.path.abspath(os.path.join(HERE, "..", ".."))
GOLD = os.path.join(HERE, "gold")
BUCKETS = ["Goalkeeper", "Defender", "Midfielder", "Forward"]


def coarse(label):
    s = label.lower()
    if any(k in s for k in ["goalkeeper", "keeper"]):
        return "Goalkeeper"
    if any(k in s for k in ["back", "defender", "defence", "defense", "libero", "sweeper"]):
        return "Defender"
    if "midfield" in s:
        return "Midfielder"
    if any(k in s for k in ["forward", "striker", "winger", "wing", "attacker"]):
        return "Forward"
    return None


def norm(s):
    s = unicodedata.normalize("NFKD", s)
    s = "".join(c for c in s if not unicodedata.combining(c)).lower()
    return " ".join(re.sub(r"[^a-z0-9]+", " ", s).split())


def qid(iri):
    return iri.rsplit("/", 1)[-1]


def load(path):
    with open(path, encoding="utf-8") as f:
        return json.load(f)


def kg_labels(ttl, cls):
    text = open(os.path.join(ROOT, "docs", ttl), encoding="utf-8").read()
    return {m.group(1): m.group(2) for m in
            re.finditer(r'wd:(Q\d+) a ex:' + cls + r' ; rdfs:label "([^"]+)"', text)}


def values_by_entity(rows, key="p", value="v"):
    out = {}
    for r in rows:
        out.setdefault(qid(r[key]), []).append(r.get(value, ""))
    return out


def score_positions(rec, gold, players):
    vals = values_by_entity(rec.get("rows", []))
    answered = correct = with_gold = multi = 0
    for q in players:
        buckets = {coarse(v) for v in vals.get(q, []) if coarse(v)}
        if buckets:
            answered += 1
            multi += len(buckets) > 1
            if q in gold:
                with_gold += 1
                correct += len(buckets) == 1 and next(iter(buckets)) in gold[q]
    return {"entities": len(players), "with_gold": len(gold), "answered": answered,
            "answer_rate": round(answered / len(players), 3),
            "answered_with_gold": with_gold, "correct": correct,
            "accuracy": round(correct / with_gold, 3) if with_gold else None,
            "several_buckets": multi, "calls": rec.get("calls"), "seconds": rec.get("seconds"),
            "error": rec.get("error")}


def score_years(rec, gold, laureates):
    vals = values_by_entity(rec.get("rows", []))
    answered = correct = with_gold = 0
    for q in laureates:
        years = {int(y) for v in vals.get(q, []) for y in re.findall(r"\b(1[5-9]\d\d|20[0-2]\d)\b", v)}
        if years:
            answered += 1
            if q in gold:
                with_gold += 1
                correct += len(years) == 1 and next(iter(years)) in gold[q]
    return {"entities": len(laureates), "with_gold": len(gold), "answered": answered,
            "answer_rate": round(answered / len(laureates), 3),
            "answered_with_gold": with_gold, "correct": correct,
            "accuracy": round(correct / with_gold, 3) if with_gold else None,
            "calls": rec.get("calls"), "seconds": rec.get("seconds"), "error": rec.get("error")}


def e2_names(name, e2gold):
    """Normalized names of every entity in the range of the query's pattern."""
    target = {q: {norm(n) for n in v["names"]} for q, v in e2gold[name].items()}
    if name == "nobel_physics_2020":  # the pattern ranges over all laureates
        for q, label in kg_labels("nobel-prize.ttl", "NobelLaureate").items():
            target.setdefault(q, set()).add(norm(label))
    return target


def score_e2(rec, name, e2gold, manual):
    names = e2_names(name, e2gold)
    checked = manual.get(name, {})
    generated = [r.get("x", "") for r in rec["generated_before"].get("rows", [])]
    after = [r.get("x", "") for r in rec["generated_after"].get("rows", [])]
    denoted, unmatched = set(), []
    for g in dict.fromkeys(generated):
        hits = {q for q, ns in names.items() if norm(g) in ns}
        if not hits and checked.get(g):
            hits = {checked[g]}
        denoted |= hits
        if not hits and g not in checked:
            unmatched.append(g)
    runs = []
    for run in rec["runs"]:
        mapped = {qid(r["x"]) for r in run.get("rows", [])}
        good = mapped & denoted
        runs.append({"similarity": run["similarity"], "theta": run["theta"], "mapped": len(mapped),
                     "correct": len(good),
                     "precision": round(len(good) / len(mapped), 3) if mapped else None,
                     "recall": round(len(good) / len(denoted), 3) if denoted else None,
                     "wrong": sorted(mapped - denoted), "calls": run.get("calls"),
                     "error": run.get("error")})
    names_in_target = sum(1 for g in dict.fromkeys(generated)
                          if any(norm(g) in ns for ns in names.values()) or checked.get(g))
    return {"generated": len(set(generated)), "names_denoting_target": names_in_target,
            "denote_graph_entities": len(denoted),
            "names_without_entity": len(set(generated)) - names_in_target,
            "unchecked_names": unmatched,
            "same_list_after": sorted(map(norm, generated)) == sorted(map(norm, after)),
            "runs": runs}


def main(dirs):
    pos_gold = load(os.path.join(GOLD, "wc_positions.json"))
    year_gold = {q: set(v) for q, v in load(os.path.join(GOLD, "nobel_birth_years.json")).items()}
    e2gold = load(os.path.join(GOLD, "e2_entities.json"))
    manual = load(os.path.join(GOLD, "e2_manual.json"))
    players = sorted(kg_labels("worldcup2026.ttl", "Athlete"))
    laureates = sorted(kg_labels("nobel-prize.ttl", "NobelLaureate"))
    summary = {}
    for d in dirs:
        model = os.path.basename(os.path.normpath(d))
        s = summary[model] = {}
        f = os.path.join(d, "e1_wc_positions.json")
        if os.path.exists(f):
            s["e1_wc_positions"] = score_positions(load(f), pos_gold, players)
        f = os.path.join(d, "e1_nobel_birth_years.json")
        if os.path.exists(f):
            s["e1_nobel_birth_years"] = score_years(load(f), year_gold, laureates)
        for name in e2gold:
            f = os.path.join(d, f"e2_{name}.json")
            if os.path.exists(f):
                s["e2_" + name] = score_e2(load(f), name, e2gold, manual)
        for f in sorted(os.listdir(d)):
            if f.startswith("e3_"):
                r = load(os.path.join(d, f))
                without = r.get("solutions_without_restriction", {}).get("rows", [{}])
                s[f[:-5]] = {"calls": r.get("calls"), "rows": len(r.get("rows", [])),
                             "solutions_without_restriction": int(without[0].get("n", 0)) if without else None,
                             "seconds": r.get("seconds"), "error": r.get("error")}
    out = os.path.join(os.path.dirname(os.path.normpath(dirs[0])), "summary.json")
    with open(out, "w", encoding="utf-8") as fh:
        json.dump(summary, fh, indent=1, ensure_ascii=False)
    print(json.dumps(summary, indent=1, ensure_ascii=False)[:6000])
    print("wrote", out, file=sys.stderr)


if __name__ == "__main__":
    main(sys.argv[1:])
