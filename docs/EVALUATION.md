# Preliminary Evaluation — setup, results, and how to reproduce

Evidence for the demo paper's *Preliminary Evaluation* section. The story is
**problem-oriented**: knowledge graphs are incomplete and static, so SPARQL can only
return triples that already exist. GenSPARQL makes an LLM a first-class data source
inside the query algebra, so a single declarative query can answer things the KG alone
cannot. We evaluate three claimed benefits and are candid about the limitation.

| Benefit | Experiment | Headline |
|---|---|---|
| **Coverage** — answer what the KG can't express | **X1** column extension (player position) | SPARQL-only **0** → GENOP **70%** correct on the **77%** it answers (vs Wikidata P413) |
| **Composition** — KG structure ⨝ LLM value in one query | **X2** `BGP + GENOP + FILTER` | 41 defenders / 77 Group-A players; SPARQL-only **0** |
| **Efficiency** — LLM only on the bindings the KG keeps | **X4** cost-aware GENOP placement | **825 → 26** LLM calls (**31×**) by pushing KG selectivity before GENOP |
| **Entity consistency** (correctness anchor) | **X3** constrained selection / grounding | every returned entity is a real KG node; 0 hallucination leak |

## Setup

- **KG:** 2026 FIFA World Cup, built from Wikidata (`Q5020214`) — 48 teams, 12 groups,
  16 venues, 12 host cities (+metro aliases), 825 official squad players; 913 entities /
  2,716 triples, real Wikidata IRIs + clean `rdfs:label`. Builder:
  [`gensparql-example/data/build_worldcup2026_kg.py`](../gensparql-example/data/build_worldcup2026_kg.py).
- **Generation:** `Qwen/Qwen3-30B-A3B-Instruct-2507`, self-hosted with vLLM
  (OpenAI-compatible), non-thinking, `temperature=0`. See [`../deploy/`](../deploy/README.md).
- **Grounding / linking:** `JaccardSimText` (default) or bge-large embeddings via a local
  server; threshold θ = 0.85.
- **Ground truth (X1):** Wikidata `P413` (position played on team).

## X1 — Coverage: column extension (the linchpin)

GENOP supplies a column the KG does not model — each player's playing position — scored
against P413 at the coarse 4-class level {Goalkeeper, Defender, Midfielder, Forward}. An
open-ended prompt with an explicit *Unknown* abstention avoids option-position bias.

| Metric | Value |
|---|---|
| SPARQL-only answers (no position predicate) | **0** |
| Answer rate (commits a position) | **77%** (635/825; 190 abstain) |
| Accuracy on answered players with gold | **70.4%** (420/597); random 4-class = 25% |
| Gold coverage (players with P413) | 90% |

*Reading:* plain SPARQL returns nothing; GENOP fills the column, answers where it is
confident (abstaining otherwise), and is right ~2.8× above chance — and, crucially, this
is **measurable** because position has an external ground truth. Driver:
[`../eval/wc2026_x1_position.py`](../eval/wc2026_x1_position.py).

## X2 — Composition: one query, KG ⨝ LLM

```sparql
SELECT ?team ?player WHERE {
  ?t a ex:Team ; ex:inGroup ?g ; rdfs:label ?team .   # KG (authoritative)
  ?g rdfs:label "Group A" .
  ?p a ex:Athlete ; ex:playsFor ?t ; rdfs:label ?player .
  GENOP("What position does {?player} play? …", (?pos), <model:openai:…>)  # LLM
  FILTER(?pos = "Defender")                                                # compose
}
```

Runs as **one** declarative query: 41 defenders / 77 Group-A players (FILTER correctly
drops the rest). Plain SPARQL returns **0** (no position predicate); RAG/tool-calling
cannot re-enter the result into the relational algebra to join/filter it. Driver:
`LiveVllmSmokeTest#compositionFilterQuery…`.

## X4 — Efficiency: GENOP-aware planning

Context-mode GENOP issues one LLM call per input binding, so the number of bindings that
*reach* GENOP dominates cost. Same query semantics ("one team's midfielders"), two
authorings, LLM calls counted with a mock provider:

| Authoring | LLM calls |
|---|---|
| Selective BGP first | 26 |
| GENOP first, *before* the fix | **825** |
| GENOP first, *after* the fix | **26** |

The engine now reorders a simple group so each GENOP runs after the KG patterns that
constrain its inputs (basic-pattern joins commute → semantics-preserving), turning an
O(all-entities) fan-out into O(selected): **31× fewer LLM calls**, automatically,
regardless of how the query is written. This is expensive-predicate optimization with the
LLM as the expensive operator. Driver: `CostReorderTest`.

## X3 — Entity consistency (correctness anchor)

When GENOP output must join back to the KG, grounding/sim-join maps the generated string to
a KG node; base-mode generation over a KG type keeps only entities the KG contains. On the
WC KG (θ = 0.85, Jaccard): every grounded answer maps to a real KG entity (sim = 1.00),
e.g. teams 28/44 candidates, players 10, venues 11, cities 11/12 grounded — 0 hallucination
leak. Note this guarantees the **entity** is real, not that an *additive fact* is true (see
Limitations). The hybrid sim-join recovers accented near-misses (Group A: South Korea → Son
Heung-min, Mexico → Javier Hernández, Czechia → Tomáš Vaclík). Driver: `ExperimentRunner`
(`GS_PROFILE=wc2026`), `LiveVllmSmokeTest#hybridWorldCupQuery…`.

## Limitations (stated, not hidden)

- For **additive** facts the KG does not contain (a player's position, a 2026 result), the
  KG cannot verify them — correctness rests on the LLM. X1 quantifies this honestly (70% /
  abstains on 23%); grounding only guarantees **entity-level** consistency, not fact truth.
- Freshness is bounded by the LLM's training cutoff.
- FB15k-237 remains a near-zero boundary (opaque MIDs, non-world-knowledge relations); we
  report it rather than obscure it.

## Reproduce

```bash
# 0) build the KG (needs internet to Wikidata) and serve the models (see deploy/README.md)
python gensparql-example/data/build_worldcup2026_kg.py
sbatch deploy/serve_llm.slurm ; sbatch deploy/serve_embeddings.slurm

export OPENAI_API_KEY=dummy
export OPENAI_BASE_URL=http://<llm-node>:8000/v1
export GS_GEN_MODEL=Qwen/Qwen3-30B-A3B-Instruct-2507

# X1 — column-extension accuracy vs Wikidata P413
python eval/wc2026_x1_position.py

# X2 / X3 — composition + hybrid (live, gated on OPENAI_BASE_URL)
mvn -pl gensparql-integration-tests test \
  -Dtest='LiveVllmSmokeTest#compositionFilterQuery_bgpPlusGenopPlusFilter+hybridWorldCupQuery_bgpPlusGenopPlusJoin'

# X4 — cost-aware placement (mock provider, no server needed)
mvn -pl gensparql-integration-tests test -Dtest=CostReorderTest

# E1/E2 grounding-backend sweep on the bundled football KG (original drivers)
OPENROUTER_API_KEY=sk-or-... ./eval/run_eval.sh
```
