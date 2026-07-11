# Benchmark data

The raw knowledge‑graph datasets are **not** included in this repository (they are
tens to hundreds of MB). This module ships only the query generators, run scripts,
and generated query/answer definitions under `Queries-FB15k-237+H/` and
`Queries-NELL995+H/`.

## Datasets used

- **FB15k‑237** — a subset of Freebase widely used for KG completion.
- **NELL‑995** — a subset of the NELL knowledge base.

Both are standard, publicly available KG‑completion benchmarks (e.g. from the
`datasets_knowledge_embedding` collection). Obtain the `train` / `valid` / `test`
triple files from the original sources.

## Preparing the data

Place the raw triples under `GENSPARQL_Data/<DATASET>/` and convert them to
N‑Triples with the provided scripts:

```bash
cd gensparql-benchmark/GENSPARQL_Data
python3 convert_to_rdf.py            # triples -> RDF/N-Triples
python3 add_entity_names.py          # attach human-readable entity names
./load_to_jena.sh                    # load into a Jena TDB store (optional)
```

Then generate GenSPARQL queries and run the benchmark:

```bash
cd gensparql-benchmark
python3 generate_gensparql_queries_v2.py
export OPENROUTER_API_KEY="sk-..."
./run_2i_3i_benchmark.sh
```

Expected file layout after preparation:

```
GENSPARQL_Data/FB15k-237+H/{train,valid,test}.nt
GENSPARQL_Data/NELL995+H/{train,valid,test}.nt
```

## Clean entity labels (FB15k)

FB15k entity URIs embed variable-underscore MIDs, so labels cannot be recovered
from the URIs alone. Fetch the standard MID→name file and build a clean
`URI → name` map:

```bash
cd gensparql-benchmark/GENSPARQL_Data
curl -fsSL https://raw.githubusercontent.com/yao8839836/KG-BERT/master/data/FB15k-237/entity2text.txt \
     -o FB15k-237+H/entity2text.txt
python3 build_entity_labels.py FB15k-237+H     # -> FB15k-237+H/entity_labels.tsv
```

`GenSPARQLExample` auto-loads `entity_labels.tsv` (next to `train.nt`) and calls
`CanonicalForm.setLabelLookup(...)`; the query generator uses the same map for
constrained-generation candidate lists.
