# Running GenSPARQL with open-weight models (vLLM on SLURM)

GenSPARQL's `openai` provider talks to **any OpenAI-compatible endpoint**, so you can
replace the OpenRouter API with open-weight models from Hugging Face served locally by
[vLLM](https://docs.vllm.ai). Both the **chat** model (the `GENOP` operator) and the
**embedding** model (entity grounding, similarity join, `gen:embedding`) are open-weight
and served by the same tool.

```
 GenSPARQL (openai provider)
   ├── chat        →  OPENAI_BASE_URL            →  vLLM  serve_llm.slurm         (e.g. Qwen2.5)
   └── embeddings  →  OPENAI_EMBEDDING_BASE_URL  →  vLLM  serve_embeddings.slurm  (e.g. bge-large)
```

## 1. One-time: create the serving env

```bash
./deploy/setup_vllm_env.sh          # creates conda env "vllm" with vllm installed
```

## 2. Launch the servers (SLURM)

```bash
# Chat model (pick per VRAM; A100 here is 40 GB, A40 is 48 GB, up to 8 GPUs/node → TP up to 8)
MODEL=Qwen/Qwen3-8B                       TP=1 sbatch deploy/serve_llm.slurm  # single GPU, fast iteration
# MODEL=Qwen/Qwen3-30B-A3B-Instruct-2507  TP=2 sbatch deploy/serve_llm.slurm  # fast MoE (3B active), stronger

# Embedding model (small, one GPU)
MODEL=BAAI/bge-large-en-v1.5 sbatch deploy/serve_embeddings.slurm
```

Each job prints, in its `vllm_*.out` log, the exact `export` lines to use — including the
compute-node hostname it landed on.

## 3. Point GenSPARQL at the servers

In the shell where you run the GenSPARQL Java client / eval:

```bash
export OPENAI_API_KEY=dummy                                   # vLLM doesn't check it, but it must be non-empty
export OPENAI_BASE_URL=http://<llm-node>:8000/v1

export OPENAI_EMBEDDING_BASE_URL=http://<emb-node>:8001/v1    # only if embeddings run on a different server
export OPENAI_EMBEDDING_MODEL=BAAI/bge-large-en-v1.5
```

Provider routing (no extra flag needed):
- `GENOP` picks its provider from the model spec — `<model:openai:...>` → the `openai` provider → `OPENAI_BASE_URL`.
- Grounding / similarity-join embeddings use the **default** provider, which is auto-detected
  from `OPENAI_API_KEY` being set → also the `openai` provider → `OPENAI_EMBEDDING_BASE_URL`.
- If you still have `OPENROUTER_API_KEY` exported, `OPENAI_API_KEY` takes precedence, so the
  default stays on the local server. (Unset `OPENROUTER_API_KEY` to be safe.)

And write query model specs against the `openai` provider using the HF repo id as the model:

```sparql
GENOP("List one tool used in {?field}.", (?tool), <model:openai:Qwen/Qwen2.5-7B-Instruct>)
```

## Model choices (see the review analysis)

| Role | Default | Bigger / better | Notes |
|------|---------|-----------------|-------|
| Chat | `Qwen/Qwen3-8B` | `Qwen/Qwen3-30B-A3B-Instruct-2507` (MoE), `Qwen/Qwen3-32B` | Qwen3 = strong JSON/instruction following; well supported by vLLM |
| Newest (check vLLM support) | — | `Qwen/Qwen3.5-*`, `Qwen/Qwen3.6-*` (incl. `-FP8` for 40 GB) | brand-new arch (`Qwen3_5*`); needs a very recent vLLM and may be multimodal — verify before relying on it |
| Reasoning ablation | — | `deepseek-ai/DeepSeek-R1-Distill-Qwen-32B` | compare "reasoning vs direct" |
| Embeddings | `BAAI/bge-large-en-v1.5` | `BAAI/bge-m3` (multilingual), `Alibaba-NLP/gte-large-en-v1.5` | English KG → bge-large is light & fast |

## Notes / gotchas

- **Disk:** the shared home has limited free space; weights are large (7B ≈ 15 GB, 72B fp16
  ≈ 145 GB, 72B-AWQ ≈ 40 GB). `HF_HOME` defaults to `$HOME/hf_cache` — point it at a location
  with room, and prefer AWQ/GPTQ quantized repos for the big models.
- **A100 here is the 40 GB variant.** 72B fp16 needs `TP=4`; the AWQ 4-bit build fits in `TP=2`.
- **Reachability:** the client must be able to reach the compute node's hostname/port
  (run the client from a login node or another SLURM job on the same network).
- **Determinism:** GenSPARQL already sends `temperature=0`; keep it for reproducible eval.
- Embeddings can also be served with `--runner pooling` on the *same* node as the chat model on
  a spare GPU; just give it a different `PORT`.
- On compute nodes without a CUDA compiler (`nvcc`), FlashInfer's runtime JIT fails; the scripts
  set `VLLM_USE_FLASHINFER_SAMPLER=0` to use the native PyTorch sampler instead.
