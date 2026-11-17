# gensparql-functions

Auxiliary SPARQL functions under the prefix `PREFIX gen: <http://gensparql.org/function#>`. `GenSPARQL.init()` registers them, and they can be called inside `FILTER`, `BIND` and `SELECT` expressions. They are not part of the semantics in the paper: `GENOP` and the similarity join do not use them. The functions that call an LLM or an embedding model use the default provider (see the [repository README](../README.md#llm-providers-and-models)).

## Similarity

| Function | Description |
|----------|-------------|
| `gen:similarity(?x, ?y)` | Cosine of the embeddings of two values; the Jaccard similarity of the lowercase words if the embeddings fail. Embeddings are cached per endpoint, model and text. It does not read the similarity that the query context sets for the similarity join |
| `gen:approxEq(?x, ?y, ?threshold)` | Whether `gen:similarity(?x, ?y)` reaches the threshold |
| `gen:embedding(?x)` | Embedding vector for text |

## Language model

| Function | Description |
|----------|-------------|
| `gen:classify(?text, ?labels)` | Classify text into one of the given labels |
| `gen:extract(?text, ?schema)` | Extract structured fields from text |
| `gen:ground(?text)` | Ask the LLM for the Wikidata or DBpedia entity of a mention and return its IRI, without checking it against the queried graph |
| `gen:validate(?value, ?schema)` | Check whether a value conforms to a schema |
| `gen:entail(?premise, ?hypothesis)` | Textual entailment |

## Other

| Function | Description |
|----------|-------------|
| `gen:parseJSON(?raw, ?field)` | Extract a field from JSON text (`field`, `nested.field`, `array[0]`) |
| `gen:tokenCost(?prompt)` | Estimate token usage for a prompt |

`GenFunctionsTest` and `SimilarityFunctionCacheTest` in `gensparql-integration-tests` test these functions.
