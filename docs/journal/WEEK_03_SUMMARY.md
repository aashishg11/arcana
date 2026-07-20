# Week 3 — Completion Summary (handoff for Week 4 planning)

Status: **complete and shipped** (branch `main`).
Week-3 commits (on `main`, newest first):
- `526a92f` — Wire weekly price sync, snapshot, and market section (Day 5)
- `3aa8352` — Ship ML Kit GenAI Summarization on-device with Gemini fallback (Day 4)
- `ca5b4d1` — On-device weekly summary card (Day 4, Gemini path)
- `b05e827` — Price provider seam + live value tracking (Days 1–3)
- `ca99538` — Drop Co-Authored-By trailer from commit convention

All JVM unit tests green; every screen verified on a physical **Pixel 10 Pro XL** (Tensor G5). This document states what exists, what's stubbed, the verified facts, and the Week-4 entry points — so Week 4 can be scoped without re-reading the tree.

---

## 1. What Week 3 delivered (vs. the plan)

`WEEK_03_PLAN.md` scoped Week 3 as: light up the dead value-tracking UI behind a `PriceProvider` seam + mock, write an ongoing `ValueSnapshot` series, and ship the on-device **`genai-summarization`** card. **All of that shipped, plus the real ML Kit capability (not just a Gemini stand-in):**

- **`PriceProvider` seam** — category-routed chain behind a mock, per `DESIGN.md`.
- **Ongoing `ValueSnapshot` time-series** + an idempotent **backdated 12-week seed** so charts show a curve and the summary has deltas on first open.
- **Portfolio sparkline + Detail 90-day chart** rendering from real snapshots, with an interactive **30/60/90-day range selector** and honest thin-data states.
- **The `genai-summarization` card, on-device** — shipped via **ML Kit GenAI Summarization** (the 8th `ai-samples` capability) behind a `CollectionSummarizer` seam, with a **Gemini-Nano fallback**. Input is the user's own per-list weekly deltas — never eBay-sourced.
- **`WeeklyPriceSyncWorker`** (WorkManager `HiltWorker`) + **"Sync now"** + **"Snapshot today's price"** (all previously no-ops) now functional.
- **`MarketSection`** (shared component) — median-active + top listings from the mock, labeled "median active," with an untracked **"Buy on eBay"** search link.
- **Unified current-value model** — headline, per-list rollup, item rows, and Detail all read `COALESCE(lastKnownValue, estimated) × quantity`, so the numbers agree and move together with sync.

---

## 2. Architecture added

New seams and packages (single `:app` module, package-by-feature):

```
core/ai/
  pricing/     PriceProvider, PriceProviderChain (category-routed), MockPriceProvider,
               MockPriceModel (deterministic drift), EbaySearch, di/PricingModule
  summary/     CollectionSummarizer (+ GeminiCollectionSummarizer, MlKitCollectionSummarizer,
               FallbackCollectionSummarizer), di/SummaryModule
  model/       PriceResult, MarketContext (+ ActiveListing, SoldListing), PriceConfidence
core/data/
  worker/      WeeklyPriceSyncWorker (@HiltWorker)
core/domain/
  model/       ValueSnapshot, PortfolioPoint, WeeklyDeltas (+ ListDelta), currentValueCents ext
  usecase/     SnapshotItemPrice (debounced), SyncAllPrices, SeedMockHistory, ComputeWeeklyDeltas
ui/component/  ValueSparkline, RangeSelector (+ ChartRange), MarketSection
```

**Layering discipline held:** `core/data` never imports `core/ai` (the mock-drift seeding lives in a domain use case, not the repository), so the future `:core:*` module split stays acyclic.

---

## 3. The AI layer (Week-3 centerpiece: `genai-summarization`)

**`CollectionSummarizer.summarize(WeeklyDeltas): Flow<InferenceResult>`** — the seam. Same `InferenceResult` stream as "Ask Arcana," so the card streams token-by-token and shows the on-device/cloud badge, engine-agnostic.

- **`MlKitCollectionSummarizer`** — ML Kit GenAI Summarization (`com.google.mlkit:genai-summarization:1.0.0-beta1`). Renders the structured deltas into a prose "article," then `THREE_BULLETS`. **Runs on-device (verified, On-device badge).**
- **`GeminiCollectionSummarizer`** — the proven Firebase/Nano path; builds a prompt from the deltas.
- **`FallbackCollectionSummarizer`** (bound) — ML Kit primary → Gemini fallback. ML Kit failures surface up front (a status check), so the fallback swaps in before any tokens stream.

**Two hard-won ML Kit gotchas (see INTERVIEW_PREP §Story 5):**
1. **Feature 2004 provisions lazily** — early calls `606 FEATURE_NOT_FOUND` while AICore downloads a **~162 MB** model bundle in the background; then `checkFeatureStatus()` → `AVAILABLE`. It was **not** an AICore-version issue (bootloader locked, base Nano fine).
2. **`InputType.ARTICLE` requires ≥400 characters** — terse deltas throw `GenAiException`; the article expands every list's move + framing to clear it.

**Input guardrail honored:** the summary reads per-list deltas from the user's **own** `value_snapshots` (`ComputeWeeklyDeltas`), never a live eBay Browse response. On-device inference is foreground-only, so the summary regenerates on Portfolio load / after "Sync now" — **not** in the background worker.

---

## 4. Data layer & valuation semantics

- **`ValueSnapshotDao`** grew: reactive per-item history, latest-snapshot (debounce ref), aggregate **portfolio series** (`GROUP BY snapshotAt HAVING COUNT(*) > 1` — batch instants only), and a per-list series for deltas. `CollectibleDao.updateLastKnownValue` + a suspend `count`.
- **Current-value model:** `observeTotalValueCents` / `observeListBreakdown` and the item/detail rows all use `COALESCE(lastKnownValueCents, estimatedValueCents) × quantity`. Before any sync everything falls back to the import estimate; after sync everything moves together.
- **Backdated seed** (`SeedMockHistory`): idempotent, writes 12 weekly snapshots at **shared** instants per item (NFT-redeemable items drift harder, so "what moved" has a story). Guarded by `isHistorySeeded()`.
- **Snapshot-writing invariant (learned the hard way):** a *batch* sync writes all items at **one** timestamp; the portfolio series counts only multi-item instants — so a single "Snapshot today's price" (or the old per-item-timestamp worker bug) can't skew the sparkline. See INTERVIEW_PREP §Story 7.

---

## 5. Current app surface

`Router → Welcome/Import → Portfolio → Category → Detail`; `Ask` is a `ModalBottomSheet` off Portfolio.
- **Portfolio:** tracked headline + week-over-week delta + live sparkline (30/60/90d) · on-device "what moved" summary card (ML Kit, badge) · "Sync now" (in-place loader) · duplicate-aware breakdown.
- **Detail:** current value · live `MarketSection` (median-active + listings + Buy-on-eBay) · "Snapshot today's price" (debounced, snackbar) · 90-day per-item chart (30/60/90d).
- **Background:** `WeeklyPriceSyncWorker` scheduled (7-day, unmetered) via on-demand WorkManager init.

---

## 6. Stubs / deliberately deferred (Week-4 candidates)

- **Real `EbayBrowsePriceProvider`** — the mock stands in; the seam is ready for the sandbox → prod impl (display-only, never AI-fed). *(Week-4 stretch per plan.)*
- **`MockPriceProvider` is deterministic** — re-fetching returns the same value, so "Sync now" settles to **$0** honestly ("remained unchanged"). A small per-sync jitter would make demos livelier (one-liner).
- **Settings screen** — the ⚙ icon is still inert; the plan's "user-disable toggle for the weekly worker" needs a Settings surface (worker is scheduled unconditionally for now).
- **`MarketSection` "sold" data** — none (Browse can't see sold); the UI honestly shows median-active only.
- **"Edit details" / "Add another" / "Delete"** on Detail — still no-ops.
- **The `$240 vs $243` reconciliation** was fixed (summary total = portfolio-wide delta); the summary's first bullet still restates the total by choice, with bullets 2–3 carrying the "what moved."

---

## 7. Verified device facts (Pixel 10 Pro XL)

- **ML Kit GenAI Summarization runs on-device** — 3-bullet summary, On-device badge; feature 2004 provisioned after a ~162 MB download (+ reboot helped).
- **Bootloader locked** (`ro.boot.verifiedbootstate: green`) — required; ML Kit GenAI refuses on an unlocked bootloader.
- **AICore build** at time of writing: `aicore_20260528` (unchanged by an unrelated system update — the feature gate was provisioning + input length, not version).
- **The Gemini/Nano fallback ran in Cloud** briefly after clearing AICore storage (base Nano re-provisioning) — the hybrid fallback working as designed; badge showed Cloud, then returned to On-device.

---

## 8. Toolchain additions

- `com.google.mlkit:genai-summarization:1.0.0-beta1` + `androidx.concurrent:concurrent-futures-ktx:1.2.0` (`ListenableFuture.await()`).
- `androidx.work:work-runtime-ktx:2.10.3` + `androidx.hilt:hilt-work` / `androidx.hilt:hilt-compiler` (KSP) `1.4.0-rc01`.
- Manifest removes the default `androidx.startup` WorkManagerInitializer; `ArcanaApplication` implements `Configuration.Provider` with `HiltWorkerFactory`.
- **Dagger gotcha:** a Kotlin `List<PriceProvider>` @Provides collides with the `List<? extends PriceProvider>` use-site wildcard — build the chain's list inline in the module instead of injecting the `List`.

---

## 9. Test status

- New JVM tests (14): `MockPriceModelTest` (drift/determinism), `PriceProviderChainTest` (routing/short-circuit/rate-limit/unsupported), `SnapshotItemPriceTest` (same-day ∧ ±5% debounce matrix), `ComputeWeeklyDeltasTest` (batch-instant delta + portfolio-wide total).
- New fakes extend the Week-2 pattern: `FakeCollectibleRepository` (in-memory snapshots + settable series), `FakePriceProvider`.
- `FallbackCollectionSummarizer` / the summary in `PortfolioViewModel` are covered by device verification, not a unit test (the two engines are concrete classes; a fake would need qualifier-based DI — a cheap Week-4 add if desired).

---

## 10. Candidate Week-4 directions

Per `ARCANA_CONTEXT.md` and what's now built:
1. **Real `EbayBrowsePriceProvider`** — wire the sandbox behind the ready seam (display-only, never AI-fed), then production. Closes the last piece of the value story.
2. **Benchmark surface** — p50/p95 on-device-vs-cloud sweep screen; `InferenceMetadata` (incl. ML Kit latency) already flows. "Interview gold," cheap.
3. **Settings screen** — worker toggle, theme, model/inference readout; unblocks the deferred user-disable.
4. **Multi-module split** — boundaries have held three weeks; promote `:core:ai` / `:core:data` / `:core:domain` / `:feature:*`.
5. **Confidence-based escalation + capture flow** — the larger Week 6–7 surface.

Reference docs in-repo: `ARCANA_CONTEXT.md`, `DESIGN.md`, `WEEK_02_SUMMARY.md`, `WEEK_03_PLAN.md`, `arcana-wireframes.html` (design source of truth).
