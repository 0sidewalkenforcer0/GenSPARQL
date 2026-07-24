#!/usr/bin/env bash
# Create a dedicated conda env for serving open-weight models with vLLM.
# vLLM bundles its own CUDA-matched torch, so keep this separate from other envs.
#
# Usage:  ./setup_vllm_env.sh [env_name]     (default env name: vllm)
set -euo pipefail

ENV_NAME="${1:-vllm}"

source "$(conda info --base)/etc/profile.d/conda.sh"

if conda env list | awk '{print $1}' | grep -qx "$ENV_NAME"; then
    echo "Conda env '$ENV_NAME' already exists; skipping create."
else
    conda create -y -n "$ENV_NAME" python=3.11
fi

conda activate "$ENV_NAME"
pip install --upgrade pip
# vLLM serves both chat models (/v1/chat/completions) and embedding models
# (/v1/embeddings with --task embed), so this single package covers both roles.
# Install the LATEST vLLM so recent Qwen3 architectures are supported.
pip install -U vllm

echo
echo "Done. Serve models with the SLURM scripts in this directory:"
echo "  sbatch deploy/serve_llm.slurm"
echo "  sbatch deploy/serve_embeddings.slurm"
