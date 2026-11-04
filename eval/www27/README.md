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

The E2 numbers of the paper come from `results/e2-replay-20261104/` (engine `ee196ca`): `replay_e2.sbatch` replayed the recorded LLM answers of 2026-11-03 with the engine that compares a generated name with every label of an entity, and `score.py` scored them with the completed manual check (`summary.json` there). The generated names are those of the run of 2026-11-03; E1 and E3 come from that run (`results/summary.json`).

The planner measurement runs locally with the stub as the LLM, once with the service started as usual and once with `-Dgensparql.planner.reorder=false`:

```
python3 eval/www27/stub_llm.py 8089 &
mvn -q -f gensparql-server/pom.xml exec:java -Dexec.mainClass=org.gensparql.server.GenSPARQLFuseki \
    -Dexec.args=8080 -Dgensparql.ui.dir=docs [-Dgensparql.planner.reorder=false] &
python3 eval/www27/measure_calls.py --gs http://localhost:8080 --llm-base http://localhost:8089/v1 \
    --data docs --label reorder-on --out eval/www27/results
```
