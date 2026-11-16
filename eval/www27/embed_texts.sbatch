#!/bin/bash
# Embed the texts of IN.json with BAAI/bge-large-en-v1.5 and write the vectors to VECTORS.json for
# embedding_scores.py:
#   sbatch eval/www27/embed_texts.sbatch IN.json VECTORS.json
# The model runs through transformers on the CPU, in float32, with the CLS pooling that
# sentence-transformers and vLLM apply to this model, so the job needs no GPU. The cosines agree
# with those of the vLLM server of E2 (float16 on a GPU) at every threshold of E2, see
# embedding_scores.py.
#SBATCH --job-name=gs-emb-cpu
#SBATCH --partition=fastlane
#SBATCH --cpus-per-task=8
#SBATCH --mem=16G
#SBATCH --time=00:15:00
#SBATCH --output=gs-emb-cpu-%j.out
set -euo pipefail
IN=$(realpath "$1"); OUT=$(realpath -m "$2")
PY=$HOME/.conda/envs/vllm/bin/python
export HF_HOME=$HOME/hf_cache HF_HUB_OFFLINE=1 OMP_NUM_THREADS=8
echo "job $SLURM_JOB_ID on $(hostname)"
"$PY" -I - "$IN" "$OUT" "$SLURM_JOB_ID" <<'PYEOF'
import json, sys, torch, transformers
from transformers import AutoTokenizer, AutoModel
src, dst, job = sys.argv[1:4]
name = "BAAI/bge-large-en-v1.5"
texts = json.load(open(src))["texts"]
tok = AutoTokenizer.from_pretrained(name)
model = AutoModel.from_pretrained(name).to(torch.float32).eval()
vectors = []
with torch.no_grad():
    for t in texts:
        h = model(**tok([t], return_tensors="pt", truncation=True, max_length=512)).last_hidden_state[:, 0]
        vectors.append(torch.nn.functional.normalize(h, dim=-1)[0].tolist())
runtime = f"transformers {transformers.__version__}, torch {torch.__version__}, CPU float32, CLS pooling"
json.dump({"model": name, "job": job, "runtime": runtime, "texts": texts, "vectors": vectors}, open(dst, "w"))
print("wrote", len(vectors), "vectors of", len(vectors[0]), "dimensions")
PYEOF
echo "finished $(date)"
