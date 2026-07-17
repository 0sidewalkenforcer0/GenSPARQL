# Idea 1 — Cost-Aware Planner for Generative SPARQL Operators (research plan)

> Draft plan (2026-07-17). Positioning grounded in a deep-research novelty pass; see
> the landscape/caveats at the bottom. Two intended outputs: **(A) short-term ISWC
> short paper / demo**, **(B) long-term full conference paper** (SIGMOD/VLDB/EDBT/ISWC).

## 0. One-line thesis

GENOP is invisible to the SPARQL optimizer (`OpGenerate.effectiveOp()` returns
`OpTable.unit()`), so context-mode fires one LLM call per binding and blows up
(measured: 2p pattern = 41 calls / 5.2 min). We make generative operators
**first-class, costable, and reorderable** in the SPARQL algebra — the first
cost-aware planner for generative operators on RDF/SPARQL.

## 1. Novelty verdict (from deep-research, 2026-07-17)

- Cost-based optimization of LLM/"semantic" operators is **exploding in 2024–2026 but
  is entirely SQL/relational**: LOTUS (VLDB 2025), Abacus (PVLDB v19 2026), iPDB
  (2026, DuckDB), PLOP (2026), Cortex AISQL (Snowflake 2025). **None target SPARQL/RDF.**
- Everyone **punts on output cardinality / fan-out estimation** for generative
  operators (Abacus names its absence as the core difficulty; classic UDF models
  assume a *fixed known* selectivity — the very assumption generative outputs break).
- Our own foundational semantics paper **On the Semantics of Generative SPARQL**
  (Thapa & Staab, arXiv 2606.23875, 2026) treats each GENOP as an **O(1) oracle with
  no cost model** (§7: "treat each GenOp as an external oracle"), proves the reordering
  legality — **Prop 6** safe join reordering (`X⊆var(P₁)`, `var(P₂)∩X=∅`), **Prop 5**
  FILTER pushdown (`var(F)⊆X`, `Y∩var(P)=∅`, `var(F)⊆var(P)`), **Theorem 6**
  topological-order invariance — and lists "developing optimizer support for generative
  operators within the SPARQL algebra" as **future work** (§8). **No one has followed up.**
  (Note: the deep-research pass mis-numbered these as Prop 17/18 — verified against the
  arXiv HTML on 2026-07-17; real numbers are Prop 5, Prop 6, Theorem 6.)

**Defensible novelty = SPARQL algebra + generative fan-out estimation + binding-aware
batching, inside the Prop-17/18 guardrails.** That intersection is empty in the surveyed
literature.

## 2. Motivating observation — the determinism assumption (NOT a theory contribution)

**The actual error the author confirmed:** the semantics paper assumes GenOp is a
**deterministic oracle — the same prompt always returns the same result.** This is the
hidden premise behind treating GenOp as an O(1) oracle (§7) and behind Theorem 6
(topological-order invariance: "all orders give the same Ω"). Real LLMs are
**non-deterministic** — the repo itself records the same query returning `[]` on one run
and ~427 items on another (ISSUES §9 addendum). So the "same result regardless of order"
guarantee does not hold in practice.

**We deliberately do NOT chase a formal fix** (the project is systems-first, not
theory-first). Instead this becomes a **motivation** that our systems contributions
resolve pragmatically:
- **record-replay** (§4) pins responses so execution is reproducible for measurement;
- **C3 dedup** makes a repeated prompt deterministic *by construction* within a query
  (one call, reused) — i.e. it enforces the very determinism the theory assumed, and its
  "lossless" claim is precisely scoped to "identical prompt ⇒ identical result";
- optional **self-consistency / voting** (a later C-component) can turn a
  non-deterministic oracle into a calibrated, stable one.

Framing for the paper: cite Theorem 6 / the oracle abstraction, note the determinism gap
as the practical reason a naive planner is unsafe, and position our reproducibility +
dedup machinery as what makes cost-based planning *sound in practice*. One or two
sentences, not a theorem. (Verified the real proposition numbers — Prop 5/6, Theorem 6 —
against the arXiv HTML, 2026-07-17.)

## 3. Technical components (shared core)

| # | Component | Where it plugs into the code | Novel? |
|---|-----------|------------------------------|--------|
| **C1** | LLM-operator **cost model** (prompt tokens $ + latency + expected fan-out) | replace `OpGenerate.effectiveOp()` unit-table stub; expose cost to planner in `GenSPARQLQueryEngine.executeOp` | model shape known (SQL side); **SPARQL-specific + fan-out term is new** |
| **C2** | **Fan-out / output-cardinality estimator** for a GENOP before the call | new estimator consuming KG stats (`CandidateExtractor`), prompt features, per-relation priors | ⭐ **central novelty** |
| **C3** | **Binding-aware batching + dedup** for context-mode | extend existing batching in `QueryIterGenerate` (already has `BatchedPromptBuilder`, lines ~202–310) to be plan-/cost-driven and dedup identical prompts across bindings | ⭐ **demoable win** |
| **C4** | **Cost-based reordering** (push cheap BGP filters before GENOP; predicate pull-up) under Prop 17/18 | upgrade the correctness-only reorder in `reorderForDependencies` to a cost-based DP (Chaudhuri-Shim rank ordering) | rediscovered on SQL side; **first on SPARQL** |

**Classic foundations to build on & cite:** Hellerstein & Stonebraker, *Predicate
Migration* (SIGMOD 1993) — predicate pull-up, function caching, rank =
`(selectivity−1)/cost`; Chaudhuri & Shim (VLDB 1996) — DP placement, rank `c/(1−s)`,
two-static-parameter model. **Batching:** Liu et al. (UC Berkeley, arXiv 2403.05821,
2024) — reorder + dedup per-row LLM requests for KV-cache reuse.

### C3 design (from the batching literature pass, 2026-07-17)

Current engine batching (`QueryIterGenerate.hasNextBindingBatched`/`executeBatch`,
~lines 202–310) is **naive**: accumulate a fixed `batchSize`(=5) bindings → one numbered
prompt → one call → map results back **positionally by index**. No dedup, no
cost-driven sizing, no reordering. C3 upgrades it, in priority order:

1. **Cross-binding exact-prompt dedup (do first — lossless, biggest win).** Collapse
   identical resolved prompts to one LLM invocation, fan the result back to every input
   binding that produced it. Adopted from Liu et al. (SQL `DISTINCT` before invoke; 8.2×
   on a low-cardinality column, ~1.1× when near-unique) and iPDB's in-operator KV dedup
   cache (arXiv 2601.16432; mean 2.5×, peak 30×). **Careful:** breaks the current
   positional `batchInputs.size()==prompts.size()` mapping — need a
   distinct-prompt→[input indices] map to re-expand.
2. **Cost-driven batch sizing.** Replace fixed `batchSize` with token-bounded packing
   using the existing `BatchedPromptBuilder.isBatchWithinLimits` / `batchMaxTokens`
   (=8000). Pack until the token budget, not a magic count.
3. **KV-cache-aware binding reordering (optional / backend-gated).** Sort bindings so
   shared prompt prefixes cluster (Liu et al. field score `ASL × rows/cardinality`).
   **Only pays off on a prefix-caching backend (vLLM); no benefit on OpenRouter/deepseek**
   → keep behind a flag, out of the ISWC demo scope.
4. **Semantic (embedding-similarity) caching** (GPTCache; GPT Semantic Cache arXiv
   2411.05276, hit-rate 61–69%, up to 68.8% fewer calls) — a *second* tier above exact
   dedup for near-duplicate bindings. Risk = false-positive reuse; tunable threshold.
   Defer past the demo; it changes results so it must be evaluated for correctness, not
   just cost.
5. **Budget-bounded execution** (FrugalGPT arXiv 2305.05176) — a cost cap driving
   model-cascade / how-many-bindings-get-a-call. Ties into C1/C4; later.

**Gap C3 can claim (confirmed by the pass):** binding-aware batching + cross-binding
prompt dedup + KV-cache-aware reordering + semantic caching + budget-bounded execution
are demonstrated for **relational** LLM operators (Spark/Liu et al., iPDB) and general
LLM serving (Orca OSDI'22, vLLM SOSP'23, Sarathi OSDI'24), but **never for a generative
operator over SPARQL/RDF graph bindings** — where the coalescing unit is a *solution
mapping* (not a table row) and dedup/reorder must respect binding provenance
(RDF-derived vs. generated).

## 4. Experimental design

### Research questions
- **RQ1 (cost/latency):** Does cost-aware planning reduce LLM calls / wall-clock / $
  vs. the current engine on multi-hop patterns, at equal answer quality?
- **RQ2 (fan-out estimation):** How accurate is the fan-out estimator (q-error), and
  how much of RQ1's gain is attributable to it (vs. static defaults)?
- **RQ3 (batching):** How much of the per-binding blow-up does binding-aware batching +
  dedup eliminate (the 41-calls/5.2-min case → ?)?
- **RQ4 (reordering legality):** Do Prop-17/18-guarded reorderings preserve results
  exactly (result-set equality vs. unoptimized baseline) across the workload?

### ⚠️ Controlling LLM non-determinism — record-replay (the foundation of all eval)

The killer methodological problem: the same query returns `[]` on one run and ~427 items
on another (ISSUES §9 addendum). Without control, you cannot attribute a speedup to the
planner vs. the LLM happening to generate less. **Protocol:**
1. **Record pass:** run each workload once, persist every GENOP request→response. The
   engine already has `CachingLLMProvider` + `InMemoryResponseCache` keyed by a
   SHA-256 `CacheKey`(prompt+model+temp+maxTokens+outputVars); persist it to disk to get
   a record-replay layer (or a `MockLLMProvider` seeded from the recording).
2. **Measurement pass:** run ALL plan variants against the SAME recorded responses →
   the only variable is the plan. Fan-out, result sets, call counts become deterministic
   and reproducible.
3. Report **two number sets:** (a) record-replay deterministic numbers (main results,
   ablations, correctness); (b) a smaller live-LLM run with mean±variance (shows it holds
   in the wild). Use `temperature=0` throughout.

**Dedup + call-count accounting (design decision, must state in the paper):** with C3
dedup, N identical-prompt bindings become 1 LLM call. Report BOTH "logical GENOP
invocations" (N) and "actual LLM calls" (1) so the saving is legible and not mistaken for
a correctness change. Correctness is judged on the fanned-out result, which must equal the
un-deduped result exactly.

### Baselines
1. **Current GenSPARQL** (no cost model; per-binding calls) — the honest baseline.
2. **Naive batching** (fixed batch size, no dedup, no cost) — isolate C3's smart part.
3. **Correctness-only reorder** (existing `reorderForDependencies`) — isolate C4.
4. **SQL-lineage ported heuristic** (Chaudhuri-Shim rank with *static* selectivity) —
   shows why fan-out estimation (C2) is needed on top.
5. (Optional, qualitative) LOTUS/PLOP-style framing note — cannot run on SPARQL, cited
   as "SQL-only" to motivate the gap, not as a runnable baseline.

### Metrics
- **Efficiency:** # LLM calls, wall-clock, total tokens / $ (repo already has `metrics/`
  — `LLMCallMetrics`, `QueryExecutionMetrics`).
- **Estimator quality:** q-error / MAPE of predicted vs. actual fan-out per GENOP.
- **Correctness:** result-set equality (Jaccard = 1.0) vs. unoptimized plan → validates
  Prop 17/18 in practice. Answer quality (P/R/F1 / grounding rate) unchanged.
- **Planning overhead:** optimizer time (must stay ≪ LLM time — it will).

### Workloads / datasets
- **Primary:** the bundled scientists KG (`scientists_awards_large.ttl`) — clean labels,
  reproducible, no external key beyond `OPENROUTER_API_KEY`. Build 2-hop / 3-hop /
  intersection query templates that trigger context-mode fan-out.
- **Secondary (scale/stress):** FB15k-237+H — use it **for cost/latency & fan-out**
  (RQ1–RQ3), *not* for accuracy (E3 already documents ~0 accuracy for reasons orthogonal
  to planning). This sidesteps the label/relation blockers while still stressing the planner.
- **Ablations:** C2 on/off (static vs. estimated fan-out), C3 on/off, C4 on/off, and
  full system — 2×2×2 grid + full.

## 5. Output A — ISWC short paper / demo (short term)

**Scope: C3 (+ minimal C1).** Punchy, demoable, self-contained: "binding-aware batching
kills context-mode blow-up." Live demo = run a 2p/3p query with/without the planner and
show 41 calls / 5.2 min → a handful of batched calls / seconds, identical results.
- Contributions: (i) framing GENOP as a costable operator; (ii) binding-aware
  batching + dedup; (iii) the reproducible blow-up→fix measurement.
- Minimal experiments: RQ1 (efficiency) + RQ4 (correctness) on the scientists KG.
- Low risk, fast to write, plants the flag while the full paper matures.

## 6. Output B — full conference paper (long term)

**Scope: C1–C4 + the theory refinement (§2).** The complete cost-aware planner with
**fan-out estimation as the headline contribution**, full ablations (RQ1–RQ4), FB15k
scale study, and the corrected Prop-17 reordering theorem. Position against the SQL
lineage (LOTUS/Abacus/iPDB/PLOP/Cortex) as "all relational; we are the first for the
SPARQL algebra + the first to estimate generative fan-out."

## 7. Risks & mitigations

- **Time-sensitivity (highest):** all competitors are ≤12-month arXiv preprints. → Date
  the novelty claim; **re-run this deep-research pass right before each submission.**
- **"Semantic cardinality estimation" is getting crowded** (SemCEB 2606.23081; SIGMOD
  2026 Tsinghua "Bridging the Gap" doi 10.1145/3802024; Larch 2606.07923) — but all
  relational. → Anchor C2's novelty in **SPARQL structure** (graph schema, per-predicate
  KG stats as priors), not "first semantic cardinality estimator."
- **Batching evidence was thin in the pass** → run a dedicated follow-up search
  (semantic caching, GPTCache, LLM-serving micro-batching) before finalizing C3.
- **Own-group foundation:** low collision risk (no follow-up exists), but coordinate
  with the authors on the theory-refinement framing.

## 8. Immediate next steps

1. Verify the Prop-17 `dom(μ2) ∩ X = ∅` refinement against the semantics paper's proof (§2).
2. Build the context-mode fan-out query templates on the scientists KG; capture the
   baseline blow-up numbers with the existing `metrics/` harness.
3. Prototype C3 (dedup + cost-driven batch sizing) in `QueryIterGenerate` → target the
   ISWC short/demo.
4. Design the fan-out estimator (C2): decide sampling (Abacus-style) vs. learned
   regression (iPDB-style) vs. KG-structural priors — likely a hybrid.

---

### Verified sources (deep-research, 2026-07-17)
LOTUS — PVLDB v18 2025 (arXiv 2407.11418) · Abacus — PVLDB v19 2026 (arXiv 2505.14661) ·
iPDB — arXiv 2601.16432 (2026) · PLOP — arXiv 2604.09944 (2026) · Cortex AISQL —
arXiv 2511.07663 / Snowflake 2025 · GenSPARQL semantics — Thapa & Staab, arXiv 2606.23875
(2026) · Hellerstein & Stonebraker — SIGMOD 1993 · Chaudhuri & Shim — VLDB 1996 ·
Liu et al. batching — arXiv 2403.05821 (2024) · SemCEB — arXiv 2606.23081 · "Bridging the
Gap" cardinality — SIGMOD 2026 (doi 10.1145/3802024) · Larch — arXiv 2606.07923.
