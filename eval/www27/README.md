# Evaluation of the WWW'27 demo paper

Every LLM answer in these experiments passes through GENOP: `run_eval.py` only sends SPARQL
queries to the GenSPARQL HTTP service and records the bindings, the number of LLM requests
(`trace.genopCalls`) and the time.

| File | Purpose |
| --- | --- |
| `fetch_gold.py` | Fetches the gold data from Wikidata into `gold/` (run once, needs Internet) |
| `run_eval.py` | Runs E1 (completing attributes), E2 (mapping generated names) and E3 (LLM calls) |
| `score.py` | Scores the records against `gold/` and writes `results/summary.json` |
| `build.sbatch` | Builds GenSPARQL on a compute node and writes `server.classpath` |
| `job.sbatch` | Starts vLLM (model and `BAAI/bge-large-en-v1.5`) and the service, then runs `run_eval.py` |
| `stub_llm.py` | An OpenAI-compatible stub that answers `[]`, for counting calls without a model |
| `measure_calls.py` | Counts the LLM calls of the E3 queries with the planner on or off; results in `results/planner-calls.json` |
| `replay_e2.sbatch` | Replays the recorded LLM answers of E2 with the current engine; only the embedding model runs |
| `embedding_scores.py` | Computes the bge-large-en-v1.5 similarity of each team name of Mistral-7B in E2 to each team of the graph, as the similarity join does, checks it against the E2 replay and writes `results/e2-embedding-scores/` for the recorded mode of the workbench |
| `embed_texts.sbatch` | Embeds the texts for `embedding_scores.py` with `BAAI/bge-large-en-v1.5` on a CPU node |
| `make_recorded_data.py` | Writes the data of the recorded mode of the workbench (`docs/recorded-runs.js`) from the results |

On the cluster (from the repository root; the login node only submits jobs):

```
sbatch eval/www27/build.sbatch
sbatch --gpus=A40:1 eval/www27/job.sbatch Qwen/Qwen3-8B 1 1   # a few calls
sbatch --gpus=A40:2 eval/www27/job.sbatch Qwen/Qwen3-30B-A3B-Instruct-2507 2
sbatch --gpus=A40:1 eval/www27/job.sbatch Qwen/Qwen3-8B
sbatch --gpus=A40:1 eval/www27/job.sbatch mistralai/Mistral-7B-Instruct-v0.3
python3 eval/www27/score.py eval/www27/results/Qwen_* eval/www27/results/mistralai_*
```

All models run at temperature 0 (the default of GENOP). Qwen3-8B runs with thinking turned off.

E2 records the first LLM answer of each list query in `results/<model>/llm-records/` and replays it for the threshold runs. `job.sbatch` deletes these records before an E2 run, because a record that exists is loaded and replayed.

The E1 records of 2026-11-03 contain rows whose value is `[]`: the service of that day bound an empty array to the single output variable instead of dropping the solution. `score.py` counts such a row as no answer, so the scores are those of the corrected engine. The E2 records contain the same rows for the two queries that Qwen3-30B and Qwen3-8B declined; the threshold runs at 0.5 and 0.6 map this value to entities, which `score.py` counts as wrong matches.

The E2 numbers of the paper come from `results/e2-replay-20261113/` (engine `47edb63`, cluster job 118180): `replay_e2.sbatch` replayed the recorded LLM answers of 2026-11-03, and `score.py` scored them with the manual check in `gold/e2_manual.json` (`summary.json` there). The replay parses each recorded answer again with the current parser. The parser binds the values of every JSON array in an answer, so Mistral-7B's list of the Physics laureates of 2020 to 2025, which spreads 14 names over five arrays, now yields 14 names instead of the 2 of the last array. The other answers yield the names of the run of 2026-11-03. The earlier replay, `results/e2-replay-20261104/` (engine `ee196ca`), is kept for comparison. E1 and E3 come from the run of 2026-11-03 (`results/summary.json`).

The recorded mode of the workbench uses, for the embedding similarity, the scores in `results/e2-embedding-scores/` (cluster job 119666): `embed_texts.sbatch` embedded the 24 names of Mistral-7B and the labels of the 48 teams with bge-large-en-v1.5 on a CPU in float32, and `embedding_scores.py` computed the similarities as the similarity join does. At every threshold of E2 the teams that the scores reach are the teams of the embedding runs of the replay. The workbench draws only the best team of each name, which below 0.85 is fewer teams than the join returns (20 instead of 48 at 0.5).

The planner measurement runs locally with the stub as the LLM, once with the service started as usual and once with `-Dgensparql.planner.reorder=false`:

```
python3 eval/www27/stub_llm.py 8089 &
mvn -q -f gensparql-server/pom.xml exec:java -Dexec.mainClass=org.gensparql.server.GenSPARQLFuseki \
    -Dexec.args=8080 -Dgensparql.ui.dir=docs [-Dgensparql.planner.reorder=false] &
python3 eval/www27/measure_calls.py --gs http://localhost:8080 --llm-base http://localhost:8089/v1 \
    --data docs --label reorder-on --out eval/www27/results
```
