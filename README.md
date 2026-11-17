# GenSPARQL: A Generative Extension of SPARQL for Incomplete Knowledge Graphs

[![License](https://img.shields.io/badge/License-MIT-blue.svg)](LICENSE)
![Java](https://img.shields.io/badge/Java-17%2B-orange.svg)
![Maven](https://img.shields.io/badge/Maven-3.8%2B-blue.svg)
![SPARQL](https://img.shields.io/badge/SPARQL-1.1-green.svg)
![Apache Jena](https://img.shields.io/badge/Apache%20Jena-5.1.0-red.svg)
![Status](https://img.shields.io/badge/status-research%20prototype-yellow.svg)
[![Live demo](https://img.shields.io/badge/live-demo-0E7C86.svg)](https://0sidewalkenforcer0.github.io/GenSPARQL/)

**A generative extension of SPARQL 1.1 for Apache Jena, with LLM generation and entity grounding as first-class query operators.**

▶ **[Try the workbench](https://0sidewalkenforcer0.github.io/GenSPARQL/)** on two graphs built from Wikidata, the 2026 World Cup and the Nobel laureates. Its recorded mode replays the answers of three open-weight LLMs, and its live mode runs your own queries with a local model ([setup](#live-mode-with-a-local-model-ollama-no-provider-account)). ▶ **[Watch the 3-minute video](https://0sidewalkenforcer0.github.io/GenSPARQL/video/gensparql-demo.mp4)**.

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
- [Earlier Benchmark Material](#earlier-benchmark-material)
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
ollama pull mistral:7b               # once; Ollama serves on http://localhost:11434
ollama pull bge-large                # once, for the embedding similarity
./start-fuseki.sh
```

Open `http://localhost:8080/?mode=live`. The live panel is preset to Ollama on this machine: the endpoint `http://localhost:11434/v1`, the chat model `mistral:7b` and, for *Embedding (cosine)*, the embedding model `bge-large` at the same endpoint. The API key field can stay empty, because a server other than OpenAI's is used without a key. Ollama serves a 4-bit build of Mistral-7B-Instruct-v0.3, so its answers can differ from the recorded ones. The prompts and the texts to embed go to Ollama on this machine and nowhere else.

The endpoint, model and key apply only to that query; the server does not store them. Clients other than the workbench can configure the provider through the environment instead:

```bash
export OPENAI_BASE_URL=http://localhost:11434/v1   # or a vLLM server, or a hosted API
export OPENAI_API_KEY=...                          # only for a hosted API
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

The `gensparql-example/queries/` directory contains ready-to-use queries over the small demo graphs in `gensparql-example/data/`. All of them call `<model:openrouter:openai/gpt-4o-mini>`; any other model of a configured provider works too. The queries with a script run with `./run_queryN.sh` from `gensparql-example/`:

| File | Demonstrates | Script |
|------|--------------|--------|
| `query1_list_scientists_awards.sparql` | Basic SPARQL, no `GENOP` | `run_query1.sh` |
| `query2_generate_descriptions.sparql` | `GENOP` context mode (attribute completion) | `run_query2.sh` |
| `query3_list_nobel_laureates.sparql` | `GENOP` base mode (entity completion) | `run_query3.sh` |
| `query3_1_multi_output.sparql` | `GENOP` with multiple input and output variables | `run_query3_1.sh` |
| `query3_2_union.sparql` | Combining KG results with `GENOP` via `UNION` | `run_query3_2.sh` |
| `query4_filter_french_scientists.sparql` | Semantic `FILTER` using LLM knowledge | `run_query4.sh` |
| `query5_optional_genop.sparql` | Enriching results with `OPTIONAL` + `GENOP` | `run_query5.sh` |
| `query5_1_optional_selective.sparql` | Selective `OPTIONAL` enrichment | `run_query5_1.sh` |
| `query6_simscore_join.sparql` | Similarity join between KG and generated values (θ = 0.75) | `run_query6.sh` |
| `query7_cartesian_product.sparql` | `GENOP` inputs from independent BGPs | `run_query7.sh` |
| `query8_genop_before_bgp.sparql` | `GENOP` inputs bound by a later BGP | `run_query8.sh` |
| `query9_join_with_threshold.sparql` | Similarity join with the default threshold θ0 | none |
| `nobel_quantum_simjoin.sparql` | Similarity join over a Nobel Prize graph (θ = 0.75) | `run_nobel_quantum_simjoin.sh` |

The query without a script runs through the [Java API](#programmatic-use-java-api) or the HTTP service.

Run one from the example module:

```bash
cd gensparql-example
./run_query2.sh
```

`gensparql-example/data/` holds the small demo graphs of these queries (scientists and awards, footballers) and earlier builds of the two graphs of the paper. The graphs of the paper and the workbench are `docs/worldcup2026.ttl` and `docs/nobel-prize.ttl`, and the queries of the evaluation are in [`eval/www27/`](eval/www27/README.md).

### Programmatic Use (Java API)

```java
GenSPARQL.init();                                  // register engine + functions
Query q = GenSPARQLQueryFactory.create(queryString);
Context cxt = ARQ.getContext().copy();
cxt.set(GenSPARQLConstants.SIM_TEXT, new JaccardSimText());
try (QueryExecution qe = GenSPARQL.createQueryExecution(q, dataset, cxt)) {
    ResultSet rs = qe.execSelect();
    // ...
}
```

The context chooses the similarity of the similarity join: `JaccardSimText`, or the cosine of embeddings with

```java
LLMProvider ollama = new OpenAIProvider("", "http://localhost:11434/v1", "", "http://localhost:11434/v1");
cxt.set(GenSPARQLConstants.SIM_TEXT,
        new EmbeddingSimText(new JaccardSimText(), ollama, ModelSpec.fromURI("model:openai:bge-large")));
```

Without this setting, the engine uses the embeddings of the default provider. If the embedding model fails, the query falls back to the Jaccard similarity and logs a warning.

---

## The GENOP Operator

`GENOP` appears alongside `FILTER`, `BIND`, and `OPTIONAL` inside a graph pattern:

```sparql
GENOP("<prompt, may contain {?var} placeholders>", (?out1, ..., ?outk), <model:PROVIDER:MODEL> [, θ])
```

It runs in one of two modes, covering two kinds of completion the KG cannot answer alone.

Both examples are the queries of Figure 1 of the paper and run as written over the World Cup graph `docs/worldcup2026.ttl`, here with Mistral-7B on a local Ollama server. The engine itself asks the LLM for a JSON array, so the prompt does not need to.

**Attribute completion (context mode).** The prompt contains a placeholder, so the LLM is called once for each solution of the other patterns, here for each of the 26 players of Argentina, and fills in a property that the graph does not store:

```sparql
PREFIX ex:   <http://example.org/>
PREFIX rdfs: <http://www.w3.org/2000/01/rdf-schema#>
SELECT ?name ?pos WHERE {
  ?p ex:playsFor ?t .
  ?t rdfs:label "Argentina" .
  ?p rdfs:label ?name .
  GENOP("Position of {?name}?", (?pos), <model:openai:mistral:7b>)
}
```

**Entity completion (base mode).** The prompt has no placeholder, so a single LLM call returns a list of names, and the similarity join maps them to the athletes of the graph. Names of players outside the 2026 squads, such as Pelé, map to no entity:

```sparql
PREFIX ex:   <http://example.org/>
SELECT ?p WHERE {
  ?p a ex:Athlete .
  GENOP("List footballers who have won the World Cup.", (?p), <model:openai:mistral:7b>, 0.85)
}
```

The optional `θ` in [0, 1] is the threshold of the similarity join of this `GENOP` (see the next section); without it, the default `θ0 = 0.85` applies. The grammar also accepts `key: value` options of an experimental grounding mode that is off by default. Because `GENOP` lives in the query algebra, the planner places it after the KG patterns that constrain its inputs, so the LLM runs only on the bindings that survive. Within a group with the `GENOP` expressions G1, ..., Gn in written order, the planner orders the elements as follows:

- An `OPTIONAL`, `MINUS` or `BIND` is *deferred* if it mentions an output variable of a `GENOP`, or a variable of an earlier element that mentions one or is deferred itself.
- The other elements that mention no output variable come first. The filters over the variables that their triple patterns bind follow them, so they remove solutions before any prompt is sent.
- Then each Gi in written order, followed by the elements for which Gi is the last `GENOP` whose output variable they mention. These elements are joined with Gi by similarity (see below).
- Then the deferred elements, in written order, and finally the other filters.

An `OPTIONAL` or `BIND` that is not deferred comes before the `GENOP` expressions even when it is written after them, so it can bind a placeholder variable.

---

## Entity Grounding (Similarity Join)

A generated value is free text, whereas a KG term is an IRI or typed literal, so the two cannot be joined by equality. When a `GENOP` shares an output variable with a graph pattern of its group, GenSPARQL joins them by **similarity** instead, in both modes: it compares each generated value with the labels of the term that the pattern binds, by the Jaccard similarity of the lowercase word sets or by the cosine similarity of text embeddings, and keeps a match when the similarity reaches a threshold `θ`. Only the output variables of that `GENOP` are compared by similarity. Any other shared variable, the output of an earlier `GENOP` included, is compared by equality. A joined solution keeps the term of the graph. The default θ0 is 0.85. It is an engine parameter, which `MAVEN_OPTS=-Dgensparql.theta0=0.9 ./start-fuseki.sh` sets for the service and `GenSPARQL.setSimilarityThreshold(0.9)` sets in Java, and the fourth argument of `GENOP` sets another value for that `GENOP`, as in `query6_simscore_join.sparql` (0.75). The mode only decides how often the prompt is sent: a context-mode `GENOP` sends it once for every solution of the patterns that bind its placeholders, a base-mode `GENOP` once. The similarity join also applies inside `UNION`, `OPTIONAL` and `GRAPH`, where it reads the labels of the named graph. If the embedding model fails, the query falls back to the Jaccard similarity, logs a warning and reports the fallback in the trace of the HTTP service.

Every entity that the similarity join maps a name to belongs to the graph, but the mapping can be wrong: a name, also one that denotes no entity of the graph, can reach the threshold for an entity with a similar label. In the evaluation, the embedding similarity made one such match at `θ = 0.85` (George P. Smith to George E. Smith) and none at `θ = 0.9`. Generated attribute values are literals that are not mapped, and they are only as reliable as the LLM that generated them.

---

## Available Functions

All functions use the prefix `PREFIX gen: <http://gensparql.org/function#>`. They can be called inside `FILTER`, `BIND`, and `SELECT` expressions. They are auxiliary functions outside the semantics of the paper: `GENOP` and the similarity join do not use them. The functions that call an LLM or an embedding model use the default provider.

### Similarity Functions
| Function | Description |
|----------|-------------|
| `gen:similarity(?x, ?y)` | Cosine of the embeddings of two values; the Jaccard similarity of the lowercase words if the embeddings fail. It does not read the similarity that the query context sets for the similarity join |
| `gen:approxEq(?x, ?y, ?threshold)` | Whether `gen:similarity(?x, ?y)` reaches the threshold |
| `gen:embedding(?x)` | Embedding vector for text |

### NLP Functions
| Function | Description |
|----------|-------------|
| `gen:classify(?text, ?labels)` | Classify text into one of the given labels |
| `gen:extract(?text, ?schema)` | Extract structured fields from text |
| `gen:ground(?text)` | Ask the LLM for the Wikidata or DBpedia entity of a mention and return its IRI, without checking it against the queried graph |
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
| Ollama (local, OpenAI-compatible) | `<model:openai:mistral:7b>` | `OPENAI_BASE_URL=http://localhost:11434/v1` (no key needed) |
| Mock (no network) | `<model:mock:...>` | none |

Every provider asks the model for a JSON array and reads the values of every array in the answer; an empty array means no answer.

### Using open-weight models (vLLM)

To run the chat model *and* the embedding model as local open-weight models (for example on a SLURM cluster) instead of a hosted API, see [`deploy/README.md`](deploy/README.md). Serve the models with vLLM (`deploy/serve_llm.slurm`, `deploy/serve_embeddings.slurm`), then point the `openai` provider at them:

```bash
export OPENAI_BASE_URL=http://<llm-node>:8000/v1
export OPENAI_EMBEDDING_BASE_URL=http://<emb-node>:8001/v1
export OPENAI_EMBEDDING_MODEL=BAAI/bge-large-en-v1.5
# query model spec, for example: <model:openai:Qwen/Qwen3-30B-A3B-Instruct-2507>
```

`OPENAI_EMBEDDING_MODEL` (and `OPENAI_EMBEDDING_BASE_URL`) select the embedding model used by `gen:embedding` and embedding-based grounding.

---

## Evaluation

The evaluation runs on the two graphs of the workbench, built from Wikidata: the 2026 FIFA World Cup (1,248 players, 48 teams, 16 host cities and their stadiums; 4,000 triples) and the Nobel laureates (1,018 laureates, 1,026 awards; 10,849 triples). Three open-weight LLMs, Qwen3-30B-A3B-Instruct-2507, Qwen3-8B in non-thinking mode and Mistral-7B-Instruct-v0.3, were served by vLLM at temperature 0, and every answer went through `GENOP` in the HTTP service. The embedding similarity used bge-large-en-v1.5.

- **Completing attributes.** The generated positions are correct for 55-59% of the answered players (a uniform guess among the four positions: 26%), and the birth years for 69-90% of the answered laureates.
- **Mapping generated names.** Over six list queries, 20-61% of the generated names denote no entity of the graph. At `θ = 0.85`, the embedding similarity (bge-large-en-v1.5) maps 83% of the entities that the names denote, with one wrong match; the Jaccard similarity maps 67%, with none from `θ = 0.6` on.
- **LLM calls.** The planner reduces the calls of a composed query from 1,248 to 104, and those of a second one from 230 to 17.

[`eval/www27/README.md`](eval/www27/README.md) describes how to rerun the evaluation; `eval/www27/results/` holds the queries' results, the recorded LLM answers and the scores, and `eval/www27/gold/` the gold data.

---

## Earlier Benchmark Material

The folder `gensparql-benchmark/` holds query generators and run scripts for KG completion over FB15k-237 and NELL-995 from an earlier version of GenSPARQL. It is not a Maven module, it is not part of the evaluation above, and it has not been updated since July 2026. The datasets are **not** committed; see [`gensparql-benchmark/DATA.md`](gensparql-benchmark/DATA.md) for how to obtain and place them, and the generator scripts (`generate_gensparql_queries.py`, `generate_expected_answers.py`, `generate_run_scripts.py`) for the workflow.

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
├── gensparql-benchmark/          # Earlier KG-completion material (FB15k-237, NELL-995), not built by Maven
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
A hosted API needs its key exported in the shell (`OPENAI_API_KEY`, `ANTHROPIC_API_KEY` or `OPENROUTER_API_KEY`). A local OpenAI-compatible server such as Ollama or vLLM needs only `OPENAI_BASE_URL`, and no key. The `run_query*.sh` scripts call OpenRouter and stop when `OPENROUTER_API_KEY` is not set. To try GenSPARQL without a provider account, use the workbench with Ollama (see [Quick Verification](#quick-verification)), the `mock` provider, or `run_query1.sh`.

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
