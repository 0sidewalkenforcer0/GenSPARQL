# Preliminary Evaluation — setup, raw results, and how to reproduce

This documents the numbers reported in the demo paper's *Preliminary Evaluation*
section. Drivers live in [`../eval/`](../eval); run them with
[`../eval/run_eval.sh`](../eval/run_eval.sh).

## Setup

- **Model:** `deepseek/deepseek-chat` via OpenRouter.
- **Grounding:** the engine's `JaccardSimText` (word-set) similarity at
  threshold `θ`. We use text similarity, **not** embeddings, because OpenRouter
  exposes no embedding endpoint (`supportsEmbedding=false`); this is the engine's
  default grounding path when no embedding provider is configured.
- **KG (E1/E2/E4):** `gensparql-example/data/footballers_eval.ttl`
  (26 athletes, 13 national teams, 12 clubs, 8 trophies, 26 cities; clean
  `rdfs:label`). Some well-known sibling entities are deliberately omitted
  (e.g. "Manchester City", "Inter Milan", "Europa League") so embedding grounding
  has real near-misses to over-match.
- **KG (E3):** FB15k-237+H (`entity2text.txt`, 14,951 entities). Not shipped in
  this repo — obtain separately and pass via `FB15K_DIR`.
- Numbers below are from a single representative run; LLM output is
  non-deterministic, so candidate counts vary slightly between runs.

## Grounding backend: text similarity vs. embeddings

Grounding maps an LLM's free-text output to a KG node, so it needs a **text**
similarity, not a knowledge-graph structural embedding. Two modes are supported:

- **Text similarity (default, no key):** `JaccardSimText` — deterministic,
  reproducible, used for the numbers in this document.
- **Embedding cosine (optional):** a **foundation text embedder** via any
  OpenAI-compatible `/embeddings` endpoint. This can rescue near-misses that
  Jaccard drops (e.g. "Quantum Physics"→"Quantum Mechanics", "MIT"→"Massachusetts
  Institute of Technology"). Generation stays on deepseek/OpenRouter; only
  grounding uses the embedder.

Enable embeddings by pointing the OpenAI-compatible client at a backend:

```bash
# Local, free, offline — a foundation embedding model via Ollama:
#   ollama pull nomic-embed-text
export OPENAI_API_KEY=ollama                       # any placeholder for local
export OPENAI_BASE_URL=http://localhost:11434/v1
export OPENAI_EMBEDDING_MODEL=nomic-embed-text     # or bge-m3, mxbai-embed-large
./eval/run_eval.sh                                 # driver auto-switches to embedding cosine

# Cloud alternative (turnkey): just an OpenAI key, default model text-embedding-3-small
export OPENAI_API_KEY=sk-...
```

> **Not for grounding: KG foundation models / KGE.** ULTRA, TransE, ComplEx, etc.
> embed *graph structure*, not text — they can't consume a generated string. They
> are the right tool for the *complementary* task of structural link prediction
> (the FB15k completion where the LLM path scores low in E3), and are a natural
> future-work pairing (LLM proposes candidates, a KGE scores their structural
> plausibility) rather than a grounding replacement.

## E1 — Grounding precision (θ = 0.85)

Five base-mode `GENOP` queries generate entities of a KG-present type. Every
returned answer grounds to a real KG entity; candidates with no KG counterpart
are dropped.

| Base-mode GENOP query   | LLM cand. | in KG | returned | KG-valid |
|-------------------------|:---------:|:-----:|:--------:|:--------:|
| World Cup winners       | 20 | 8 | 8 | 100% |
| national teams          | 15 | 8 | 8 | 100% |
| famous clubs            | 15 | 7 | 7 | 100% |
| major trophies          | 12 | 2 | 2 | 100% |
| player birthplaces      | 15 | 4 | 4 | 100% |
| **Total**               | **77** | **29** | **29** | **100%** |

Raw-LLM KG-validity = 29/77 = **38%**; GenSPARQL = **100%**, 0 hallucination leak.
Text (Jaccard) grounding; single representative run (deepseek non-determinism
shifts candidate counts slightly between runs).

## E2 — Threshold sensitivity (total grounded across the 5 queries)

Same candidates, grounded at each θ by both backends (`ExperimentRunnerCompare`):

| θ (backend)  | 0.50 | 0.60 | 0.70 | 0.80 | 0.85 | 0.90 | 0.95 | 1.00 |
|--------------|:----:|:----:|:----:|:----:|:----:|:----:|:----:|:----:|
| Jaccard      | 39 | 32 | 29 | 29 | 29 | 29 | 29 | 29 |
| Embedding    | 77 | 77 | 77 | 58 | 47 | 39 | 38 | 30 |

Embedding recall is θ-sensitive: a low θ grounds many candidates (recall ↑) at the
risk of spurious matches, and collapses toward text similarity as θ rises. Jaccard
grounds only near-exact matches and is almost flat.

## E4 — Systems performance (football KG)

| Query | Mode | LLM calls | wall-clock | note |
|-------|------|:---------:|:----------:|------|
| base-mode + grounding | base + grounding | 1 | ~4–8 s | one generation, then grounding |
| context enrichment    | context | one per binding | scales with bindings | ≈2 s / binding |

- SimScore (Jaccard) grounding over a candidate set: **3–10 ms** — negligible
  vs. the LLM.
- No cross-execution response-cache speedup observed (warm ≈ cold); latency is
  LLM-bound and scales with the number of generated bindings.

## Text vs. embedding grounding (fair comparison)

Same LLM candidates grounded both ways (`eval/ExperimentRunnerCompare.java`),
embeddings via local Ollama `nomic-embed-text`. Fuzzy (non-exact) matches were
classified correct/wrong by inspection; the driver prints every pair.

| Grounding backend | grounded | correct | precision |
|---|---|---|---|
| Text (Jaccard), θ=0.85 | 29 | 29 | **100%** |
| Embedding, θ=0.85 | 47 | 38 | 81% |
| Embedding, θ=0.90 | 39 | 38 | 97% |
| Embedding, θ=0.95 | 38 | 37 | 97% |

**Genuine recoveries embeddings add (Jaccard scores low):**
`Liverpool FC`→`Liverpool` (0.95), `Juventus FC`→`Juventus` (0.97),
`FIFA World Cup`→`World Cup` (0.98), `UEFA Champions League`→`Champions League`
(0.98), `São Paulo`→`Sao Paulo` (1.00, accent), `Xavi`→`Xavi Hernandez` (0.92).

**Semantic over-matching (false positives at θ≈0.85):**
`Manchester City`→Manchester United (0.85), `Inter Milan`→AC Milan (0.89),
`Atletico Madrid`→Real Madrid (0.89), `Premier League`→Champions League (0.86),
`AFC Asian Cup`→World Cup (0.86), `Porto`→Porto Alegre (0.87).

**Takeaway.** Embeddings' correct recoveries and false positives sit in the same
similarity band (~0.85–0.90), so no single θ separates them cleanly; matching
Jaccard's precision needs θ≈0.95, recovering only a little extra. Grounding
quality is bounded by the similarity space — neither backend dominates. Enable
embeddings with the env vars above; the E1/E2 driver auto-switches.

## E3 — Label-quality boundary (FB15k-237)

**Part A — structural (θ = 0.85, 306-entity sample).** Can a correct generated
name ground to its own KG entity?

| KG entity label | grounding recall |
|-----------------|:----------------:|
| raw MID (`/m/06rf7`) | **0%** |
| human-readable label | **100%** |

**Part B — end-to-end (15 sampled test triples, human labels + Jaccard).**
Hits@any = **2/15 (13%)**. Grounding works, but the LLM rarely predicts the
dataset-specific answer (e.g. "England →location contains→ Pontefract": it lists
20 English towns, grounds 19, misses the gold one).

**Takeaway.** Label recovery is *necessary but not sufficient*: opaque MIDs make
grounding structurally impossible (0%), and even with labels most benchmark
relations are not answerable from LLM world knowledge. This is why the fully
automated pipeline scores near zero on KG-completion benchmarks — a boundary we
report rather than obscure.

## Reproduce

```bash
export OPENROUTER_API_KEY=sk-or-...
# E1 + E2 + E4 (bundled football KG):
./eval/run_eval.sh
# add E3 (supply FB15k-237+H data directory):
FB15K_DIR=/path/to/FB15k-237+H ./eval/run_eval.sh
```
