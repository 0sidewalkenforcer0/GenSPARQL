#!/usr/bin/env python3
"""Run the WWW'27 evaluation through the GenSPARQL HTTP service.

Every LLM answer in the results passes through GENOP: the script only sends SPARQL
queries to the service (gensparql-server) and records the bindings, the number of LLM
requests that the service reports (trace.genopCalls) and the time. score.py compares the
records with the gold data of fetch_gold.py.

  E1  completing attributes: the position of every player (World Cup graph) and the
      birth year of every laureate (Nobel graph), one context-mode GENOP per query
  E2  mapping generated names: six base-mode list queries, each first without a shared
      pattern (the generated names as strings), then with the pattern for the Jaccard and
      the embedding similarity and seven thresholds, then once more without the pattern
      to check that the LLM gave the same list again
  E3  composition and LLM calls: two queries whose restricting patterns and FILTER run
      before GENOP, and the size of the solutions that GENOP would see otherwise

Usage (inside a Slurm job, see job.sbatch):
  run_eval.py --gs http://localhost:PORT --llm-base http://localhost:PORT/v1 --model NAME \
              --emb-base http://localhost:PORT/v1 --emb-model NAME --data DOCS --out DIR [--smoke]
"""
import argparse
import json
import os
import sys
import time
import urllib.error
import urllib.request
import uuid

PREFIXES = """PREFIX ex: <http://example.org/>
PREFIX rdfs: <http://www.w3.org/2000/01/rdf-schema#>
PREFIX wd: <http://www.wikidata.org/entity/>
PREFIX xsd: <http://www.w3.org/2001/XMLSchema#>
"""
THRESHOLDS = [0.5, 0.6, 0.7, 0.8, 0.85, 0.9, 1.0]

POSITION_PROMPT = ("On which position does the footballer {?name} primarily play? Give one specific "
                   "position, for example centre-back, winger, defensive midfielder or goalkeeper. "
                   "If you do not know this player, answer Unknown.")
BIRTH_PROMPT = ("In which year was {?name}, a Nobel laureate, born? Answer with the year only. "
                "If {?name} is an organization or you do not know the year, answer Unknown.")

E1 = [
    ("wc_positions", "wc",
     "SELECT ?p ?v WHERE { ?p a ex:Athlete ; rdfs:label ?name . "
     f'GENOP("{POSITION_PROMPT}", (?v), <model>) }}'),
    ("nobel_birth_years", "nobel",
     "SELECT ?p ?v WHERE { ?p a ex:NobelLaureate ; rdfs:label ?name . "
     f'GENOP("{BIRTH_PROMPT}", (?v), <model>) }}'),
]
# Smoke runs restrict E1 to a few entities so that a test job makes a handful of calls.
SMOKE_VALUES = {
    "wc_positions": "VALUES ?p { wd:Q615 wd:Q28058687 wd:Q21621995 wd:Q19952334 }",
    "nobel_birth_years": "VALUES ?p { wd:Q937 wd:Q7186 wd:Q35802 }",
}

# name, graph, prompt, pattern that binds ?x to the target entities
E2 = [
    ("wc_teams", "wc", "List the national teams that play at the 2026 FIFA World Cup.",
     "?x a ex:Team ."),
    ("wc_cities", "wc", "List the host cities of the 2026 FIFA World Cup.",
     "?x a ex:City ."),
    ("wc_venues", "wc", "List the stadiums where matches of the 2026 FIFA World Cup are played.",
     "?x a ex:Venue ."),
    ("wc_argentina", "wc", "List the players in the squad of Argentina at the 2026 FIFA World Cup.",
     "?x ex:playsFor wd:Q79800 ."),
    ("nobel_fields", "nobel", "List the fields in which the Nobel Prize is awarded.",
     "?x a ex:NobelField ."),
    ("nobel_physics_2020", "nobel", "List the laureates of the Nobel Prize in Physics from 2020 to 2025.",
     "?x a ex:NobelLaureate ."),
]

E3 = [
    ("wc_group_a_defenders", "wc",
     'SELECT ?name ?pos WHERE { ?t ex:inGroup ?g . ?g rdfs:label "Group A" . '
     "?p ex:playsFor ?t ; rdfs:label ?name . "
     'GENOP("On which position does the footballer {?name} primarily play? Answer with one of '
     'Goalkeeper, Defender, Midfielder or Forward. If you do not know this player, answer Unknown.", '
     '(?pos), <model>) FILTER(?pos = "Defender") }',
     # the solutions of the patterns without the restriction to Group A
     "SELECT (COUNT(*) AS ?n) WHERE { ?p ex:playsFor ?t ; rdfs:label ?name . }"),
    ("nobel_physics_2020_contributions", "nobel",
     "SELECT ?name ?c WHERE { ?a ex:field wd:Q38104 ; ex:winningYear ?y ; ex:winner ?l . "
     "?l rdfs:label ?name . FILTER(xsd:integer(STR(?y)) >= 2020) "
     'GENOP("For which contribution did {?name} receive the Nobel Prize in Physics? '
     'Answer with one short phrase.", (?c), <model>) }',
     # the solutions that GENOP would see if the FILTER ran after it
     "SELECT (COUNT(*) AS ?n) WHERE { ?a ex:field wd:Q38104 ; ex:winningYear ?y ; ex:winner ?l . "
     "?l rdfs:label ?name . }"),
]


def http(url, data=None, headers=None, timeout=7200):
    req = urllib.request.Request(url, data=data, headers=headers or {})
    with urllib.request.urlopen(req, timeout=timeout) as r:
        return json.load(r)


def wait_for(url, what, seconds=1800):
    t0 = time.time()
    while time.time() - t0 < seconds:
        try:
            http(url, timeout=10)
            print(f"{what} is up after {time.time() - t0:.0f} s", file=sys.stderr)
            return
        except Exception:
            time.sleep(5)
    raise RuntimeError(f"{what} did not come up: {url}")


def upload(gs, path):
    boundary = uuid.uuid4().hex
    name = os.path.basename(path)
    body = (f"--{boundary}\r\nContent-Disposition: form-data; name=\"file\"; filename=\"{name}\"\r\n"
            f"Content-Type: text/turtle\r\n\r\n").encode() + open(path, "rb").read() + \
        f"\r\n--{boundary}--\r\n".encode()
    r = http(gs + "/api/gensparql/datasets", body,
             {"Content-Type": f"multipart/form-data; boundary={boundary}"})
    print(f"uploaded {name}: {r['tripleCount']} triples", file=sys.stderr)
    return r["datasetId"]


class Service:
    def __init__(self, args):
        self.gs = args.gs.rstrip("/")
        self.llm = {"apiKey": "local", "baseUrl": args.llm_base.rstrip("/"), "model": args.model}
        self.emb = {"method": "embedding", "apiKey": "local", "baseUrl": args.emb_base.rstrip("/"),
                    "model": args.emb_model}
        self.ds = {}

    def query(self, graph, text, similarity=None):
        body = {"datasetId": self.ds[graph], "query": PREFIXES + text, "includeTrace": True,
                "llm": self.llm, "similarity": similarity or {"method": "jaccard"}}
        t0 = time.time()
        try:
            r = http(self.gs + "/api/gensparql/query", json.dumps(body).encode(),
                     {"Content-Type": "application/json"})
        except urllib.error.HTTPError as e:
            return {"query": text, "error": f"HTTP {e.code}: {e.read().decode()[:500]}",
                    "seconds": round(time.time() - t0, 1)}
        rows = [{k: v["value"] for k, v in b.items()} for b in r["results"]["bindings"]]
        return {"query": text, "rows": rows, "calls": r.get("trace", {}).get("genopCalls"),
                "seconds": round(time.time() - t0, 1)}


def main():
    ap = argparse.ArgumentParser()
    ap.add_argument("--gs", required=True)
    ap.add_argument("--llm-base", required=True)
    ap.add_argument("--model", required=True)
    ap.add_argument("--emb-base", required=True)
    ap.add_argument("--emb-model", required=True)
    ap.add_argument("--data", required=True, help="directory with worldcup2026.ttl and nobel-prize.ttl")
    ap.add_argument("--out", required=True)
    ap.add_argument("--smoke", action="store_true", help="a few calls per experiment only")
    args = ap.parse_args()
    os.makedirs(args.out, exist_ok=True)

    wait_for(args.gs + "/api/gensparql/health", "GenSPARQL service")
    wait_for(args.llm_base.rstrip("/") + "/models", "LLM server")
    wait_for(args.emb_base.rstrip("/") + "/models", "embedding server")
    svc = Service(args)
    svc.ds["wc"] = upload(args.gs, os.path.join(args.data, "worldcup2026.ttl"))
    svc.ds["nobel"] = upload(args.gs, os.path.join(args.data, "nobel-prize.ttl"))

    def save(name, record):
        record["model"] = args.model
        with open(os.path.join(args.out, name + ".json"), "w", encoding="utf-8") as f:
            json.dump(record, f, indent=1, ensure_ascii=False)
        print(f"wrote {name}: {len(record.get('rows', record.get('runs', [])))} entries", file=sys.stderr)

    for name, graph, text in E1:
        if args.smoke:
            text = text.replace("WHERE { ", "WHERE { " + SMOKE_VALUES[name] + " ", 1)
        save("e1_" + name, svc.query(graph, text))

    for name, graph, prompt, pattern in (E2[:2] if args.smoke else E2):
        genop = 'GENOP("{}", (?x), <model>{{}})'.format(prompt)
        record = {"prompt": prompt, "pattern": pattern, "runs": []}
        record["generated_before"] = svc.query(graph, "SELECT ?x WHERE { " + genop.format("") + " }")
        for sim_name, sim in [("jaccard", None), ("embedding", svc.emb)]:
            for theta in (THRESHOLDS[2:5] if args.smoke else THRESHOLDS):
                q = "SELECT DISTINCT ?x WHERE { " + pattern + " " + genop.format(f", {theta}") + " }"
                run = svc.query(graph, q, sim)
                run.update({"similarity": sim_name, "theta": theta})
                record["runs"].append(run)
        record["generated_after"] = svc.query(graph, "SELECT ?x WHERE { " + genop.format("") + " }")
        save("e2_" + name, record)

    for name, graph, text, without in (E3[:1] if args.smoke else E3):
        record = svc.query(graph, text)
        record["solutions_without_restriction"] = svc.query(graph, without)
        save("e3_" + name, record)
    print("done", file=sys.stderr)


if __name__ == "__main__":
    main()
