# Arcana — Eval Methodology (accuracy scoring definitions)

*Week 11. The latency benchmark (Week 4) answers "how fast." This answers "how right." An
ambiguous "accuracy" number is worse than none, so every definition below is written down before
any percentage is reported — you should be able to read this and predict exactly how a given run
was scored.*

This document is the **committed contract** for the two accuracy suites:

- **Cascade accuracy** — how often the capture cascade identifies a box correctly (on-device
  vision + catalog chain). Runs on-device (the cascade *is* on-device). Fixtures: real box photos.
- **Retrieval accuracy** — how often the hybrid retriever answers a collection question correctly
  (router path + structured exact-match + semantic hit). Fixtures: labeled queries + the committed
  504-item CSV.

Results land in `EVAL_RESULTS.md` (Day 4). This file defines *what is measured*; that file records
*what was measured*.

---

## 1. What "correct" means

### 1.1 Cascade (scored per photo)

| Axis | Signal | "Correct" means |
|---|---|---|
| **Pop-number** | `CascadeState.Read.layout.popNumber` | Exact string match to the manifest `popNumber`. Scored **pre-** and **post-**burst-vote so the vote's contribution is a measured delta, not a claim. |
| **Franchise** | settled `entry.name` / layout franchise | Normalized match to manifest `franchise`. **Exact and fuzzy (substring/alias) are reported as separate cells** — never blended into one "franchise accuracy," which would be ambiguous. |
| **Catalog identity** | settled `entry` | Resolves to the **right catalog entry**, not merely the right number: for an **owned** item, `entry.matchedLocalId` maps to the manifest item; for an **unowned** item, `entry.name` + number match the expected identity. |
| **Resolution locus** | `entry.executedOn` / `telemetry.resolvedOn` | Matches the manifest's expected locus (owned → `OnDevice` via `LocalCollectionCatalogProvider`; unowned → `Cloud` via `CloudMultimodalCatalogProvider`). Reported as a **rate**, not pass/fail — locus is an outcome, not an error. |

**Refusal is its own bucket.** An ML Kit / Nano safety refusal returns an **empty, candidate-less
response — not an exception** (the Week-10 finding). The scorer counts `refused` **distinctly from
`wrong`**. Folding refusals into "wrong" would understate identification accuracy and misattribute a
safety event to a model error. Note: the on-device *describe* stage is **off the identification
critical path** (it never feeds `entry`), so a describe refusal does not affect identification
scoring — it is logged separately.

### 1.2 Retrieval (scored per query)

| Axis | Signal | "Correct" means |
|---|---|---|
| **Router path** | `QueryRouter.classify(query)` | The returned `Route` type equals the manifest `expectedRoute`. This is the week's central design and the most defensible number; it is scored in the **reproducible JVM suite** (pure function, no model, no device). |
| **Structured exact-match** | `Grounding.facts` (count/value) | The computed count/total equals the **independently-derived** ground truth (see §5). Exact integer match on the count; total value checked to the dollar. |
| **Semantic hit** | `Grounding.items` (ranked) | **Top-1** (primary headline): the expected item is rank 1. **Top-k** (k=5, secondary): the expected item is in the top 5. Both are reported; top-1 is the honest headline, top-k shows recall. For broad-franchise queries the target is *any* member of that franchise. |

**Misroute surfacing.** Every router miss is listed **individually** in `EVAL_RESULTS.md` (query →
got → expected), not just aggregated. A misroute rate with no examples hides the failure it names.

---

## 2. Fixture formats (committed, inspectable)

Both fixture files are **tab-separated (TSV)** — zero-dependency to parse in pure Kotlin, diff-
friendly, human-inspectable. Cells that hold a set use `|` as the inner separator. Lines beginning
`#` are comments; blank lines are ignored.

### 2.1 Photo manifest — `app/src/androidTest/assets/eval/photo_fixtures.tsv`

Columns: `photo  item  popNumber  franchise  character  series  owned  expectedLocus  failureMode  notes`

- `photo` — asset filename under `androidTest/assets/`. **The photo is gitignored; this label row is
  committed** (the Week-8 pattern: commit the coverage, not the private image).
- `owned` — `yes` / `no` (is this exact item in the 504-item collection?).
- `expectedLocus` — `OnDevice` / `Cloud`.
- `failureMode` — one of `clean`, `glare`, `angle`, `backlight`, `topdown`, `barcode`, `hand`
  (the controlled test axis; a photo may be tagged with its dominant mode).

### 2.2 Query set — `app/src/androidTest/assets/eval/query_fixtures.tsv`

Columns: `query  expectedRoute  answerKind  groundTruth  notes`

- `expectedRoute` — `Count` / `NftRedeemable` / `MostValuable` / `AddedInYear` / `FollowUp` /
  `Semantic`.
- `answerKind` — `structured` / `semantic` / `followup` (which scorer applies).
- `groundTruth` — for `structured`: the expected number, or `TBD-D3` where the exact count is
  **derived independently on Day 3** (§5) rather than guessed now. For `semantic`: the expected
  item name(s), `|`-separated (any-of for broad franchises). For `followup`: empty.

The same query file is read by **both** runners: the instrumented retrieval suite loads it from
packaged assets (`context.assets`); the JVM router-scoring test reads it via the module-relative
path `src/androidTest/assets/eval/query_fixtures.tsv` (unit-test working dir = the `:app` module
root). One committed source of truth, two readers.

---

## 3. Reproducibility — stated plainly

Not everything here reproduces from a clean checkout, and saying which is which is part of the
rigor.

| Suite | Reproducible from a clean `git clone`? | Why |
|---|---|---|
| **Router path** (retrieval) | **Yes — fully.** In the green JVM suite. | Pure `QueryRouter.classify`; committed queries; no model, no device. |
| **Structured exact-match** (retrieval) | **Yes**, for anyone who imports the committed `collectibles_2026-07-03.csv`. | SQL over the 504-item CSV; no model. Needs a device/emulator to run Room, but no private data. |
| **Semantic hit** (retrieval) | **No** — needs the side-loaded EmbeddingGemma model (gated, not in the APK). | Same gate as the Ask feature itself. Skips cleanly (`assumeTrue`) when absent. |
| **Cascade** (identification) | **No** — needs the private box photos (gitignored) + a device. | Same gate as the capture feature itself. The committed manifest documents the labels; the photos are personal. |

So: **the router suite is the reproducible headline; the photo suite is not** — exactly as honest as
the features they measure.

---

## 4. N and error bars (the honesty caveat)

**Below ~30 fixtures, accuracy numbers are noise.** Every reported rate carries its **N** and, where
N is small, an explicit caveat rather than a confident percentage. A "94%" that is 15/16 is a lie of
precision; it is reported as **15/16 (N=16, wide error bars)**.

Per-**cell** N matters too: a per-failure-mode rate computed from < 3 instances of that mode is
labeled **"N too small for this cell"** and not given a percentage.

### Current coverage vs. the gap (photo suite)

The existing committed fixtures are **7 photos of 2 items**, and **both items are unowned**
(`Aang #406` is a stock image; `Freddy Funko as Popeye #32` is not in the collection — the nearest
owned item is `Popeye With Swee'Pea #30`). Consequences, stated up front:

| Coverage axis | Status |
|---|---|
| Failure modes | **Good** — glare / angle / backlight / topdown / barcode / hand all present (on one item). |
| Item breadth | **Thin** — 2 distinct items. Accuracy is near-anecdotal until more boxes are shot. |
| On-device-resolve column | **Empty** — no owned item is photographed, so the owned→`OnDevice` path is **unmeasured** until a collection box is shot. |
| Cloud-escalation column | Present — both current items exercise the unowned→`Cloud` path. |

The fix is more fixtures (target ~10–20 physical boxes, spanning **owned and unowned**, plus the 6
Freddy modes). Until then, numbers are reported with N and this caveat.

---

## 5. Independent ground truth (no circular tests)

Structured ground-truth counts are derived **independently of the code under test.** If the eval
asserted `StructuredRetriever == StructuredRetriever`, it would pass by construction and measure
nothing. Instead, the expected count for each structured query is computed by an **independent
derivation over the committed CSV** (a separate counting pass / hand-verified column tally),
recorded in `query_fixtures.tsv` (or marked `TBD-D3` and filled when that derivation runs on Day 3).
The suite then asserts the retriever's SQL answer matches that independently-derived number.

Known independently today: **504** total items in the committed CSV (verified row count). Subject-
filtered counts (Marvel, Star Wars, horror, …) and the most-valuable identity are derived on Day 3
and pinned then — not guessed now.

---

## 6. Cloud budget (cascade runs)

Unowned fixtures escalate to `CloudMultimodalCatalogProvider` — a real cloud call under the free-tier
**20/min + daily cap**. The cascade runner therefore: (a) uses the manifest `owned` flag to run owned
fixtures **offline/local** (no cloud), and (b) **caps and paces** the unowned cloud calls under the
per-minute limit, running the full cloud pass **once, minimally**. On-device stages (OCR, segment,
burst-vote) have no quota and run freely.
