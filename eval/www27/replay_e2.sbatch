#!/bin/bash
# Replay E2 for the three models with the current engine. Every list query finds its LLM
# answer in the records of results/<model>/llm-records (the run of 2026-11-03), so only the
# embedding model runs, and the generated names are those of that run.
#   sbatch --gpus=A40:1 eval/www27/replay_e2.sbatch            (after build.sbatch)
# Results go to results/e2-replay-<date>/<model>/; score them with
#   python3 eval/www27/score.py eval/www27/results/e2-replay-<date>/*
#SBATCH --job-name=gs-e2-replay
#SBATCH --partition=fastlane
#SBATCH --cpus-per-task=4
#SBATCH --mem=32G
#SBATCH --time=01:30:00
#SBATCH --output=gs-e2-replay-%j.out
set -euo pipefail
REPO=${REPO:-$HOME/workspace/GenSPARQL-www27}
EVAL=$REPO/eval/www27
OUT=${OUT:-$EVAL/results/e2-replay-$(date +%Y%m%d)}
MODELS=(Qwen/Qwen3-30B-A3B-Instruct-2507 Qwen/Qwen3-8B mistralai/Mistral-7B-Instruct-v0.3)
EMB=BAAI/bge-large-en-v1.5
PY=$HOME/.conda/envs/vllm/bin/python
export HF_HOME=$HOME/hf_cache HF_HUB_OFFLINE=1
export PATH=$HOME/.conda/envs/vllm/bin:$PATH VLLM_USE_FLASHINFER_SAMPLER=0
export JAVA_HOME=$HOME/tools/jdk-21.0.8+9
free_port() { "$PY" -c 'import socket; s = socket.socket(); s.bind(("", 0)); print(s.getsockname()[1]); s.close()'; }
EMB_PORT=$(free_port); GS_PORT=$(free_port)
mkdir -p "$OUT/logs" "$OUT/llm-records"
echo "job $SLURM_JOB_ID on $(hostname), commit $(git -C "$REPO" rev-parse --short HEAD)"

# One record file per list query with the answers of all three models; the keys name the model.
"$PY" -I - "$EVAL/results" "$OUT/llm-records" <<'PYEOF'
import json, os, sys
src, dst = sys.argv[1], sys.argv[2]
merged = {}
for model in ["Qwen_Qwen3-30B-A3B-Instruct-2507", "Qwen_Qwen3-8B", "mistralai_Mistral-7B-Instruct-v0.3"]:
    d = os.path.join(src, model, "llm-records")
    for f in sorted(os.listdir(d)):
        if f.startswith("e2_") and f.endswith(".json"):
            with open(os.path.join(d, f)) as fh:
                merged.setdefault(f, {}).update(json.load(fh))
for f, entries in sorted(merged.items()):
    with open(os.path.join(dst, f), "w") as fh:
        json.dump(entries, fh, indent=1)
    print(f, len(entries), "recorded answers")
PYEOF

pids=()
cleanup() { kill "${pids[@]}" 2>/dev/null || true; wait 2>/dev/null || true; }
trap cleanup EXIT
"$PY" -m vllm.entrypoints.openai.api_server --model "$EMB" --served-model-name "$EMB" --runner pooling \
    --port "$EMB_PORT" --gpu-memory-utilization 0.3 --max-model-len 512 > "$OUT/logs/vllm-emb.log" 2>&1 &
pids+=($!)
"$JAVA_HOME/bin/java" -Xmx8g -cp "$REPO/gensparql-server/target/classes:$(cat "$EVAL/server.classpath")" \
    -Dgensparql.ui.dir="$REPO/docs" -Dgensparql.recordDir="$OUT/llm-records" \
    org.gensparql.server.GenSPARQLFuseki "$GS_PORT" > "$OUT/logs/gensparql.log" 2>&1 &
pids+=($!)

for MODEL in "${MODELS[@]}"; do
    # The chat endpoint is never reached, since every prompt has a recorded answer; the
    # embedding server stands in for it only so that run_eval.py finds a server to wait for.
    "$PY" -I "$EVAL/run_eval.py" --gs "http://localhost:$GS_PORT" --llm-base "http://localhost:$EMB_PORT/v1" \
        --model "$MODEL" --emb-base "http://localhost:$EMB_PORT/v1" --emb-model "$EMB" \
        --data "$REPO/docs" --out "$OUT/$(echo "$MODEL" | tr '/' '_')" --only e2
done
echo "finished $(date)"
