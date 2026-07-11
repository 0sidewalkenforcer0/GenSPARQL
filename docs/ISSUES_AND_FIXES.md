# GenSPARQL — Code Issues & Fixes (Post‑mortem)

This document records the defects found in the prototype during a debugging pass,
where each one lived, the root cause, the evidence, and the current status. It is
meant to explain *why the benchmark previously reported 0% accuracy* and what was
changed. Issues are ordered by impact.

Legend: **FIXED** = corrected in this repo · **OPEN** = documented, not yet changed.

---

## 1. Benchmark prompt generator produced semantically wrong prompts — **FIXED**

**Where:** `gensparql-benchmark/generate_gensparql_queries_v2.py`, `generate_genop_prompt()`.

**Root cause:** For reverse relations the generator *computed* a natural‑language
relation description (`clean_rel`) and then **threw it away**, hard‑coding:

```python
prompt = f"List movies released on {entity_display} format. ..."
```

So the actual relation semantics were lost and every reverse‑relation branch got the
same template regardless of meaning:

| Query | Relation (truth) | Prompt that was generated | Expected answers |
|-------|------------------|---------------------------|------------------|
| 2i/01 | `film.genre` = Comedy‑GB | "movies released on **DVD** format" | Cars 2, Gosford Park … |
| 2i/10 | `…distribution_medium` = DVD | "movies released on **Comedy‑GB** format" ❌ | Monster House … |
| 2i/11 | `person.place_of_birth` = NYC | "movies released on **New York City** format" ❌ (answers are *people*) | Cynthia Nixon … |

**Evidence:** run log showed the LLM returning generic blockbusters
(`"The Matrix", "Inception", …`) with essentially zero overlap with the expected
Comedy∩DVD movies → 2i/3i **Precision/Recall/F1 = 0.0000**.

**Fix:** the template now uses the computed relation description, e.g.
`"List entities whose '<relation>' is '<entity>'."`, and requests up to 50 answers.

---

## 2. Constrained generation was dead code + "clothing" residue — **FIXED**

**Where:** `generate_gensparql_queries_v2.py`, `generate_constrained_prompt()`.

**Two problems:**
1. The 2i/3i generators called `generate_genop_prompt(...)` **without** passing
   `candidates`, so the constrained‑selection branch was never reached.
2. The function body was hard‑coded to a NELL *clothing* dataset question:
   `"What clothing items typically go well with … {entity}?"` — unusable for FB15k
   (films, people, …).

**Fix:** replaced with a domain‑neutral selection prompt driven by the relation
semantics ("select the entities whose '<relation>' is '<entity>' from this list").
Wiring candidate injection into the generators proper is still **OPEN** (a working
end‑to‑end verification was done via a helper script).

---

## 3. `CanonicalForm.extractLocalName` mangled entity labels — **FIXED**

**Where:** `gensparql-core/.../similarity/CanonicalForm.java`.

**Root cause:** to turn a Freebase URI `m_<mid>_<label>` into a readable label it
scanned for the first `_[A-Z]`, which (a) dropped labels starting with a digit and
(b) never URL‑decoded percent escapes.

| URI local name | Before | After |
|----------------|--------|-------|
| `m_047q2k1_3_Idiots` | `Idiots` (lost "3") | `3 Idiots` |
| `m_01k1k4_Austin_Powers%3A_...` | `Austin Powers%3A ...` | `Austin Powers: ...` |
| `m_..._Julie_%26_Julia` | `Julie %26 Julia` | `Julie & Julia` |
| `m_0cmdwwg_50%2F50` | `m 0cmdwwg 50%2F50` (garbage) | `50/50` |

This corrupted both the sim‑join text comparison and any candidate labels derived
from it, so even a *correct* LLM answer often failed to match its KG entity.

**Fix:** parse `m_<mid>_` then take the whole remainder as the label, and URL‑decode
percent escapes (`cleanLabel()`). Verified against real FB15k URIs.

> Related item (**FIXED**): `CandidateExtractor.extractLabel` now routes through
> `CanonicalForm.canon`, so constrained‑generation candidates and grounding
> label→URI maps use the same canonical labels as the sim‑join
> (e.g. `Cars 2`, `3 Idiots`, `Comedy GB`), with percent escapes decoded.

---

## 4. `ModelSpec.getTemperature()` threw `ClassCastException` — **FIXED**

**Where:** `gensparql-core/.../model/ModelSpec.java`.

**Root cause:** query params are parsed as `Integer` when they have no decimal point
(`temperature=0` → `Integer`), but `getTemperature()` did an unchecked
`(Double) value` cast → `ClassCastException`, killing the LLM call instantly.

**Fix:** numeric getters (`getTemperature/getMaxTokens/getTimeout`) now coerce via
`Number`, tolerating both `Integer` and `Double`. All 9 `ModelSpecTest` tests pass.

---

## 5. Similarity join was an O(n×m) embedding brute force — **FIXED**

**Where:** `gensparql-engine/.../iterator/QueryIterSimJoin` + `EmbeddingSimText`.

The sim‑join compared **every** left (KG) binding against **every** right (generated)
value, and the default `SimText` called the embedding API for each non‑exact string.
On a single 2i pattern this meant ~500 sequential embedding calls and **~230–320s**
wall‑clock (vs. ~3s for the LLM call itself).

**Fix:** `QueryIterSimJoin` now
(a) builds a pre‑computed hash index over the right side keyed by normalized
join‑variable labels and resolves exact matches in O(1) with no similarity
computation (this alone covers constrained generation, where the model copies exact
KG labels);
(b) skips the fuzzy scan entirely when the join variable's threshold is ≥ 1.0
(exact‑only mode); and
(c) when approximate matching is needed with an embedding `SimText`, pre‑warms all
labels via `EmbeddingSimText.warmUp` in a few batched requests instead of ~O(n+m)
sequential ones.
Results are unchanged (every candidate is still verified by the `SimScoreEvaluator`);
a micro‑benchmark confirms exact‑only mode performs only the matching comparisons via
the index while approximate mode still finds fuzzy matches.

---

## 6. Grounding disabled in benchmark runs — **OPEN (config)**

The "open‑world generation → closed‑world validity" story depends on grounding, but
the benchmark `run.sh` did not enable it (`-Dgensparql.grounding.enabled=true`), and
the global embedding index over ~14,505 entities is impractical without the indexing
from issue #5. For 2i patterns the sim‑join already performs the GEN→KG mapping.

---

## 7. Minor correctness / hygiene — **OPEN**

- **JSON parser quirk:** an empty array `[]` is parsed into *one* (empty) binding
  instead of zero (`gensparql-llm/.../parser`).
- **Debug output in hot paths:** ~24 `System.out.println("[DEBUG …]")` remain in
  `src/main` (engine iterators, providers). They belong at `LOG.debug`.
- **Benchmark task suitability:** some relations chosen for the GENOP branch (e.g.
  "release distribution medium = DVD") are near‑universal facts, not discriminative
  world knowledge, which caps precision regardless of method. Prefer relations the
  LLM actually knows (genre, director, cast).

---

## 8. Secret committed to the tree — **FIXED (in this clean repo)**

A live OpenRouter API key was hard‑coded in ~190 run scripts. All
occurrences were replaced with `YOUR_OPENROUTER_API_KEY`; scripts read the
`OPENROUTER_API_KEY` environment variable at runtime. **Rotate that key**, since it
was present in the original history.

---

## What the fixes achieved (pattern 2i/01, single query)

| Configuration | Actual | TP | Precision | Recall | F1 |
|---|---|---|---|---|---|
| Original (broken prompt) | 1 | 0 | 0.00% | 0.00% | 0.00% |
| Fixed prompt (open generation) | 33 | 3 | 9.09% | 7.50% | 8.22% |
| Fixed prompt + candidate‑constrained selection | 427 | 32 | 7.49% | **80.00%** | 13.70% |

The mechanism works (recall 0 → 80%, and every output is a valid KG entity). **But
the 80% row was a hand‑crafted, single‑pattern best case** — a natural‑language
prompt with hand‑typed, clean candidate titles. It is **not reproducible by the
automated query generator**; see the evaluation outcome below.

---

## 9. Evaluation outcome — automated benchmark scores ~0 — **FINDING**

A full post‑fix evaluation was attempted: **both datasets × every pattern × 3 query
instances**, scored with `GenSPARQLExample` (Precision/Recall/F1). All supporting
infrastructure was built and works: the generator emits 3 instances per pattern
with per‑instance `expected{i}.json`; `GenSPARQLExample` accepts an explicit
expected‑answers file; `run_full_sweep.sh` iterates the matrix; and the
intersection generators (2i/3i) were wired to build **candidate‑constrained**
prompts from the KG co‑branch. Despite this, the automated queries score
essentially **0** — via both the open and the constrained path.

**Evidence**
- 2i/pattern_01, open generation: expected 40, actual 33, **TP 0**.
- 2i/pattern_01, constrained: the model returns `[]` (selects nothing) → **0**.
- 3i/pattern_001, constrained: `[]` → **0**.

**Root causes (compounding, several are fundamental)**
1. **FB15k entity labels are unrecoverable from the URIs.** Local names embed
   variable‑length machine IDs that themselves contain underscores
   (`m_07g_0c_The_Weather_Man` → mid `07g_0c`; `m_05_5_22_Get_Him_to_the_Greek` →
   mid `05_5_22`). No underscore rule separates mid from label, so
   canonicalization yields garbage (`"0c The Weather Man"`). This corrupts both the
   candidate lists and the sim‑join keys. (Issue #3 only fixed the clean cases.)
2. **Garbled candidates + technical relation phrasing make the model give up** — it
   returns `[]` when asked to "select entities whose 'release distribution medium'
   is 'DVD'" over names like `"0c The Weather Man"`.
3. **The relations are not LLM world knowledge** ("released on DVD" is
   arbitrary/near‑universal), so even clean labels would not recover the specific
   answers (issue #7).
4. **Path types (2p/3p/4p) are computationally infeasible.** Context‑mode `GENOP`
   fires once per intermediate binding: 2p/pattern_01 issued **41 LLM calls**,
   returned **1252** answers, and took **5.2 min** for one query. A full sweep of
   path types would run for hours.

**Conclusion.** With the *automated* pipeline, GenSPARQL scores ~0 on FB15k‑237+H
and NELL‑995+H. The engine itself is correct — validated on a curated case where a
natural prompt + clean candidates reached ~80% recall — but the benchmark is not
solvable by automated generation as it stands. The blockers are the data encoding
(unrecoverable labels) and the benchmark design (non‑discriminative relations,
path fan‑out), **not** the query engine or the harness.

**What a meaningful evaluation would require**
- An external MID→name mapping to recover clean entity labels (cannot come from the
  URIs alone).
- Natural‑language prompts (not the "whose 'X' is 'Y'" template).
- Selecting relations that are genuinely LLM‑answerable (genre, director, cast — not
  distribution medium).
- Batching / a per‑query call cap to make path types tractable.

### Addendum — clean entity labels obtained, but accuracy unchanged

Root cause #1 (unrecoverable labels) was subsequently **resolved**: KG‑BERT's
`entity2text.txt` (14,950 MID→name pairs) was fetched and `build_entity_labels.py`
produced `entity_labels.tsv` with a clean name for **all 14,505** FB15k entities
(e.g. `m_07g_0c_The_Weather_Man` → "The Weather Man"). This is now wired into both
sides — `GenSPARQLExample` sets `CanonicalForm.setLabelLookup(...)` so the sim‑join
canonicalizes to real names, and the generator uses the same map for clean
candidate lists.

**It did not change the outcome.** With clean candidates *and* a natural‑language
prompt, the model still returns `[]` on 2i/pattern_01. The earlier "80% recall"
run returned ~427 items — the model **dumping almost the entire candidate list**
(recall high, precision ~7%) — and it is **non‑deterministic** (a rerun returns
empty). The relations chosen for the GENOP branch (e.g. "released on DVD") are not
discriminative world knowledge, so the LLM cannot perform meaningful selection: it
either gives up (`[]`) or returns everything. Clean labels were **necessary but not
sufficient**; the benchmark‑design blocker (issue #7) is the binding constraint.
