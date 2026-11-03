#!/bin/bash
# One model: start vLLM for the model and for the embedding model, start the GenSPARQL
# HTTP service, and run run_eval.py against them. Arguments: MODEL [TP [SMOKE]]; the GPUs
# are given to sbatch (the cluster fails to set up jobs submitted with --export=ALL,...):
#   sbatch --gpus=A40:2 eval/www27/job.sbatch Qwen/Qwen3-30B-A3B-Instruct-2507 2
#   sbatch --gpus=A40:1 eval/www27/job.sbatch Qwen/Qwen3-8B
# SMOKE=1 (third argument) runs a few calls per experiment only; a fourth argument such as
# e2,e3 runs only those experiments, into a directory named after them.
#SBATCH --job-name=gs-eval
#SBATCH --partition=slowlane
#SBATCH --cpus-per-task=4
#SBATCH --mem=48G
#SBATCH --time=08:00:00
#SBATCH --output=gs-eval-%j.out
set -euo pipefail
MODEL=${1:?usage: job.sbatch MODEL [TP [SMOKE]]}
TP=${2:-1}
SMOKE=${3:-0}
ONLY=${4:-e1,e2,e3}
REPO=${REPO:-$HOME/workspace/GenSPARQL-www27}
EVAL=$REPO/eval/www27
SUFFIX=""; [ "$SMOKE" = 1 ] && SUFFIX="-smoke"; [ "$ONLY" != e1,e2,e3 ] && SUFFIX="$SUFFIX-${ONLY//,/-}"
OUT=${OUT:-$EVAL/results/$(echo "$MODEL" | tr '/' '_')$SUFFIX}
EMB=BAAI/bge-large-en-v1.5
PY=$HOME/.conda/envs/vllm/bin/python
export HF_HOME=$HOME/hf_cache HF_HUB_OFFLINE=1
# The job calls the env's python without activating the env, so its tools (ninja) need PATH.
# The FlashInfer sampler compiles kernels at start-up, which needs nvcc; greedy decoding
# does not need it, so vLLM samples with PyTorch.
export PATH=$HOME/.conda/envs/vllm/bin:$PATH VLLM_USE_FLASHINFER_SAMPLER=0
export JAVA_HOME=$HOME/tools/jdk-21.0.8+9
BASE=$((20000 + SLURM_JOB_ID % 20000))
LLM_PORT=$BASE; EMB_PORT=$((BASE + 1)); GS_PORT=$((BASE + 2))
mkdir -p "$OUT/logs"
echo "job $SLURM_JOB_ID on $(hostname): $MODEL, TP=$TP, GPUs ${CUDA_VISIBLE_DEVICES:-?}, commit $(git -C "$REPO" rev-parse --short HEAD)"

pids=()
cleanup() { kill "${pids[@]}" 2>/dev/null || true; wait 2>/dev/null || true; }
trap cleanup EXIT

# Qwen3-8B thinks before it answers unless the chat template is told otherwise.
EXTRA=()
case "$MODEL" in *Qwen3-8B*) EXTRA=(--default-chat-template-kwargs '{"enable_thinking": false}');; esac
"$PY" -m vllm.entrypoints.openai.api_server --model "$MODEL" --served-model-name "$MODEL" \
    --port "$LLM_PORT" --tensor-parallel-size "$TP" --max-model-len 4096 \
    --gpu-memory-utilization 0.82 "${EXTRA[@]}" > "$OUT/logs/vllm.log" 2>&1 &
pids+=($!)
for i in $(seq 1 180); do
    curl -sf "localhost:$LLM_PORT/v1/models" > /dev/null && break
    kill -0 "${pids[0]}" 2>/dev/null || { echo "vLLM exited"; tail -30 "$OUT/logs/vllm.log"; exit 1; }
    sleep 10
done
"$PY" -m vllm.entrypoints.openai.api_server --model "$EMB" --served-model-name "$EMB" --runner pooling \
    --port "$EMB_PORT" --gpu-memory-utilization 0.08 --max-model-len 512 > "$OUT/logs/vllm-emb.log" 2>&1 &
pids+=($!)
"$JAVA_HOME/bin/java" -Xmx8g -cp "$REPO/gensparql-server/target/classes:$(cat "$EVAL/server.classpath")" \
    -Dgensparql.ui.dir="$REPO/docs" -Dgensparql.recordDir="$OUT/llm-records" \
    org.gensparql.server.GenSPARQLFuseki "$GS_PORT" \
    > "$OUT/logs/gensparql.log" 2>&1 &
pids+=($!)

ARGS=(--gs "http://localhost:$GS_PORT" --llm-base "http://localhost:$LLM_PORT/v1" --model "$MODEL"
      --emb-base "http://localhost:$EMB_PORT/v1" --emb-model "$EMB" --data "$REPO/docs" --out "$OUT"
      --only "$ONLY")
[ "$SMOKE" = 1 ] && ARGS+=(--smoke)
"$PY" -I "$EVAL/run_eval.py" "${ARGS[@]}"
echo "finished $(date)"
