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

**C3 status (prototyped + verified, 2026-07-17, branch `feat/c3-prompt-dedup`):** exact
cross-binding prompt dedup implemented behind `gensparql.batch.dedup`. On the bundled
scientists KG researchField workload: **46 logical GENOP invocations → 9 actual LLM
calls (80% reduction), result rows unchanged.** Deterministic (mock provider, no key);
wall-clock/$ gains follow since latency is LLM-bound. Token-bounded sizing, KV-reorder,
semantic caching, budget cap remain as follow-ons.

### C2 design (fan-out / output-cardinality estimation — full-paper headline)

**Estimand.** For a GENOP `g = GenOp(π(X),Y,M)` under a context binding μ (X bound),
predict its output row count (fan-out) *before* the call returns; aggregated over the
input, this feeds the cost model (C1), reordering (C4), and dedup/batch sizing (C3).
**Hard because** output size is unknown until the LLM returns and depends on prompt
semantics ("list all…" vs "the capital of…"), the model, and grounding survival.

**Hybrid estimator, three tiers (SPARQL-anchored — the differentiation):**
1. **Static prompt + schema priors (zero-cost).** (a) Prompt-form features on the
   template: list-vs-single intent ("list/all/name five" vs "the/which"), and any
   explicit cardinal ("five physicists" → 5) — a strong direct signal. (b) Output arity
   |Y|. (c) **KG-structural prior:** when Y grounds to a type/relation
   (`grounding_relation`), use per-relation object-cardinality stats from
   `CandidateExtractor` as a prior on plausible fan-out — the signal no relational system
   has.
2. **Grounding-survival factor.** post-grounding fan-out ≈ raw-generation count ×
   survival rate; estimate survival from the grounding threshold θ and KG candidate
   density near the query (ties to the E1/E2 grounding stats already measured).
3. **Online feedback (Abacus-style, cheap).** record true fan-out on the first executed
   bindings and update a per-(template, model) estimate (running mean / Bayesian). The
   LLM calls happen anyway — we only record outcomes (hook sits next to the C3 memo,
   which already counts outputs per prompt).

**★ The C2↔C3 SPARQL insight (concrete, novel, and already empirically visible).** The
dedup ratio = the **value-cardinality of the join/context variable in the KG**, which is
KNOWN from KG statistics *before* running. On the scientists workload we can predict
"46 invocations → ~9 distinct-field calls" purely from `COUNT(DISTINCT ?field)` in the KG
— i.e. C2 estimates *actual LLM calls*, not just raw fan-out, by combining generation
fan-out with the statically-known dedup collapse. No existing (relational) system frames
cardinality estimation this way because row-dedup ratios aren't a schema property; in
SPARQL they are a graph statistic.

**Estimator choice to evaluate (RQ2):** static KG-priors (tier 1) + online feedback
(tier 3) vs. iPDB-style token-only regression vs. Abacus-style pure sampling — hypothesis:
the KG-anchored hybrid wins on SPARQL workloads at lower profiling cost.

**Plugs into code:** new `FanOutEstimator` consuming `OpGenerate` (template/options) +
`CandidateExtractor` (KG stats) + a feedback hook in `QueryIterGenerate`; output feeds
C1's cost model. Ships after C3 (demo) as the full-paper core.

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

### Correctness — two distinct notions (do not conflate)

| | What it asks | Whose job | Compared against |
|---|---|---|---|
| **① Planner correctness (preservation)** | optimized plan yields the SAME result as the unoptimized plan | **our contribution** | plan vs plan |
| **② Answer quality (accuracy)** | the LLM answer matches the dataset gold | LLM / grounding, NOT the optimizer | answer vs gold |

The "dataset gold ≠ LLM answer, both valid" problem is axis ②, not ①. **Proving the
planner correct never requires knowing the true answer** — it is a plan-vs-plan
differential judgement. Axis ② is held *fixed* (equal quality), not improved, so it
cannot confound the efficiency results.

**Proving ① (this validates Prop 5/6 / Theorem 6 empirically) — under record-replay:**
- **E4a — preservation differential test.** For each optimization (C4 reorder, C3 batch,
  C3 dedup), assert result-set equality (Jaccard = 1.0) vs. the baseline plan across the
  whole workload. Metric: % queries at Jaccard 1.0 (target 100%). Any miss is not "the
  LLM was wrong" — it is an *unsound rewrite*, which is exactly how we surface Prop-condition
  boundaries (e.g. the OPTIONAL case).
- **E4b — metamorphic test.** Feed several semantics-equivalent plans / topological orders
  (allowed by Theorem 6) for the same query; outputs must pairwise match. Tests legality
  coverage; no gold needed.
- **E4c — dedup determinism boundary.** C3 dedup is lossless only under
  identical-prompt⇒identical-answer. Under a *live* non-deterministic LLM, un-deduped and
  deduped runs can legitimately diverge (two different-but-valid answers to the same
  prompt). Measure this divergence rate live and show it collapses to 0 under
  `temperature=0` / record-replay. This turns the semantics paper's determinism assumption
  into a measured curve, and frames dedup as *explicitly determinizing* the operator (a
  reproducibility contribution, ties to E5) rather than a hidden assumption.

**Holding ② fixed:** report P/R/F1 / grounding rate identical between baseline and
optimized (trivially equal under record-replay; within-CI live). Any accuracy-vs-gold
reporting (optional, not the main claim) uses metrics that accept "different but correct":
grounding/validity (maps to a real KG entity — GenSPARQL's E1 strength), LLM-judge
equivalence to gold, and separating closed-answer (exact-match OK) from open-ended tasks.

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

## 8. Progress & next steps

**Done (2026-07-17):**
- ✅ Theory error identified (author-confirmed): the semantics paper assumes a
  *deterministic* oracle; reframed as a systems-level motivation, not a theorem (§2).
- ✅ C3 exact prompt-dedup prototyped, tested, committed (`feat/c3-prompt-dedup`).
- ✅ Real-workload number: scientists KG researchField, **46 → 9 LLM calls (80%),
  lossless** (mock/deterministic).
- ✅ C2 fan-out estimator designed + implemented (`FanOutEstimator`, `KgStats`); KG predicts
  N=46→D=9 before any call (the C2↔C3 dedup-ratio insight, empirically verified).
- ✅ C1 cost model implemented (`GenOpCostModel`): calls/tokens/$/latency/rows.
- ✅ C4 cost-based planner implemented (`GenOpPlanner`): Held-Karp subset DP, pulls
  selective KG patterns before an expensive GENOP (26→5 calls in the unit test), respects
  Prop-6 dependency legality; pure-join reordering sound per Prop 6 / Theorem 6.

- ✅ Planner WIRED into the engine: `GenSPARQLQueryEngine` uses `GenOpPlanner` to reorder
  context-mode GENOP sequences when `gensparql.planner.costBased` is on (default off), with a
  safe fallback to the correctness-only heuristic. End-to-end result-preservation verified
  (cost-based on/off identical; +dedup identical).

- ✅ Real KG statistics fed into the wired planner: `reorderByCost` now uses `OpStats`
  (triple extraction + SPARQL serialization) + `KgStats` to compute the real binding count N
  (carried on the first BGP) and each GENOP's dedup ratio D/N, replacing the 1.0 placeholders.
  Fallback-safe; `OpStatsTest` proves the built pattern is countable (N=3, D=2, ratio 2/3).
  (Join cardinality across *multiple* BGPs is still approximated — the first BGP carries N;
  precise multi-BGP selectivity ordering is the remaining refinement.)

- ✅ Self-review pass (10 finder angles + verify): fixed 5 correctness issues (multi-var
  empty-field row drop on the default path; constrained-prompt outside try/catch; dedup
  memoizing failed calls; record-replay storing failures; record-replay empty-file crash)
  + 3 quality/robustness (FanOutEstimator multi-line & incidental-number priority;
  dedupRatio guard for chained GENOPs; AtomicInteger counters). Regression tests added.

**Next:**
1. **ISWC demo:** ✅ live wall-clock obtained (`LiveDedupOpenRouterTest`, gated on
   `OPENROUTER_API_KEY`, model `openai/gpt-oss-20b:free`): 6 bindings / 2 distinct fields →
   **dedup OFF: 6 calls, 6 rows, 43.3 s; dedup ON: 2 calls, 6 rows, 8.0 s** (~5.4× faster,
   3× fewer calls, same rows). Free-tier smoke, not the formal benchmark. Still TODO:
   token-bounded batch sizing; a fuller live run (needs paid/higher-limit model to avoid
   free-tier 429s); write the short paper around 46→9 + these live numbers + the framing.
2. **C2 tier 2:** ✅ grounding-survival factor done (`FanOutEstimator.survivalPrior(θ)` =
   1−0.8θ, anchored on E1's ~0.32 at θ=0.85; `effectiveFanOut` wired into `reorderByCost`
   when grounding is on). Still pending: grounding-relation KG prior, and runtime online
   calibration of the survival rate (feed per-execution grounded/generated counts back).
3. **Multi-BGP selectivity:** per-pattern join-cardinality estimation so the planner can
   also reorder among KG patterns (not just BGP-before-GENOP).
4. **Deferred cleanup (from review, non-blocking):** ~~cache `KgStats` COUNTs~~ ✅ DONE;
   ~~memoize grounded nodes (dedup+grounding grounds D times not N)~~ ✅ DONE (per-value
   `groundedNodeCache` in `createBinding` + `getGroundingCacheHits()`; also removed the
   `[DEBUG GROUNDING]` prints); ~~dedupRatioFor↔KgStats.dedupRatio divergence~~ ✅ DONE
   (single `KgStats.ratio(distinct,total)`); still pending (low value / cross-module) —
   apply dedup on the batched path (or keep them exclusive); the estimateTokens↔
   BatchedPromptBuilder chars/4 dup (different modules, no shared home without a core util)
   and the findGenerate/collectTriples traversal duplication (a generic Op visitor would be
   a larger, riskier refactor for little gain).
5. Re-run the novelty deep-research before submission (time-sensitivity risk).

---

### Verified sources (deep-research, 2026-07-17)
LOTUS — PVLDB v18 2025 (arXiv 2407.11418) · Abacus — PVLDB v19 2026 (arXiv 2505.14661) ·
iPDB — arXiv 2601.16432 (2026) · PLOP — arXiv 2604.09944 (2026) · Cortex AISQL —
arXiv 2511.07663 / Snowflake 2025 · GenSPARQL semantics — Thapa & Staab, arXiv 2606.23875
(2026) · Hellerstein & Stonebraker — SIGMOD 1993 · Chaudhuri & Shim — VLDB 1996 ·
Liu et al. batching — arXiv 2403.05821 (2024) · SemCEB — arXiv 2606.23081 · "Bridging the
Gap" cardinality — SIGMOD 2026 (doi 10.1145/3802024) · Larch — arXiv 2606.07923.
