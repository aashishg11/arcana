# Week 9 — Completion Summary (handoff for Week 10)

Status: **complete.** The app's premise becomes visible. Week 8 built the identification *engine* headless;
Week 9 built the **capture UI on top of it** — camera → the cascade running as designed beats → a settled,
sourced identity → **save into the collection** — plus the **real eBay price integration** the value story
had been faking, and a **working delete** the collection had been missing.

**The headline:** *Point the phone at a Funko box and watch it identify — the segmentation outline snaps on,
the `#NNN` OCR callout lands, the catalog chain walks, the on-device↔cloud badge flips, it settles, and you
save it into a chosen list with a real eBay value. An owned pop resolves **on-device** (no network) in ~1.6 s;
an unowned pop **escalates to cloud** and comes back in ~5–10 s. Verified end-to-end on the Pixel with real
boxes (owned Freddy Funko #32 on-device; unowned Whitebeard w/ Moby Dick #127 → cloud → saved → deleted →
restored). Six of eight `ai-samples` capabilities are now shipped and **visible**, not just true.*

---

## 1. What shipped (per the Week-9 plan)

- **Day 1 — Camera + capture loop.** CameraX viewfinder (clean, no reticle — segmentation handles framing),
  centre shutter, status-bar-inset close, a deliberately understated bottom-left barcode fallback, and a
  permission rationale ("Arcana needs camera access…") with a settings deep-link on permanent denial. The
  Portfolio `⊕` FAB opens `capture/camera`; a `CaptureSessionStore` (`@ActivityRetainedScoped`) hands the
  frozen frame(s) to `capture/review` (bitmaps can't ride nav args). **Escalation-burst OCR** seam:
  `CaptureCascade.identify(frames: List<Bitmap>)` (single-frame `identify(bitmap)` still delegates), the
  majority vote in a pure `OcrBurstVote`.
- **Day 2 — the animated Review hero.** `CaptureReviewScreen` renders the `Flow<CascadeState>` as beats in
  **real-resolution order**: animated scanline + dashed iris segmentation outline, `#NNN` callout with a
  leader line, a phase chip, catalog status lines, the `InferenceBadge` (On-device → Cloud flip), settled
  identity with an animated confidence bar and ownership callout. **Load-bearing engine fix:** the ~6–7 s
  Nano describe moved **off the critical path** (launched concurrently, trails Settled as an optional line),
  so an owned local hit settles in **~1.6 s** (was ~5.6 s).
- **Day 3 — settled + low-confidence + failure states.** The shared `MarketSection` (now real eBay) on the
  settled Review; the "you already own ×N" callout; the low-confidence variant (heading softens to "Not
  sure — this might be…", the barcode fallback promotes from a tertiary link to a button); an unresolved
  no-match state; and the local-match tightening (below).
- **Day 4 — save to collection + delete.** `saveCaptured` (origin `ArcanaCapture`, metadata + series +
  an initial eBay-priced snapshot) into a **user-chosen list** (a captured pop carries none — the picker
  offers existing lists or create-new, with a token-matched suggestion); **"Add another"** →
  `incrementQuantity`; lands on **Detail** after save. And a real **delete** — `repository.delete` +
  a Detail confirmation dialog + nav-back — which had been a **no-op stub**.
- **Day 5 — tests, README, demo.** Device-free logic tests (below); README made capture-first; this
  summary; demo capture.

## 2. The real eBay price integration (pulled forward from a Day-4 prerequisite)

Saving an *unowned* pop needs a real value — the identity carries none and the old price layer was a **mock**
derived from the CSV estimate (a never-seen pop would read ~$0). So Week 9 built the real
**`EbayBrowsePriceProvider`**, retiring the mock to a fallback:

- OAuth client-credentials **application token** (cached ~2 h behind a Mutex) → Browse `item_summary/search`
  → median active + listings; `HttpURLConnection` (no HTTP dep), `org.json` parse (house style).
  Credentials in a **gitignored `ebay.properties`** → `BuildConfig` (never in git).
- **Query accuracy (the collector's-eye fixes, verified on real boxes):**
  - **Product line.** "Captain America 01" returned every Captain America pop (median ~$35); adding the
    line from the series ("Die-Cast") isolates the actual pop (~$77).
  - **Franchise disambiguation.** "Freddy Funko" is generic (as Popeye, as Aang…). The *collection* stores
    no franchise for these — only the **OCR box read** has it — so `CascadeReconciler` **box-wins the
    franchise** into the settled entry, and the query searches "Freddy Funko Popeye 32" → the right pop
    ($65) vs a mixed bag ($57).
- **Listings = cheapest by total (price + shipping), lowest first** — not the priciest outliers. Shipping is
  parsed from `shippingOptions` (FIXED = known incl. free; CALCULATED = null, marked "+ shipping").

## 3. The numbers (real, on the Pixel 10 Pro XL)

- **Owned pop, on-device:** Freddy Funko #32 settles in **~1.6 s** (was ~5.6 s before describe moved off the
  critical path). Burst of 5 frames costs ~1.2–1.5 s of the shutter→cascade time.
- **Unowned pop, cloud:** Whitebeard #127 → cloud → **~9.5 s** to settle.
- **Save/value math:** saving the Whitebeard moved Portfolio 504 → 505 items, +$30 (the $29.99 eBay median);
  delete restored it to exactly 504 / $29,811.
- **Burst vote caught a live misread:** `[32, 32, 82, 32] → #32` and `[32, 32, 32, 2, 32] → #32` — the
  majority outvoted single-frame glyph errors.

## 4. Prediction scorecard (pre-registered)

- "Burst pre-capture ≈ 0.6 s" → **2× wrong: measured 1.21 s** (each ImageCapture still ~300 ms, not ~150).
- "Owned local hit settles ~9 s after the describe fix" → **too pessimistic: 1.6 s** (the earlier 9 s was a
  slower describe on a non-box frame; a benign box's describe is terser).
- "A corroborated first-frame read never needs the burst" → **wrong, and it bit us.** A single-frame glyph
  misread (32→82, 32→62) looks *perfectly confident* (right franchise, plausible number), so the burst
  can't be gated on the primary read — it must **always vote**. Fixed.
- "Franchise fixes the median" → **right, verified** ($65 vs mixed-bag $57; listings now the correct pop).

## 5. The honest failure catalogue → designed states (Week-9 UI)

- **Description refuses** (fantasy/horror, ErrorCode 11) → the on-device line simply doesn't appear; its
  absence is designed to be invisible.
- **OCR number uncertain after voting** → low-confidence variant, barcode promoted to a button.
- **Nano's labels disagree with the parser** → the parser/OCR is authoritative; the on-device line surfaces
  Nano **only where it corroborates the number** ("Gemini Nano also read #32"), never its swapped
  character/franchise labels.
- **Barcode undecodable** → "Couldn't identify (barcode)".
- **Nothing resolves** → an honest "Not sure what this is" no-match state with the barcode as the way in.

## 6. Bugs found + fixed by real on-device capture (only real captures surface these)

1. **Burst gated on corroboration let glyph misreads through** — a live #32→#82 read matched the *wrong*
   owned pop (Parallax Hal Jordan #82) at 70% because only the (weak) **series** corroborated. Two-line fix:
   the burst now **always votes** (`OcrBurstVote` gate removed), and `LocalCollectionCatalogProvider`
   **requires franchise/character** to corroborate a same-number match (series is near-worthless — numbers
   restart per series and half the collection is "Pop! Digital").
2. **Segmented subject rendered tiny** — ML Kit `foregroundBitmap` is full-frame-sized with the subject in a
   sea of transparency. `MlKitImageSegmenter.cropToContent` crops to the opaque bounds so the item fills the
   hero frame.
3. **The median priced the wrong pop** — too-generic query (name + number). Fixed with product-line +
   franchise (§2).
4. **"Delete from collection" was a no-op stub** — surfaced when capture started *adding* items with no way
   to remove them. Now wired with a confirmation dialog.

## 7. Files & artifacts

- **New (`feature/capture/`):** `CameraScreen` (+`CameraViewModel`), `CaptureSessionStore`,
  `CaptureReviewViewModel` (+ pure `reduceCapture`/`capturedItem`), `CaptureReviewScreen`.
- **New (`core/ai/cascade/`):** `OcrBurstVote`, `CascadeReconciler` (box-wins series + franchise).
- **New (`core/ai/pricing/`):** `EbayBrowseClient`, `EbayBrowsePriceProvider`, `EbayPriceMath`, `EbayListing`.
- **Changed:** `CaptureCascade` (burst entry + always-vote + describe off the critical path + reconcile),
  `MlKitImageSegmenter` (crop), `MarketContext.ActiveListing` (shipping/total), `MarketSection` (cheapest +
  shipping notes), `LocalCollectionCatalogProvider` (franchise-only corroboration), `CollectibleRepository`
  (+`saveCaptured`/`incrementQuantity`/`listNames`/`delete`), `CollectibleDao`/`SeriesDao`, `DetailScreen`
  (delete + confirm), `PortfolioScreen` (FAB), `ArcanaNavHost` (capture routes), `build.gradle.kts`
  (CameraX + eBay `BuildConfig`), the manifest (camera permission).
- **Tests:** `OcrBurstVoteTest`, `CascadeReconcilerTest`, `EbayPriceMathTest`, `CaptureReviewLogicTest`
  (`reduceCapture` over every `CascadeState` incl. failure + `capturedItem` + a save round-trip through the
  fake repo), `LocalCollectionCatalogProviderTest` (series-corroboration tightening). Full JVM suite green.
- **New deps:** CameraX 1.4.2 (core/camera2/lifecycle/view). eBay uses `HttpURLConnection` + `org.json` — no
  new HTTP/JSON dep. Credentials: gitignored `ebay.properties` (+ committed `ebay.properties.example`).

## 8. Gotchas worth carrying forward

- **The burst must always vote** — a single-frame glyph misread looks confident; don't gate on the primary
  read. Corroboration is now only a vote **tie-breaker**.
- **The Pop number is not unique** — it restarts per series, so it's never a standalone identifier;
  franchise/character disambiguates. Series is near-worthless for it.
- **Run `:app:testDebugUnitTest` before committing, not just `installDebug`.** Two Week-9 commits shipped
  latent test-compile breaks (a new required field / new interface methods) that incremental builds masked.
  Inline `CollectibleRepository` impls in tests: `FakeCollectibleRepository` + `AskViewModelTest`.
- **eBay Marketplace-Account-Deletion exemption** ("I do not persist eBay data") holds only because we
  persist **only the aggregate price**, never eBay user/seller data; listings are transient/live-render.
- Stage order still load-bearing (OCR before AICore/segmentation); the Prompt API recycles its bitmap.

## 9. What Week 10 inherits

A feature-complete Arcana: import, portfolio, value tracking (now **real eBay**), on-device summarization,
three-engine Ask, a benchmark surface, and **capture** end-to-end (camera → cascade → identify → save/delete).
**Six of eight `ai-samples` capabilities shipped.** Remaining: **LiteRT RAG** (semantic retrieval for Ask —
fixes the lexical undercount; the vehicle is already a dependency) and **`genai-writing-assistance`** (the
listing writer — a cheap fast-follow on the ML Kit GenAI groundwork). Still parked, now more attractive: the
**Snapdragon cross-vendor benchmark**.

**Queued robustness (deferred from Week 9):**
- **Burst tie-break via catalog-disambiguation.** A 2-2 tie (`[62,62,32,32]→62`) still mis-picks; the robust
  fix is to try the tied candidate numbers against the local catalog and prefer the one that resolves (the
  collection has Popeye #32, not #62). Bumping the burst to 5 frames (odd) only mitigates.
- **On-device read as a visual description.** Since describe no longer feeds identification, re-prompt
  `NanoMultimodalDescriber` for what Nano is reliable at — a visual description ("a masked figure in a red
  suit, gold crown") — restoring the wireframe's streaming "AI describing the item" beat honestly.

## 10. Reading order for Week 10

`WEEK_09_SUMMARY.md` (this) → repo `CLAUDE.md` → `DESIGN.md` (the RAG/Ask sections) →
`core/ai/cascade/CaptureCascade.kt` (if touching capture) → `core/ai/pricing/EbayBrowsePriceProvider.kt`.
