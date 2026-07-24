# GenSPARQL: A Generative Extension of SPARQL for Incomplete Knowledge Graphs

[![License](https://img.shields.io/badge/License-MIT-blue.svg)](LICENSE)
![Java](https://img.shields.io/badge/Java-17%2B-orange.svg)
![Maven](https://img.shields.io/badge/Maven-3.8%2B-blue.svg)
![SPARQL](https://img.shields.io/badge/SPARQL-1.1-green.svg)
![Apache Jena](https://img.shields.io/badge/Apache%20Jena-5.1.0-red.svg)
![Status](https://img.shields.io/badge/status-research%20prototype-yellow.svg)
[![Live demo](https://img.shields.io/badge/live-demo-0E7C86.svg)](https://0sidewalkenforcer0.github.io/GenSPARQL/)

**A generative extension of SPARQL 1.1 for Apache Jena, with LLM generation and entity grounding as first-class query operators.**

▶ **[Try the interactive demo](https://0sidewalkenforcer0.github.io/GenSPARQL/)** — explore the three query shapes over the 2026 World Cup KG, with a live grounding-threshold slider.

A SPARQL query returns only what a knowledge graph (KG) explicitly stores, so it cannot return an attribute the KG never modeled or an entity it does not record. Large language models (LLMs) hold broad open-world knowledge, but they optimize likelihood over truth and can hallucinate. GenSPARQL bridges the two by adding two operators that live inside the query algebra: a first-class `GENOP` that calls an LLM during query evaluation and binds the generated values to query variables, and a type-aware similarity join that grounds generated entities to real KG nodes. Because `GENOP` is an operator rather than an external call, the query planner defers it behind selective graph patterns, so the LLM runs only on the bindings that survive.

---

## Table of Contents

- [Requirements](#requirements)
- [Installation](#installation)
- [Quick Verification](#quick-verification)
- [Usage Examples](#usage-examples)
- [The GENOP Operator](#the-genop-operator)
- [Entity Grounding (Similarity Join)](#entity-grounding-similarity-join)
- [Available Functions](#available-functions)
- [LLM Providers and Models](#llm-providers-and-models)
- [Evaluation](#evaluation)
- [Benchmark Reproduction](#benchmark-reproduction)
- [Project Structure](#project-structure)
- [Troubleshooting](#troubleshooting)
- [Citing](#citing)
- [License](#license)

---

## Requirements

| Requirement | Version | Check Command |
|-------------|---------|---------------|
| Java (JDK)  | **17 or higher** | `java -version` |
| Maven       | **3.8 or higher** | `mvn -version` |
| LLM backend | a hosted key, a local vLLM model, or `mock` | see [LLM Providers and Models](#llm-providers-and-models) |

### Installing Java 17 (if needed)

**macOS (Homebrew):**
```bash
brew install --cask temurin@17
```

**Ubuntu/Debian:**
```bash
sudo apt install openjdk-17-jdk
```

**Windows:**
Download from [Adoptium](https://adoptium.net/temurin/releases/?version=17)

---

## Installation

### Step 1: Clone the Repository

```bash
git clone https://github.com/0sidewalkenforcer0/GenSPARQL.git
cd GenSPARQL
```

### Step 2: Build

GenSPARQL is a multi-module Maven project on top of Apache Jena ARQ. Compiling also generates the SPARQL + `GENOP` parser from the JavaCC grammar.

```bash
mvn -q compile
```

**Expected output:**
```
[INFO] BUILD SUCCESS
```

---

## Quick Verification

### Run Unit Tests

```bash
mvn -q test
```

### Run a Sample Query

The `mock` provider needs no API key and is the fastest way to confirm the engine works end to end:

```bash
cd gensparql-example
./run_query1.sh          # basic SPARQL, no LLM call
```

To run a query that actually calls an LLM, set a provider key first (see [below](#llm-providers-and-models)):

```bash
export OPENROUTER_API_KEY="sk-..."
./run_query2.sh          # context-mode GENOP over the demo dataset
```


---

## Usage Examples

### Example Queries

The `gensparql-example/queries/` directory contains ready-to-use queries, each with a matching `run_queryN.sh` script:

| File | Demonstrates |
|------|--------------|
| `query1_list_scientists_awards.sparql` | Basic SPARQL, no `GENOP` |
| `query2_generate_descriptions.sparql` | `GENOP` context mode (attribute completion) |
| `query3_list_nobel_laureates.sparql` | `GENOP` base mode (entity completion) |
| `query3_1_multi_output.sparql` | `GENOP` with multiple input and output variables |
| `query3_2_union.sparql` | Combining KG results with `GENOP` via `UNION` |
| `query4_filter_french_scientists.sparql` | Semantic `FILTER` using LLM knowledge |
| `query5_optional_genop.sparql` | Enriching results with `OPTIONAL` + `GENOP` |
| `query5_1_optional_selective.sparql` | Selective `OPTIONAL` enrichment |
| `query6_simscore_join.sparql` | Similarity join between KG and generated values |
| `query7_cartesian_product.sparql` | `GENOP` inputs from independent BGPs |
| `query8_genop_before_bgp.sparql` | `GENOP` inputs bound by a later BGP |
| `query9_join_with_threshold.sparql` | Similarity join with an explicit threshold |

Run one from the example module:

```bash
cd gensparql-example
./run_query2.sh
```

The demo datasets live in `gensparql-example/data/` (a scientists/awards graph and a footballers graph).

### Programmatic Use (Java API)

```java
GenSPARQL.init();                                  // register engine + functions
Query q = GenSPARQLQueryFactory.create(queryString);
try (QueryExecution qe = GenSPARQL.createQueryExecution(q, dataset)) {
    ResultSet rs = qe.execSelect();
    // ...
}
```

---

## The GENOP Operator

`GENOP` appears alongside `FILTER`, `BIND`, and `OPTIONAL` inside a graph pattern:

```sparql
GENOP("<prompt, may contain {?vars}>", (?out1, ?out2), <model:PROVIDER:MODEL> [, options])
```

It runs in one of two modes, covering two kinds of completion the KG cannot answer alone.

**Attribute completion (context mode).** The prompt references bound variables and runs once per surviving binding, filling a property the KG never models:

```sparql
SELECT ?name ?position WHERE {
  ?p a ex:Athlete ; rdfs:label ?name .
  GENOP("What position does {?name} play?", (?position),
        <model:openrouter:deepseek/deepseek-chat>)
}
```

**Entity completion (base mode).** The prompt has no input variables, so a single LLM call can produce many result tuples, returning entities the KG is missing:

```sparql
SELECT ?player WHERE {
  ?player a ex:Athlete .
  GENOP("List footballers who have won the FIFA World Cup. Return ONLY a JSON array of names.",
        (?player), <model:openrouter:deepseek/deepseek-chat>, 0.85)
}
```

The bracketed `options` set parameters such as the grounding similarity threshold `θ`. Because `GENOP` lives in the query algebra, the planner places it after the KG patterns that constrain its inputs, so the LLM runs only on the bindings that survive.

---

## Entity Grounding (Similarity Join)

A generated value is free text, whereas a KG term is an IRI or typed literal, so the two cannot be joined by equality. When a `GENOP` output variable is also bound by another graph pattern, GenSPARQL joins them by **similarity** instead: it compares each generated string against the canonical labels of the KG entities using text or embedding similarity, and keeps a match only when the score clears a threshold `θ` (ordinary equality join is the case `θ = 1`). The threshold is supplied as a `GENOP` option, as in `query9_join_with_threshold.sparql`.

Grounding guarantees **membership, not truth**. It drops any generated name the KG does not contain, so no hallucinated *entity* leaks through. Generated *attribute* values are literals, are not grounded, and are only as reliable as the model that produced them.

---

## Available Functions

All functions use the prefix `PREFIX gen: <http://gensparql.org/function#>`. They can be called inside `FILTER`, `BIND`, and `SELECT` expressions.

### Similarity Functions
| Function | Description |
|----------|-------------|
| `gen:similarity(?x, ?y)` | Semantic similarity between two values |
| `gen:approxEq(?x, ?y, ?threshold)` | Approximate equality test |
| `gen:embedding(?x)` | Embedding vector for text |

### NLP Functions
| Function | Description |
|----------|-------------|
| `gen:classify(?text, ?labels)` | Classify text into one of the given labels |
| `gen:extract(?text, ?schema)` | Extract structured fields from text |
| `gen:ground(?text)` | Map a natural-language mention to a KG entity (IRI) |
| `gen:validate(?value, ?schema)` | Check whether a value conforms to a schema |
| `gen:entail(?premise, ?hypothesis)` | Textual entailment |
| `gen:parseJSON(?raw, ?field)` | Extract a field from JSON text (`field`, `nested.field`, `array[0]`) |
| `gen:tokenCost(?prompt)` | Estimate token usage for a prompt |

---

## LLM Providers and Models

The target model is named inline in the query as `<model:PROVIDER:MODEL>`. Supported providers:

| Provider | Model reference | Environment variable |
|----------|-----------------|----------------------|
| OpenRouter | `<model:openrouter:deepseek/deepseek-chat>` | `OPENROUTER_API_KEY` |
| OpenAI | `<model:openai:gpt-4o>` | `OPENAI_API_KEY` |
| Anthropic | `<model:anthropic:claude-...>` | `ANTHROPIC_API_KEY` |
| Self-hosted (vLLM, OpenAI-compatible) | `<model:openai:Qwen/Qwen3-30B-A3B-Instruct-2507>` | `OPENAI_BASE_URL` (+ `OPENAI_API_KEY`) |
| Mock (no network) | `<model:mock:...>` | none |

A response cache and request batching reduce repeated calls.

### Using open-weight models (vLLM)

To run the chat model *and* the embedding model as local open-weight models (for example on a SLURM cluster) instead of a hosted API, see [`deploy/README.md`](deploy/README.md). Serve the models with vLLM (`deploy/serve_llm.slurm`, `deploy/serve_embeddings.slurm`), then point the `openai` provider at them:

```bash
export OPENAI_API_KEY=dummy
export OPENAI_BASE_URL=http://<llm-node>:8000/v1
export OPENAI_EMBEDDING_BASE_URL=http://<emb-node>:8001/v1
export OPENAI_EMBEDDING_MODEL=BAAI/bge-large-en-v1.5
# query model spec: <model:openai:Qwen/Qwen2.5-7B-Instruct>
```

`OPENAI_EMBEDDING_MODEL` (and `OPENAI_EMBEDDING_BASE_URL`) select the embedding model used by `gen:embedding` and embedding-based grounding.

---

## Evaluation

The paper evaluates GenSPARQL on a 2026 FIFA World Cup KG built from Wikidata (48 national teams, 825 official squad players, 913 entities with real IRIs and `rdfs:label`s):

- **Coverage.** `GENOP` recovers an attribute the KG never modeled (player position) with 69-91% accuracy across three model families, where SPARQL alone returns nothing.
- **Validity.** Grounding binds every returned entity to a real KG node, with no hallucinated entity getting through (100% validity).
- **Cost.** Deferring `GENOP` behind selective KG patterns reduces LLM calls by 31x without changing the result.

See [`docs/EVALUATION.md`](docs/EVALUATION.md) for the full protocol and numbers.

---

## Benchmark Reproduction

The `gensparql-benchmark` module contains generators and runners for KG completion over FB15k-237 and NELL-995. The datasets themselves are **not** committed; see [`gensparql-benchmark/DATA.md`](gensparql-benchmark/DATA.md) for how to obtain and place them, and the generator scripts (`generate_gensparql_queries.py`, `generate_expected_answers.py`, `generate_run_scripts.py`) for the workflow.

---

## Project Structure

```
GenSPARQL/
├── gensparql-core/               # Data models, text/embedding similarity, canonicalization, metrics
├── gensparql-llm/                # LLM provider abstraction (OpenAI, Anthropic, OpenRouter, self-hosted, Mock) + cache & batching
├── gensparql-parser/             # JavaCC grammar: SPARQL 1.1 + the GENOP extension
├── gensparql-engine/             # OpGenerate algebra operator, QueryIterGenerate, similarity join, grounding
├── gensparql-functions/          # gen:* SPARQL extension functions
├── gensparql-integration-tests/  # Cross-module tests
├── gensparql-example/            # Runnable example queries and demo data
│   ├── data/                     # Demo RDF datasets
│   ├── queries/                  # Sample SPARQL queries
│   └── run_query*.sh             # Per-query run scripts
├── gensparql-benchmark/          # KG-completion benchmark harness (FB15k-237, NELL-995)
├── eval/                         # Experiment runner and evaluation result JSON (X1-X4)
├── deploy/                       # vLLM serving scripts for local open-weight models (SLURM)
├── docs/                         # Evaluation notes and issue/fix history
└── pom.xml                       # Maven build configuration
```

---

## Troubleshooting

### "Java version not supported"
Ensure Java 17+ is installed and set as default:
```bash
java -version  # should show 17 or higher
```

### Missing or invalid API key
`GENOP` queries need a provider key exported in the shell (or edited into the `run_query*.sh` script). Use the `mock` provider or `run_query1.sh` to test without a key.

### Parser errors after editing the grammar
The parser is generated at build time. Recompile from the project root so the grammar is regenerated:
```bash
mvn -q compile
```

---

## Citing

If you use GenSPARQL, please cite the ISWC 2026 demo paper:

```bibtex
@inproceedings{gensparql2026,
  title     = {GenSPARQL: A Generative Extension of SPARQL for Incomplete Knowledge Graphs},
  author    = {Wu, Jingcheng and Wang, Yi and Zhou, Hongkuan and Staab, Steffen and Xiong, Bo},
  booktitle = {ISWC 2026: The 25th International Semantic Web Conference,
               Posters, Demos, and Industry Tracks},
  year      = {2026}
}
```

---

## License

This project is licensed under the [MIT License](LICENSE).
