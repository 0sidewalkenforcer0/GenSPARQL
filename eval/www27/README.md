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
