#!/usr/bin/env python3
"""
X1 — Column-extension accuracy (the GenSPARQL "coverage" claim).

GENOP supplies a column the KG does NOT model: each 2026 World Cup player's playing
position. We measure how accurate that generated column is against Wikidata P413
(position played on team) as ground truth.

  SPARQL-only baseline: the KG has no position predicate -> 0 answers.
  GenSPARQL (GENOP)   : the LLM fills the column; we score it vs P413.

Positions are compared at the coarse bucket level {Goalkeeper, Defender, Midfielder,
Forward} to keep LLM output and Wikidata's fine-grained roles comparable.

Env:
  OPENAI_BASE_URL   local vLLM chat endpoint (OpenAI-compatible)
  GS_GEN_MODEL      default Qwen/Qwen3-30B-A3B-Instruct-2507
  X1_SAMPLE         optional: cap the number of players (default: all)
Output: eval/wc2026_x1_results.json + a printed summary.
"""
import json
import os
import re
import sys
import time
import urllib.parse
import urllib.request
from concurrent.futures import ThreadPoolExecutor, as_completed

HERE = os.path.dirname(os.path.abspath(__file__))
KG = os.path.join(HERE, "..", "gensparql-example", "data", "worldcup2026.ttl")
WD = "https://query.wikidata.org/sparql"
UA = "GenSPARQL-X1/1.0 (research; ac-claude-3@ki.uni-stuttgart.de)"
LLM = os.environ["OPENAI_BASE_URL"].rstrip("/")
MODEL = os.environ.get("GS_GEN_MODEL", "Qwen/Qwen3-30B-A3B-Instruct-2507")
SAMPLE = int(os.environ.get("X1_SAMPLE", "0"))
BUCKETS = ["Goalkeeper", "Defender", "Midfielder", "Forward"]


def coarse(label):
    """Map a fine-grained position label to a coarse bucket, or None."""
    s = label.lower()
    if any(k in s for k in ["goalkeeper", "keeper"]):
        return "Goalkeeper"
    if any(k in s for k in ["back", "defender", "defence", "defense", "libero", "sweeper"]):
        return "Defender"
    if any(k in s for k in ["midfield"]):
        return "Midfielder"
    if any(k in s for k in ["forward", "striker", "winger", "wing", "attacker", "centre-forward",
                            "center-forward", "second striker"]):
        return "Forward"
    return None


# ---- players from the KG (qid + label) ------------------------------------------
players = {}
with open(KG) as f:
    for line in f:
        m = re.match(r'wd:(Q\d+) a ex:Athlete ; rdfs:label "([^"]+)"', line)
        if m:
            players[m.group(1)] = m.group(2)
qids = sorted(players)
if SAMPLE and len(qids) > SAMPLE:
    qids = qids[:SAMPLE]
print(f"players (KG): {len(qids)}", file=sys.stderr)


# ---- gold positions from Wikidata P413 ------------------------------------------
def wd_positions(qid_list):
    gold = {}
    for i in range(0, len(qid_list), 200):
        chunk = qid_list[i:i + 200]
        values = " ".join("wd:" + q for q in chunk)
        q = f"""SELECT ?p ?posLabel WHERE {{
          VALUES ?p {{ {values} }}
          ?p wdt:P413 ?pos .
          ?pos rdfs:label ?posLabel . FILTER(LANG(?posLabel)="en")
        }}"""
        url = WD + "?" + urllib.parse.urlencode({"query": q, "format": "json"})
        req = urllib.request.Request(url, headers={"User-Agent": UA,
                                                   "Accept": "application/sparql-results+json"})
        for _ in range(3):
            try:
                with urllib.request.urlopen(req, timeout=90) as r:
                    rows = json.load(r)["results"]["bindings"]
                break
            except Exception:
                time.sleep(3)
        else:
            rows = []
        for row in rows:
            q = row["p"]["value"].rsplit("/", 1)[-1]
            c = coarse(row["posLabel"]["value"])
            if c:
                gold.setdefault(q, set()).add(c)
    return gold


print("fetching Wikidata P413 gold ...", file=sys.stderr)
gold = wd_positions(qids)
print(f"players with gold position: {len(gold)}", file=sys.stderr)


# ---- LLM-generated position (the GENOP column) ----------------------------------
def llm_position(name):
    # Open-ended prompt (no fixed option list) to avoid first-option position bias, with an
    # explicit abstention so "don't know" is separated from a guess.
    body = json.dumps({
        "model": MODEL,
        "messages": [{"role": "user", "content":
            f"On which position does the footballer {name} primarily play? "
            f"Give the specific position (e.g. centre-back, winger, defensive midfielder, "
            f"goalkeeper). If you do not know this player, reply exactly: Unknown."}],
        "temperature": 0.0, "max_tokens": 16,
    }).encode()
    req = urllib.request.Request(LLM + "/chat/completions", data=body,
                                 headers={"Content-Type": "application/json",
                                          "Authorization": "Bearer dummy"})
    for _ in range(3):
        try:
            with urllib.request.urlopen(req, timeout=60) as r:
                txt = json.load(r)["choices"][0]["message"]["content"].strip()
            if txt.lower().startswith("unknown"):
                return "Unknown"
            return coarse(txt) or ("Unknown" if not txt else txt.strip())
        except Exception:
            time.sleep(2)
    return None


print(f"generating positions via {MODEL} ...", file=sys.stderr)
gen = {}
with ThreadPoolExecutor(max_workers=16) as ex:
    futs = {ex.submit(llm_position, players[q]): q for q in qids}
    done = 0
    for fut in as_completed(futs):
        q = futs[fut]
        gen[q] = fut.result()
        done += 1
        if done % 100 == 0:
            print(f"  {done}/{len(qids)}", file=sys.stderr)


# ---- score ----------------------------------------------------------------------
evaluable = [q for q in qids if q in gold and gen.get(q) in BUCKETS]
correct = [q for q in evaluable if gen[q] in gold[q]]
records = []
for q in qids:
    records.append({"qid": q, "name": players[q],
                    "gold": sorted(gold.get(q, [])),
                    "gen": gen.get(q),
                    "correct": (q in gold and gen.get(q) in gold[q])})

answered = [q for q in qids if gen.get(q) in BUCKETS]
abstained = [q for q in qids if gen.get(q) == "Unknown"]
summary = {
    "model": MODEL,
    "players_total": len(qids),
    "players_with_gold": len(gold),
    "coverage_gold": round(len(gold) / len(qids), 3),
    "answered": len(answered),
    "abstained_unknown": len(abstained),
    "answer_rate": round(len(answered) / len(qids), 3),
    "evaluable": len(evaluable),            # answered AND has gold
    "correct": len(correct),
    "accuracy_on_answered_with_gold": round(len(correct) / len(evaluable), 3) if evaluable else None,
    "random_baseline_4class": 0.25,
    "sparql_only_answers": 0,
}
out = os.path.join(HERE, "wc2026_x1_results.json")
with open(out, "w") as f:
    json.dump({"summary": summary, "records": records}, f, indent=2, ensure_ascii=False)

print("\n=== X1: column-extension (player position) ===")
for k, v in summary.items():
    print(f"  {k}: {v}")
print(f"  wrote {out}")
# a few examples
print("  examples:")
for r in records:
    if r["gen"] in BUCKETS and r["gold"]:
        mark = "OK " if r["correct"] else "XX "
        print(f"    {mark}{r['name']:<24} gen={r['gen']:<11} gold={r['gold']}")
        if records.index(r) > 400:
            break
