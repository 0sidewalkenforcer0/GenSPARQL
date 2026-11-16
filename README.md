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
| LLM backend | a local model (Ollama or vLLM), a hosted API key, or `mock` | see [LLM Providers and Models](#llm-providers-and-models) |

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

### Maven Central (planned)

A first release, 0.1.0, is planned for Maven Central under the group `org.gensparql`, with the aggregate dependency `gensparql-libs` (declared with `<type>pom</type>`, as Jena's `apache-jena-libs`). It is not published yet, so build GenSPARQL from source as below. [RELEASING.md](RELEASING.md) describes how releases will be made.

### From Source

#### Step 1: Clone the Repository

```bash
git clone https://github.com/0sidewalkenforcer0/GenSPARQL.git
cd GenSPARQL
```

#### Step 2: Build

GenSPARQL is a multi-module Maven project on top of Apache Jena ARQ. Compiling also generates the SPARQL + `GENOP` parser from the JavaCC grammar.

```bash
mvn -q compile
```

With `-q`, Maven prints nothing when the build succeeds.

---

## Quick Verification

### Start the web workbench

The `gensparql-server` module embeds Fuseki, serves the workbench in `docs/`, and exposes a
GenSPARQL-aware API. Unlike Fuseki's standard query endpoint, the API parses queries with
`GenSPARQLQueryFactory`, so it accepts the `GENOP` syntax.

```bash
./start-fuseki.sh              # builds the modules and serves http://localhost:8080/
# or: ./start-fuseki.sh 9090
```

- `http://localhost:8080/` opens the recorded mode. It replays the answers that the LLMs gave in the evaluation, so it needs no model.
- `http://localhost:8080/?mode=live` opens the live mode, which sends each query to the service.

The service answers only connections from this machine, because a query names the endpoint that the service sends its prompts to. To accept connections from the network, start it with `MAVEN_OPTS=-Dgensparql.server.allowRemote=true ./start-fuseki.sh`. The read-only Fuseki dataset is at `/gensparql`, and the workbench uses:

```text
GET  /api/gensparql/health
POST /api/gensparql/datasets
POST /api/gensparql/query
```

### Live mode with a local model (Ollama, no provider account)

```bash
ollama pull qwen3:8b                 # once; Ollama serves on http://localhost:11434
ollama pull bge-large                # once, for the embedding similarity
./start-fuseki.sh
```

Open `http://localhost:8080/?mode=live` and, in the live panel, set the endpoint to `http://localhost:11434/v1` and the model to `qwen3:8b`. The API key field must not be empty, but Ollama ignores its value, so any text such as `ollama` will do. For the embedding similarity, choose *Embedding (cosine)*, set the embedding endpoint to `http://localhost:11434/v1` as well and the embedding model to `bge-large`. The prompts and the texts to embed then go to Ollama on this machine and nowhere else.

The endpoint, model and key apply only to that query; the server does not store them. Clients other than the workbench can configure the provider through the environment instead:

```bash
export OPENAI_API_KEY=ollama
export OPENAI_BASE_URL=http://localhost:11434/v1   # or a vLLM server, or a hosted API
./start-fuseki.sh
```

### Run Unit Tests

```bash
mvn -q test
```

### Run a Sample Query

```bash
cd gensparql-example
./run_query1.sh          # plain SPARQL over the demo data, no LLM call
```

The other scripts run `GENOP` queries through OpenRouter and stop with a message if `OPENROUTER_API_KEY` is not set (see [below](#llm-providers-and-models)):

```bash
export OPENROUTER_API_KEY="sk-or-..."
./run_query2.sh          # context-mode GENOP over the demo dataset
```


---

## Usage Examples

### Example Queries

The `gensparql-example/queries/` directory contains ready-to-use queries. The queries 1 to 8 have a matching `run_queryN.sh` script, and they call `<model:openrouter:openai/gpt-4o-mini>`; any other model of a configured provider works too:

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
| `query9_join_with_threshold.sparql` | Similarity join with the default threshold |

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

The bracketed `options` set parameters such as the grounding similarity threshold `θ`. Because `GENOP` lives in the query algebra, the planner places it after the KG patterns that constrain its inputs, so the LLM runs only on the bindings that survive. Within a group, the planner evaluates the elements in the order of the paper's translation: first every element that mentions no output variable of a `GENOP`, including `OPTIONAL`, `MINUS`, `BIND` and `VALUES`, then each `GENOP` in written order followed by the elements that mention its output, then the `OPTIONAL`, `MINUS` and `BIND` that mention an output, and the filters last.

---

## Entity Grounding (Similarity Join)

A generated value is free text, whereas a KG term is an IRI or typed literal, so the two cannot be joined by equality. When a `GENOP` shares an output variable with a graph pattern of its group, GenSPARQL joins them by **similarity** instead, in both modes: it compares each generated value with the labels of the term that the pattern binds, by the Jaccard similarity of the lowercase word sets or by the cosine similarity of text embeddings, and keeps a match when the similarity reaches a threshold `θ`. Only the output variables of that `GENOP` are compared by similarity. Any other shared variable, the output of an earlier `GENOP` included, is compared by equality. A joined solution keeps the term of the graph. The default is 0.85, and the fourth argument of `GENOP` sets another value, as in `query6_simscore_join.sparql` (0.75). The mode only decides how often the prompt is sent: a context-mode `GENOP` sends it once for every solution of the patterns that bind its placeholders, a base-mode `GENOP` once. The similarity join also applies inside `UNION` and `OPTIONAL`.

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
| Ollama (local, OpenAI-compatible) | `<model:openai:qwen3:8b>` | `OPENAI_BASE_URL=http://localhost:11434/v1`, `OPENAI_API_KEY=ollama` |
| Mock (no network) | `<model:mock:...>` | none |

Every provider asks the model for a JSON array and reads the values of every array in the answer; an empty array means no answer.

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

The evaluation runs on the two graphs of the workbench, built from Wikidata: the 2026 FIFA World Cup (1,248 players, 48 teams, 16 host cities and their stadiums; 4,000 triples) and the Nobel laureates (1,018 laureates, 1,026 awards; 10,849 triples). Three open LLMs (Qwen3-30B-A3B-Instruct-2507, Qwen3-8B and Mistral-7B-Instruct-v0.3) were served by vLLM at temperature 0, and every answer went through `GENOP` in the HTTP service.

- **Completing attributes.** The generated positions are correct for 55-59% of the answered players (a uniform guess among the four positions: 26%), and the birth years for 69-90% of the answered laureates.
- **Mapping generated names.** Over six list queries, 20-61% of the generated names denote no entity of the graph. At `θ = 0.85`, the embedding similarity (bge-large-en-v1.5) maps 83% of the entities that the names denote, with one wrong match; the Jaccard similarity maps 67%, with none from `θ = 0.6` on.
- **LLM calls.** The planner reduces the calls of a composed query from 1,248 to 104, and those of a second one from 230 to 17.

[`eval/www27/README.md`](eval/www27/README.md) describes how to rerun the evaluation; `eval/www27/results/` holds the queries' results, the recorded LLM answers and the scores, and `eval/www27/gold/` the gold data.

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
├── gensparql-server/             # HTTP service: embedded Fuseki, the workbench API
├── gensparql-libs/               # Aggregate dependency of the library modules
├── gensparql-integration-tests/  # Cross-module tests
├── gensparql-example/            # Runnable example queries and demo data
│   ├── data/                     # Demo RDF datasets
│   ├── queries/                  # Sample SPARQL queries
│   └── run_query*.sh             # Per-query run scripts
├── gensparql-benchmark/          # KG-completion benchmark harness (FB15k-237, NELL-995)
├── eval/www27/                   # Evaluation: runner, scoring, gold data, results and recorded LLM answers
├── deploy/                       # vLLM serving scripts for local open-weight models (SLURM)
├── docs/                         # Web workbench (GitHub Pages) and the two graphs
├── start-fuseki.sh               # Builds and starts the HTTP service with the workbench
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
`GENOP` queries need a provider key exported in the shell; the `run_query*.sh` scripts stop when `OPENROUTER_API_KEY` is not set. To try GenSPARQL without a provider account, use the workbench with Ollama (see [Quick Verification](#quick-verification)), the `mock` provider, or `run_query1.sh`.

### Parser errors after editing the grammar
The parser is generated at build time. Recompile from the project root so the grammar is regenerated:
```bash
mvn -q compile
```

---

## Citing

If you use GenSPARQL, please cite the software:

```bibtex
@software{gensparql2026,
  title  = {GenSPARQL: A Generative Extension of SPARQL for Incomplete Knowledge Graphs},
  author = {Wu, Jingcheng and Wang, Yi and Zhou, Hongkuan and Staab, Steffen and Xiong, Bo},
  year   = {2026},
  url    = {https://github.com/0sidewalkenforcer0/GenSPARQL}
}
```

---

## License

This project is licensed under the [MIT License](LICENSE).
