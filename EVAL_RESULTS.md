# Arcana — Eval Results (accuracy)

*Week 11. The accuracy counterpart to the latency benchmark in the README. Scoring definitions live in
[EVAL_METHODOLOGY.md](EVAL_METHODOLOGY.md); this file records what was measured. All retrieval numbers below
are reproducible (router + structured from a clean checkout; semantic needs the side-loaded EmbeddingGemma
model). Measured on the Pixel 10 Pro XL (`57130DLCQ000ZJ`).*

**Status:** Retrieval suites complete. Cascade suite pending a labeled photo set (see §6).

---

## 1. Headline

| Suite | Metric | Result | N |
|---|---|---|---|
| Router path | classify == intent | **35/37 (95%)**; non-adversarial **34/34 (100%)** | 37 |
| Structured retrieval | count/rank exact-match | **18/18 (100%)** | 18 |
| Semantic retrieval | **top-1** | **6/13 (46%)** | 13 |
| Semantic retrieval | **top-k (k=5)** | **10/13 (77%)** | 13 |
| **Bugs found + fixed by the harness** | | **2** | |

The one-line story: **structured retrieval is exact, router intent is near-perfect on real queries, and
on-device semantic search has good recall but weak top-1 precision** — it breaks on pop-culture indirection.
The harness also caught two real, user-facing bugs while being built.

---

## 2. Router-path accuracy — 35/37

Pure `QueryRouter.classify` over the labeled query set; fully reproducible in the JVM suite
(`RouterAccuracyEvalTest`). The 2 remaining misses are **intentional adversarial baits** that document the
keyword router's limits; every non-adversarial query routes correctly.

| Subset | Result |
|---|---|
| All queries | 35/37 (95%) |
| Non-adversarial (real user queries) | **34/34 (100%)** |
| Adversarial baits (expected to stress the router) | 1/3 handled (`Blade Runner 2049` ✓; `most valuable lesson` and `how many days until christmas` misroute, as designed) |

**Misroute rate:** 0% on real queries; the only misroutes are the deliberately hostile baits.

## 3. Structured retrieval accuracy — 18/18

`HybridCollectionRetriever` → router → `StructuredRetriever` over the committed 504-item CSV imported into a
fresh DB (`StructuredRetrievalEvalTest`). Reproducible; no model needed. Ground truth derived **independently**
of the retriever by `tools/eval/derive_ground_truth.py`.

| Query class | Ground truth | App | ✓ |
|---|---|---|---|
| Whole collection | 504 | 504 | ✓ |
| Marvel / Star Wars / GoT / Avatar / horror | 100 / 39 / 5 / 6 / 5 | same | ✓ |
| NFT redeemable | 141 | 141 | ✓ |
| Added 2023 / 2024 / 2022 | 170 / 69 / **0** | same | ✓ |
| Most valuable | Aang / Daenerys ($590 tie) | Aang | ✓ |

**Value cross-check:** every computed total matched the independent derivation **to the dollar** (Marvel
$2,435, NFT $15,083, Star Wars $972, GoT $1,000, horror $945, Avatar $1,080, whole-collection $30,115) — so
the value math is validated even though only counts were asserted.

## 4. Semantic retrieval accuracy — top-1 46% / top-k 77%

Real fuzzy queries through the live 504-vector on-device index (`SemanticRetrievalEvalTest`, EmbeddingGemma-
300M). A hit = a ground-truth token in the retrieved item's name or series.

| Query | #1 result | top-1 | in top-5 |
|---|---|---|---|
| spooky horror characters | Pennywise With Spider Legs | ✓ | ✓ |
| norse god of thunder | Thor (Holiday) | ✓ | ✓ |
| the dark knight | White Knight Batman | ✓ | ✓ |
| avatar the last airbender | Fire Nation Aang | ✓ | ✓ |
| marvel superheroes | Captain Marvel - GITD | ✓ | ✓ |
| star wars characters | Luke Skywalker (Hoth) | ✓ | ✓ |
| pops with dragons | Daemon Targaryen | ✗ | ✓ (Vhagar lower) |
| the mother of dragons | Daemon Targaryen | ✗ | ✓ (Daenerys lower) |
| killer clown | Killers Eddie | ✗ | ✓ (Pennywise lower) |
| sentient tree creature | The Lumberjack | ✗ | ✓ (Groot lower) |
| haunted doll | Pennywise With Spider Legs | ✗ | ✗ (Annabelle absent) |
| web slinger | Pennywise With Spider Legs | ✗ | ✗ (Spider-Man absent) |
| friendly neighborhood hero | Horatio J. Hoodoo | ✗ | ✗ (Spider-Man absent) |

**The pattern (this is the finding, not a defect):** literal descriptors and franchise words retrieve well;
**pop-culture indirection breaks it.** Three classes of failure:
1. **Epithet resolution** — `web slinger` / `friendly neighborhood hero` → Spider-Man: full misses. A 300M
   on-device embedder doesn't map nicknames to characters.
2. **Prominence over precision** — for `dragons` and `mother of dragons`, the more prominent Targaryen
   (Daemon) outranks the correct answer (Vhagar / Daenerys); right item present, wrong rank.
3. **Literal-token distraction** — `killer clown` → "Killers Eddie", `sentient tree creature` → "The
   Lumberjack": a surface word beats the semantic match.

This is the honest boundary of on-device RAG on this hardware, and it pairs with the Week-10 result that
semantic search **can't count** — together they're why the retriever is **hybrid**, not semantic-only.

---

## 5. Two bugs the harness caught (with before/after deltas)

The eval's real purpose, demonstrated: building it surfaced two genuine, user-facing defects, each fixed with
a measured delta.

| # | Bug | Symptom | Fix | Delta |
|---|---|---|---|---|
| 1 | `any others?` misroutes | A follow-up phrase the router's own regex *intends* to catch fell through to semantic — `.matches()` full-match vs. a trailing `?` | tolerate trailing punctuation in `isFollowUp` | router non-adversarial **33/34 → 34/34** |
| 2 | `how many items in my collection` → **0** | Whole-collection size question returned 0: `COUNT_NOISE` stripped `pops/funkos/figures` but not `items`, so the subject never collapsed to the empty (whole-collection) case | extend `COUNT_NOISE` (`items`/`things`/bare `are`/`is`) | structured **17/18 → 18/18** |

Both were invisible to the existing unit tests and to manual use; only scoring the labeled set against
independent ground truth exposed them.

## 6. Failure rates (anecdotes → rates)

| Failure mode | Rate | Notes |
|---|---|---|
| Router misroute (real queries) | **0%** (0/34) | after the `any others?` fix |
| Structured count error | **0%** (0/18) | after the `how many items` fix |
| Semantic top-1 miss | **54%** (7/13) | right item often present but not #1 |
| Semantic full miss (not in top-5) | **23%** (3/13) | all three are epithet/indirection queries |
| Semantic epithet-resolution failure | **3/3** | `web slinger`, `friendly neighborhood`, `haunted doll` |

---

## 7. Pre-registered prediction scorecard (honest grading)

The five-week methodology: predict before measuring, then grade. The misses were the value again.

| Prediction | Actual | Verdict |
|---|---|---|
| Router ≥ 34/37; misses = **exactly the 3 adversarial baits** | 34/37 pre-fix; misses = **2 baits + 1 real bug** (`any others?`); `Blade Runner` passed | **Right on the count, wrong on composition** — missed a real bug, over-predicted a bait failure |
| Structured **18/18** exact | **17/18** pre-fix — found the `how many items` → 0 bug | **Wrong** — didn't foresee the bug; 18/18 only after fixing it |
| Semantic **top-1 ≥ 9/13, top-k ≥ 11/13** | **top-1 6/13, top-k 10/13** | **Wrong, too optimistic on both** — I overestimated a 300M on-device embedder; the epithet failures I did anticipate |

Three predictions, three at least partly wrong — and each miss taught something (two bugs + the real ceiling
of on-device semantic search). That's the point of pre-registering.

---

## 8. Cascade accuracy — PENDING

The cascade identification suite needs a labeled photo set (target ~10–20 physical boxes, spanning owned +
unowned and the failure modes). The current committed fixtures are **7 photos / 2 items, both unowned**, so
only the cloud-escalation path is exercisable today (see [EVAL_METHODOLOGY.md](EVAL_METHODOLOGY.md) §4). The
runner + scoring definitions are specified; the numbers land once the photos are shot. Priority when shooting:
a few **owned** boxes, to measure the on-device-resolve path that is currently unmeasured.
