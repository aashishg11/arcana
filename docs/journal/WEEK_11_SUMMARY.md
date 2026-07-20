# Week 11 — Completion Summary (handoff for Week 12)

Status: **complete.** The **accuracy eval harness** is built, measured, and committed — the capstone that
finishes the project's measurement story. Everything before this measured **how fast**; this measures **how
right**. No feature work: the app was already feature-complete (8/8 `ai-samples`) at Week 10.

**The headline:** *Arcana now ships an accuracy eval beside the latency benchmark — labeled fixtures, a
runner, a scorer, a committed results table. Router intent is **34/34** on real queries; structured counts
are **18/18 exact** (independently derived, to the dollar); on-device semantic search is **top-1 46% / top-k
77%** (good recall, weak precision — it breaks on pop-culture indirection); the capture cascade reads a Pop
number **62%** of the time but resolves a full owned identity only **32%**, because reading the number isn't
resolving the identity and Funko's grey-on-grey corner numbers defeat ML Kit OCR outright. Building the harness
caught **three real, user-facing bugs** the unit tests had missed — each fixed with a measured before/after
delta. Every number was pre-registered as a prediction; the predictions were mostly wrong, which was the
point. Verified on the Pixel 10 Pro XL against the real 504-item collection and 30 real box photos.*

---

## 1. What shipped

- **The methodology** — `EVAL_METHODOLOGY.md`: written scoring definitions per axis, the reproducibility
  split (what reproduces from a clean checkout vs. what needs the gated model / private photos), the N /
  error-bar honesty rule, and the **no-circular-ground-truth** rule (ground truth derived independently of the
  code under test).
- **Labeled fixtures + independent ground truth** — `eval/query_fixtures.tsv` (37 queries, both router paths +
  adversarial baits), `eval/photo_fixtures.tsv` (37 photos / 16 items; photos gitignored, labels committed),
  and `tools/eval/derive_ground_truth.py` (a Python CSV parser, separate from the app's importer, that
  regenerates every structured answer).
- **Four scorers:**
  - `RouterAccuracyEvalTest` (JVM, fully reproducible, in the green suite) — router-path correctness.
  - `StructuredRetrievalEvalTest` (on-device, seeds itself from the committed CSV) — count/rank exact-match.
  - `SemanticRetrievalEvalTest` (on-device, live 504-vector index) — top-1 / top-k.
  - `CascadeAccuracyEvalTest` (on-device, real photos) — OCR Pop-number, on-device resolve, cloud resolve,
    per-failure-mode rates.
- **The committed results** — `EVAL_RESULTS.md`: accuracy tables, failure rates, the three-bug tally, and the
  pre-registered prediction scorecard.

## 2. Key decisions (each surfaced, most measured)

- **Photo set = ~10–20 boxes, not 30–50.** ~60% of the collection is digital/NFT with no physical box, so N is
  bounded by physical inventory. Shot 14 items / 30 photos (12 owned + 2 unowned) + the existing 7 → 16 items.
- **Owned run local-only; unowned escalate to cloud, deduped + paced.** Owned photos measure the on-device
  path with zero cloud cost; the 4 distinct unowned items each get **one** paced cloud call (under the 20/min
  free-tier quota). Conserves cloud tokens while still measuring both columns.
- **Independent ground truth, not the retriever's own output.** Structured answers are derived by a separate
  Python parse of the committed CSV; the eval then asserts the app matches — a fidelity check, not a tautology.
- **Router scoring lives in the reproducible JVM green suite** (the strongest, most defensible number);
  structured is reproducible-with-CSV; semantic + cascade need the gated model / private photos — stated
  plainly rather than papered over.
- **Cascade batch runs headless** with *describe* (Nano, foreground-only) **and segmentation** disabled — both
  are off the identification path. Segmentation is disabled specifically because it breaks the ML Kit text
  recognizer process-wide (see §6).
- **Parser fix = edition-exclusion + months-filter + height-ranking.** A position-based ranking was tried and
  **reverted** (it regressed other boxes). The two principled filters are the defensible win.

## 3. The numbers (real, on the Pixel 10 Pro XL)

- **Router:** 35/37 overall; **34/34** non-adversarial (the 2 misses are intentional keyword-router baits).
- **Structured:** **18/18** exact — 504 total, 100 Marvel, 141 NFT, 170/69/0 by year, the $590 most-valuable
  tie. Every value total matched the independent derivation **to the dollar**.
- **Semantic:** top-1 **6/13 (46%)**, top-k **10/13 (77%)** over the live index. Literal descriptors retrieve
  well; epithets (`web slinger`→Spider-Man) and prominence effects (`dragons`→Daemon over Vhagar) break it.
- **Cascade:** OCR Pop-number **23/37 (62%)**; on-device identity resolve **9/28 (32%)**; cloud resolve
  **4/4** (4 cloud calls). Per-mode OCR: clean 69% · angle 80% · topdown 75% · glare 67% · hand 67% ·
  barcode 25% · backlight 0%.

## 4. Prediction scorecard (pre-registered)

Four predictions, all at least partly wrong — each miss taught something.

- "Router ≥ 34/37, misses = exactly the 3 baits" → **34/37, but the misses were 2 baits + 1 real bug**
  (`any others?`); `Blade Runner 2049` passed. Right on the count, wrong on composition.
- "Structured 18/18 exact" → **17/18 pre-fix** — found the `how many items?`→0 bug; 18/18 only after fixing.
- "Semantic top-1 ≥ 9/13, top-k ≥ 11/13" → **6/13, 10/13** — too optimistic about a 300M on-device embedder.
- "Cascade clean-OCR ~85%, all ~55–65%, resolve ~55–65%" → **clean 69%, all 62%, resolve 32%** — all-OCR
  right; missed the corroboration gate and the grey-on-grey OCR limit entirely.

## 5. The honest reframe that shaped the week

**Reading the number is not resolving the identity.** The cascade reads a Pop number 62% of the time but
resolves a full owned identity only 32%, because `LocalCollectionCatalogProvider` gates on the number *plus*
franchise/character corroboration — a degraded shot reads `161` but loses the franchise, so it doesn't clear
the gate. That gap, plus semantic search's weak top-1 and its Week-10 inability to count, is exactly *why both
pipelines are hybrid* — on-device **+** cloud, semantic **+** SQL. The eval didn't just score the app; it
explained why the architecture is shaped the way it is.

## 6. Gotchas worth carrying forward

- **`am instrument`, never `connectedAndroidTest`** for on-device eval — the connected task clean-installs and
  wipes the side-loaded EmbeddingGemma model + own-model. Confirmed the model + the 504-vector index survived
  across the whole session by running via `installDebug + installDebugAndroidTest + am instrument`.
- **ML Kit subject-segmentation breaks the text recognizer process-wide.** The cascade already runs OCR first
  to dodge it within one capture; a 37-photo batch re-exposes it across photos, so the batch runner disables
  segmentation (it's UI-only, off the ID path). Verified byte-identical results with/without → not the cause of
  the OCR misses, but the right thing to disable for a clean batch.
- **`BitmapFactory.decodeStream` ignores EXIF orientation.** Not a problem here (all 30 photos were
  orientation-normal), but the real capture path must apply rotation, or sideways frames will fail OCR.
- **Cloud budget:** dedupe cloud calls per *distinct* unowned item and pace them (4 s apart) — one full cloud
  pass was 4 calls, well under quota.
- **Circular-ground-truth trap:** never score the app against its own output; derive ground truth
  independently (here, a separate Python parse of the CSV).
- **JVM unit tests read `src/androidTest/assets/…` by module-relative path** (working dir = the `:app` module
  root), so the router scorer and the instrumented suites share **one** committed query fixture.

## 7. Files & artifacts

- **New docs:** `EVAL_METHODOLOGY.md`, `EVAL_RESULTS.md`; `tools/eval/derive_ground_truth.py`.
- **New fixtures:** `app/src/androidTest/assets/eval/query_fixtures.tsv`,
  `app/src/androidTest/assets/eval/photo_fixtures.tsv` (+ 30 gitignored box photos in `androidTest/assets/`).
- **New tests:** `RouterAccuracyEvalTest` (JVM); `StructuredRetrievalEvalTest`, `SemanticRetrievalEvalTest`,
  `CascadeAccuracyEvalTest` (instrumented).
- **Bug fixes:** `QueryRouter` (follow-up trailing-punctuation + `COUNT_NOISE` size-words) and `PopNumberParser`
  (edition-exclusion + months-filter) with new `PopNumberParserTest` regressions.
- **README:** an "Accuracy: measured, not asserted" section beside the latency benchmark; status → Week 11.
- **Commits:** `2988bb3` (retrieval eval), `44e5d45` (cascade eval). **JVM suite: 161 tests green.**

## 8. What Week 12 inherits

**The complete measurement story — latency *and* accuracy — the strongest state to enter write-up-and-apply
mode.** What remains is genuinely non-feature:
- **Snapdragon / Hexagon NPU benchmark** — still parked (no device). The README states it as a bounded open
  question with the expected outcome and reasoning, which reads as rigor, not a gap.
- **Write-up & apply** — blog posts, resume bullets, interview prep. The eval results (the failure *rates*, the
  three caught bugs, the honest ceilings) feed all of them directly; this is where the marginal return now
  lives.
- **The design-apply pass** (gated behind this week in `WEEK_11_PLAN.md`): fix the two mock-data
  inconsistencies (item count 512→**504**; Daenerys price reconcile), apply the four screen refinements, ship
  the AA contrast token. An afternoon of UI-apply, not eval-week scope.
- **Polish (low signal):** Room migrations at schema-freeze; multi-module split; Edit Details; capture history.

## 9. Reading order for Week 12

`WEEK_11_SUMMARY.md` (this) → repo `CLAUDE.md` → `EVAL_RESULTS.md` (+ `EVAL_METHODOLOGY.md`) for the accuracy
story → `WEEK_11_PLAN.md` "Pipeline" section for the gated design-apply checklist.
