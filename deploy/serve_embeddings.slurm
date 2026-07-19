#!/usr/bin/env bash
# Serve an open-weight EMBEDDING model with vLLM as an OpenAI-compatible endpoint.
# Used by GenSPARQL grounding / similarity-join / gen:embedding via /v1/embeddings.
#
#   MODEL=BAAI/bge-large-en-v1.5 sbatch deploy/serve_embeddings.slurm
#
# Embedding models are small (<3 GB VRAM); one GPU is plenty, and this can share a
# node with the chat server. Point the client at it with OPENAI_EMBEDDING_BASE_URL.
#
#SBATCH --job-name=gensparql-emb
#SBATCH --partition=slowlane
#SBATCH --gres=gpu:A40:1
#SBATCH --cpus-per-task=8
#SBATCH --mem=32G
#SBATCH --time=24:00:00
#SBATCH --output=vllm_emb_%j.out
set -euo pipefail

MODEL="${MODEL:-BAAI/bge-large-en-v1.5}"   # or intfloat/e5-large-v2, Alibaba-NLP/gte-large-en-v1.5, BAAI/bge-m3
PORT="${PORT:-8001}"
VLLM_ENV="${VLLM_ENV:-vllm}"

export HF_HOME="${HF_HOME:-$HOME/hf_cache}"
mkdir -p "$HF_HOME"

source "$(conda info --base)/etc/profile.d/conda.sh"
conda activate "$VLLM_ENV"

NODE_HOST="$(hostname -f)"
echo "=============================================================="
echo " Serving EMBEDDING model : $MODEL"
echo " Node / port             : $NODE_HOST : $PORT"
echo
echo " In the GenSPARQL client shell, export:"
echo "   export OPENAI_EMBEDDING_BASE_URL=http://$NODE_HOST:$PORT/v1"
echo "   export OPENAI_EMBEDDING_MODEL=$MODEL"
echo "   # (OPENAI_EMBEDDING_API_KEY defaults to OPENAI_API_KEY / 'dummy')"
echo "=============================================================="

exec vllm serve "$MODEL" \
    --task embed \
    --host 0.0.0.0 --port "$PORT" \
    --served-model-name "$MODEL"
