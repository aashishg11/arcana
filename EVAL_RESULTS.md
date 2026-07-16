# Arcana — Eval Results (accuracy)

*Week 11. The accuracy counterpart to the latency benchmark in the README. Scoring definitions live in
[EVAL_METHODOLOGY.md](EVAL_METHODOLOGY.md); this file records what was measured. All retrieval numbers below
are reproducible (router + structured from a clean checkout; semantic needs the side-loaded EmbeddingGemma
model). Measured on the Pixel 10 Pro XL (`57130DLCQ000ZJ`).*

**Status:** Complete — retrieval and cascade suites both measured.

---

## 1. Headline

| Suite | Metric | Result | N |
|---|---|---|---|
| Router path | classify == intent | **35/37 (95%)**; non-adversarial **34/34 (100%)** | 37 |
| Structured retrieval | count/rank exact-match | **18/18 (100%)** | 18 |
| Semantic retrieval | **top-1** | **6/13 (46%)** | 13 |
| Semantic retrieval | **top-k (k=5)** | **10/13 (77%)** | 13 |
| Cascade — OCR Pop-number | exact | **23/37 (62%)** | 37 |
| Cascade — on-device identity resolve (owned) | correct + on-device | **9/28 (32%)** | 28 |
| Cascade — cloud identity resolve (unowned) | correct via cloud | **4/4 (100%)** | 4 |
| **Bugs found + fixed by the harness** | | **3** | |

The one-line story: **structured retrieval is exact, router intent is near-perfect on real queries,
on-device semantic search has good recall but weak top-1 precision, and the on-device cascade reads the Pop
number ~62% of the time but resolves a full owned identity only ~32%** — the gap and the failures are all
measured, not guessed. The harness also caught three real, user-facing bugs while being built.

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

## 5. Three bugs the harness caught (with before/after deltas)

The eval's real purpose, demonstrated: building it surfaced three genuine, user-facing defects, each fixed
with a measured delta.

| # | Bug | Symptom | Fix | Delta |
|---|---|---|---|---|
| 1 | `any others?` misroutes | A follow-up phrase the router's own regex *intends* to catch fell through to semantic — `.matches()` full-match vs. a trailing `?` | tolerate trailing punctuation in `isFollowUp` | router non-adversarial **33/34 → 34/34** |
| 2 | `how many items in my collection` → **0** | Whole-collection size question returned 0: `COUNT_NOISE` stripped `pops/funkos/figures` but not `items`, so the subject never collapsed to the empty (whole-collection) case | extend `COUNT_NOISE` (`items`/`things`/bare `are`/`is`) | structured **17/18 → 18/18** |
| 3 | `BoxLayoutParser` picks the wrong number | On standard boxes the Pop number is small in a corner; the "largest number" rule let an **edition size** (`2300 PCS`) or an **age warning** (`36 meses`) win — a wrong on-device ID | exclude the edition size (nearest `PCS`) + filter Spanish/Italian "months"; keep height-ranking | cascade OCR **21/37 → 23/37** |

All three were invisible to the existing unit tests and to manual use; only scoring the labeled set against
independent ground truth exposed them. (For bug 3, a position-based ranking was also tried and **reverted** —
it regressed `toucan125`→`0` and `freddy161_angle`→`2300`; the two principled filters plus height-ranking won.)

## 6. Failure rates (anecdotes → rates)

| Failure mode | Rate | Notes |
|---|---|---|
| Router misroute (real queries) | **0%** (0/34) | after the `any others?` fix |
| Structured count error | **0%** (0/18) | after the `how many items` fix |
| Semantic top-1 miss | **54%** (7/13) | right item often present but not #1 |
| Semantic full miss (not in top-5) | **23%** (3/13) | all three are epithet/indirection queries |
| Semantic epithet-resolution failure | **3/3** | `web slinger`, `friendly neighborhood`, `haunted doll` |
| Cascade OCR miss (all) | **38%** (14/37) | low-contrast corner numbers, backlit, back-of-box, clutter |
| Cascade OCR by mode | clean 69% · angle 80% · topdown 75% · glare 67% · hand 67% · **barcode 25%** · **backlight 0%** | per-mode rates from the 3 multi-angle sets |
| Cascade grey-on-grey number | **0/7** on Cap #817 | ML Kit never reads the grey number in Funko's dark corner circle |

---

## 7. Pre-registered prediction scorecard (honest grading)

The five-week methodology: predict before measuring, then grade. The misses were the value again.

| Prediction | Actual | Verdict |
|---|---|---|
| Router ≥ 34/37; misses = **exactly the 3 adversarial baits** | 34/37 pre-fix; misses = **2 baits + 1 real bug** (`any others?`); `Blade Runner` passed | **Right on the count, wrong on composition** — missed a real bug, over-predicted a bait failure |
| Structured **18/18** exact | **17/18** pre-fix — found the `how many items` → 0 bug | **Wrong** — didn't foresee the bug; 18/18 only after fixing it |
| Semantic **top-1 ≥ 9/13, top-k ≥ 11/13** | **top-1 6/13, top-k 10/13** | **Wrong, too optimistic on both** — I overestimated a 300M on-device embedder; the epithet failures I did anticipate |
| Cascade clean-OCR ~85%, all-OCR ~55–65%, on-device resolve ~55–65% | clean **69%**, all **62%**, resolve **32%** | **All-OCR right; clean-OCR and resolve too optimistic** — I didn't foresee the corroboration gate (number read ≠ identity resolved) or that Funko's grey corner numbers defeat ML Kit outright |

Four predictions, four at least partly wrong — and each miss taught something (three bugs, the on-device
semantic ceiling, and the OCR-vs-resolve gap). That's the point of pre-registering.

---

## 8. Cascade accuracy — 37 photos / 16 items

The full [`CaptureCascade`](app/src/main/java/com/aashishgodambe/arcana/core/ai/cascade/CaptureCascade.kt)
(real ML Kit OCR + catalog chain) over the labeled photo fixtures (`CascadeAccuracyEvalTest`). **Not
reproducible from a clean checkout** — needs the private box photos (gitignored); the committed manifest holds
the labels. Segmentation and Nano *describe* are disabled (both off the identification path); owned photos run
local-only, the 4 distinct unowned items each get one paced, deduped cloud call.

**Fixture set:** 16 items — 14 owned + 2 unowned (Baby Freddy #164, Snoopy #1553) — across 37 photos, with 3
multi-angle sequences (Freddy #161, Halloween Wanda #715, Captain America #817) spanning the failure modes.

| Metric | Result |
|---|---|
| OCR Pop-number (all) | **23/37 (62%)** |
| OCR Pop-number (clean shots) | 11/16 (69%) |
| **On-device identity resolve (owned)** | **9/28 (32%)** |
| Cloud identity resolve (unowned) | **4/4 (100%)** (4 cloud calls) |
| Per-mode OCR | clean 69% · angle 80% · topdown 75% · glare 67% · hand 67% · barcode 25% · backlight 0% |

### The finding: reading the number ≠ resolving the identity

OCR reads the Pop number **62%** of the time, but a full owned identity resolves only **32%**. The gap is the
**confidence gate**: `LocalCollectionCatalogProvider` matches on the Pop number *plus* corroborating
franchise/character, so a degraded shot that reads `161` but loses the franchise text doesn't clear the gate —
number right, identity unconfirmed. This is the honest on-device ceiling, and it's *why* the cascade escalates
to cloud.

### What genuinely limits it (not fixable in the parser)

- **Grey-on-grey corner numbers — Cap #817, 0/7**: ML Kit never detected the number on *any* of 7 angles; it's
  low-contrast grey text in a dark circle. A hard model/contrast limit.
- **Backlight 0%, back-of-box (barcode) 25%**: expected physics — a backlit or reverse shot has no legible
  front number.
- **Background clutter**: `sleestak132` → `69` (a number on a box *behind* it), `wanda715_glare` → `2` (a noise
  glyph). No clean signal separates these from the real number.

**Ruled out — low light.** The failures do **not** correlate with darkness. Measuring mean luminance (0–255)
of all 30 photos, the failing shots are actually *brighter* on average than the passing ones (**126 vs 109**):
the darkest photos (the Freddy #161 group, ~85–92) all passed, while the brightest (`cap817_clean2` 151, the
backlit Wanda 143) failed. The limits are **local contrast** (grey-on-grey numbers), **geometry** (back-of-box,
backlit), and **clutter** — not exposure. Re-shooting brighter wouldn't move the numbers.

### Method note (honest debugging)

The first run showed several clean shots reading nothing, clustered in the later photos. I chased two wrong
hypotheses — **segmentation corrupting OCR process-wide** (ruled out: a no-op segmenter gave byte-identical
results) and **EXIF rotation** (ruled out: all photos are orientation-normal) — before a zero-cloud raw-OCR
probe correctly split the 16 failures into **12 genuine OCR limits + 4 parser attribution bugs**. Fixing the
2 cleanly-attributable parser bugs (edition size, months warning) gave the 21→23 delta in §5; the other
2 (background clutter) and all 12 OCR limits are reported straight.
