#!/usr/bin/env python3
"""Count the LLM calls of the two composed queries of experiment E3 under one setting of
the planner.

The GenSPARQL service must run with the setting to measure: with the system property
gensparql.planner.reorder=false, a query keeps the order in which it is written and its
filters run at the end of the group, as SPARQL evaluates a group. Started without the
property, the planner moves GENOP after the patterns of its group that do not mention
its output variables and runs the filters that those patterns bind before GENOP.

The answers of the LLM do not change the number of calls, so stub_llm.py serves as the
LLM. Each query runs in the form of run_eval.py (GENOP written after the restricting
patterns) and in the form that writes GENOP before them.

  measure_calls.py --gs http://localhost:PORT --llm-base http://localhost:PORT/v1 \
                   --data ../../docs --label reorder-on --out results
"""
import argparse
import json
import os
import sys

sys.path.insert(0, os.path.dirname(os.path.abspath(__file__)))
import run_eval  # noqa: E402

POSITION = run_eval.POSITION_PROMPT
QUERIES = {
    "wc_group_a_defenders": ("wc", {
        "genop after the patterns for Group A":
            'SELECT ?name ?pos WHERE { ?t ex:inGroup ?g . ?g rdfs:label "Group A" . '
            "?p ex:playsFor ?t ; rdfs:label ?name . "
            f'GENOP("{POSITION}", (?pos), <model>) FILTER(LCASE(STR(?pos)) = "defender") }}',
        "genop before the patterns for Group A":
            "SELECT ?name ?pos WHERE { ?p ex:playsFor ?t ; rdfs:label ?name . "
            f'GENOP("{POSITION}", (?pos), <model>) '
            '?t ex:inGroup ?g . ?g rdfs:label "Group A" . FILTER(LCASE(STR(?pos)) = "defender") }',
    }),
    "nobel_physics_2020_contributions": ("nobel", {
        "filter written before genop":
            "SELECT ?name ?c WHERE { ?a ex:field wd:Q38104 ; ex:winningYear ?y ; ex:winner ?l . "
            "?l rdfs:label ?name . FILTER(xsd:integer(STR(?y)) >= 2020) "
            'GENOP("For which contribution did {?name} receive the Nobel Prize in Physics? '
            'Answer with one short phrase.", (?c), <model>) }',
        "filter written after genop":
            "SELECT ?name ?c WHERE { ?a ex:field wd:Q38104 ; ex:winningYear ?y ; ex:winner ?l . "
            "?l rdfs:label ?name . "
            'GENOP("For which contribution did {?name} receive the Nobel Prize in Physics? '
            'Answer with one short phrase.", (?c), <model>) FILTER(xsd:integer(STR(?y)) >= 2020) }',
    }),
}


def main():
    ap = argparse.ArgumentParser()
    ap.add_argument("--gs", required=True)
    ap.add_argument("--llm-base", required=True)
    ap.add_argument("--data", required=True)
    ap.add_argument("--label", required=True, help="e.g. reorder-on or reorder-off")
    ap.add_argument("--out", required=True)
    args = ap.parse_args()
    args.model = "stub"
    args.emb_base = args.llm_base
    args.emb_model = "stub"
    run_eval.wait_for(args.gs + "/api/gensparql/health", "GenSPARQL service", 300)
    run_eval.wait_for(args.llm_base.rstrip("/") + "/models", "stub LLM", 60)
    svc = run_eval.Service(args)
    svc.ds["wc"] = run_eval.upload(args.gs, os.path.join(args.data, "worldcup2026.ttl"))
    svc.ds["nobel"] = run_eval.upload(args.gs, os.path.join(args.data, "nobel-prize.ttl"))
    out = {"label": args.label, "queries": {}}
    for name, (graph, forms) in QUERIES.items():
        out["queries"][name] = {}
        for form, text in forms.items():
            r = svc.query(graph, text)
            out["queries"][name][form] = {"calls": r.get("calls"), "rows": len(r.get("rows", [])),
                                          "error": r.get("error"), "query": text}
            print(f"{args.label} | {name} | {form}: {r.get('calls')} calls, "
                  f"{len(r.get('rows', []))} rows, error={r.get('error')}", file=sys.stderr)
    os.makedirs(args.out, exist_ok=True)
    path = os.path.join(args.out, f"planner-calls-{args.label}.json")
    with open(path, "w", encoding="utf-8") as f:
        json.dump(out, f, indent=1)
    print("wrote", path, file=sys.stderr)


if __name__ == "__main__":
    main()
