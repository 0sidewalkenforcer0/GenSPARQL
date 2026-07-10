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

> Related **OPEN** item: `CandidateExtractor.extractLabel` still returns the raw
> local name and does not reuse `CanonicalForm`, so candidate labels and sim‑join
> labels can disagree. Align them before relying on constrained generation.

---

## 4. `ModelSpec.getTemperature()` threw `ClassCastException` — **FIXED**

**Where:** `gensparql-core/.../model/ModelSpec.java`.

**Root cause:** query params are parsed as `Integer` when they have no decimal point
(`temperature=0` → `Integer`), but `getTemperature()` did an unchecked
`(Double) value` cast → `ClassCastException`, killing the LLM call instantly.

**Fix:** numeric getters (`getTemperature/getMaxTokens/getTimeout`) now coerce via
`Number`, tolerating both `Integer` and `Double`. All 9 `ModelSpecTest` tests pass.

---

## 5. Similarity join is an O(n×m) embedding brute force — **OPEN**

**Where:** `gensparql-engine/.../GenSPARQLQueryEngine.executeSimJoinForSequence` +
`QueryIterSimJoin` + `EmbeddingSimText`.

The sim‑join compares **every** left (KG) binding against **every** right (generated)
value; the default `SimText` calls the embedding API for each non‑exact string.
On a single 2i pattern this meant ~500 sequential embedding calls and **~230–320s**
wall‑clock (vs. ~3s for the LLM call itself).

**Recommended fix:** exact‑match first, then a pre‑built entity embedding index with
approximate nearest‑neighbour lookup and batched embeddings.

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

A live OpenRouter API key (`sk-or-v1-…`) was hard‑coded in ~190 run scripts. All
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

The mechanism works (recall 0 → 80%, and every output is a valid KG entity); the
remaining low precision is a property of the chosen benchmark relation (issue #7),
not of the engine.
