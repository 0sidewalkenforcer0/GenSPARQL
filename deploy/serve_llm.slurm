#!/usr/bin/env bash
# Serve an open-weight chat model with vLLM as an OpenAI-compatible endpoint.
# GenSPARQL talks to it via the "openai" provider (set OPENAI_BASE_URL below).
#
#   MODEL=Qwen/Qwen2.5-7B-Instruct TP=1 sbatch deploy/serve_llm.slurm
#   MODEL=Qwen/Qwen2.5-72B-Instruct-AWQ TP=2 sbatch deploy/serve_llm.slurm
#
#SBATCH --job-name=gensparql-llm
#SBATCH --partition=slowlane
#SBATCH --gres=gpu:A100:1
#SBATCH --cpus-per-task=16
#SBATCH --mem=128G
#SBATCH --time=24:00:00
#SBATCH --output=vllm_llm_%j.out
set -euo pipefail

# --- config (override via environment) ---------------------------------------
MODEL="${MODEL:-Qwen/Qwen2.5-7B-Instruct}"   # HF repo id (also the model name clients send)
PORT="${PORT:-8000}"
TP="${TP:-1}"                                # tensor-parallel size = number of GPUs requested
DTYPE="${DTYPE:-bfloat16}"
MAXLEN="${MAXLEN:-8192}"
VLLM_ENV="${VLLM_ENV:-vllm}"

# Keep the (large) HF weight cache off the small quota'd default location.
export HF_HOME="${HF_HOME:-$HOME/hf_cache}"
mkdir -p "$HF_HOME"

source "$(conda info --base)/etc/profile.d/conda.sh"
conda activate "$VLLM_ENV"

NODE_HOST="$(hostname -f)"
echo "=============================================================="
echo " Serving CHAT model : $MODEL"
echo " Node / port        : $NODE_HOST : $PORT   (TP=$TP, dtype=$DTYPE)"
echo " HF_HOME            : $HF_HOME"
echo
echo " In the GenSPARQL client shell, export:"
echo "   export OPENAI_API_KEY=dummy"
echo "   export OPENAI_BASE_URL=http://$NODE_HOST:$PORT/v1"
echo " and write query model specs as:  <model:openai:$MODEL>"
echo "=============================================================="

exec vllm serve "$MODEL" \
    --host 0.0.0.0 --port "$PORT" \
    --tensor-parallel-size "$TP" \
    --dtype "$DTYPE" \
    --max-model-len "$MAXLEN" \
    --served-model-name "$MODEL"
