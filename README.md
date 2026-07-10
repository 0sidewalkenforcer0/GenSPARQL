# GenSPARQL

**Integrating generative LLM prompts as a first‑class construct in SPARQL.**

GenSPARQL extends SPARQL 1.1 (on top of [Apache Jena ARQ](https://jena.apache.org/))
with a `GENOP` operator that calls a Large Language Model during query evaluation,
binds the generated values to query variables, and joins them back against the
knowledge graph. It is a bridge between **open‑world generation** (what an LLM knows)
and **closed‑world validity** (what the KG contains).

> ⚠️ **Research prototype.** This is a proof‑of‑concept accompanying an ISWC demo
> paper. See [`docs/ISSUES_AND_FIXES.md`](docs/ISSUES_AND_FIXES.md) for known issues
> and the debugging history.

## The `GENOP` operator

`GENOP` appears alongside `FILTER`, `BIND`, and `OPTIONAL` in a graph pattern:

```sparql
GENOP("<prompt, may contain {?vars}>", (?out1, ?out2), <model:PROVIDER:MODEL> [, options])
```

**Context mode** — the prompt references bound variables and runs once per binding:

```sparql
SELECT ?scientist ?description WHERE {
  ?scientist a ex:Scientist ; ex:name ?name .
  GENOP("Write a one-sentence description of the scientist {?name}.",
        (?description),
        <model:openrouter:deepseek/deepseek-chat>)
}
```

**Base mode** — no input variables; the LLM generates values directly:

```sparql
SELECT ?laureate WHERE {
  GENOP("List five Nobel Prize-winning physicists. Return a JSON array of names.",
        (?laureate),
        <model:openrouter:deepseek/deepseek-chat>)
}
```

Optional arguments include a similarity `threshold` for approximate joins and
`grounding_relation` / `grounding_threshold` for mapping generated text to KG
entities. GenSPARQL also registers `gen:*` SPARQL functions
(`gen:similarity`, `gen:approxEq`, `gen:embedding`, `gen:classify`, `gen:extract`,
`gen:ground`, `gen:validate`, `gen:entail`, `gen:parseJSON`, `gen:tokenCost`).

## Architecture

| Module | Responsibility |
|--------|----------------|
| `gensparql-core` | Data models, text/embedding similarity, canonicalization, metrics |
| `gensparql-llm` | LLM provider abstraction (OpenAI, Anthropic, OpenRouter, Mock) + response cache & batching |
| `gensparql-parser` | JavaCC grammar: SPARQL 1.1 + the `GENOP` extension |
| `gensparql-engine` | `OpGenerate` algebra operator, `QueryIterGenerate`, similarity join, entity grounding |
| `gensparql-functions` | `gen:*` SPARQL extension functions |
| `gensparql-integration-tests` | Cross‑module tests |
| `gensparql-example` | Runnable example queries and demo data |
| `gensparql-benchmark` | KG‑completion benchmark harness (FB15k‑237, NELL‑995) — see [`gensparql-benchmark/DATA.md`](gensparql-benchmark/DATA.md) |

## Requirements

- Java 17+
- Maven 3.8+
- An LLM API key (OpenRouter by default). The `mock` provider needs no key.

## Build

```bash
mvn -q compile            # build all modules (generates the parser from the grammar)
mvn -q test               # run tests
```

## Quick start

```bash
export OPENROUTER_API_KEY="sk-..."      # or use the mock provider

cd gensparql-example
./run_query2.sh                          # context-mode GENOP over the demo dataset
```

The demo dataset lives in `gensparql-example/data/scientists_awards.ttl` and the
example queries in `gensparql-example/queries/`.

Programmatic use:

```java
GenSPARQL.init();                                  // register engine + functions
Query q = GenSPARQLQueryFactory.create(queryString);
try (QueryExecution qe = GenSPARQL.createQueryExecution(q, dataset)) {
    ResultSet rs = qe.execSelect();
    // ...
}
```

## Benchmark

The `gensparql-benchmark` module contains generators and runners for
knowledge‑graph completion over FB15k‑237 and NELL‑995. The datasets themselves are
**not** committed (see [`gensparql-benchmark/DATA.md`](gensparql-benchmark/DATA.md)).

## Citing

If you use GenSPARQL, please cite the accompanying ISWC demo paper (see the paper
project in the companion Overleaf/LaTeX sources).

## License

Released under the [MIT License](LICENSE).
