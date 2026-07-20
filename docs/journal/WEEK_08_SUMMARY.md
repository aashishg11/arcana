# Week 8 — Completion Summary (handoff for Week 9)

Status: **complete.** The app's premise finally becomes true — Arcana can *identify a collectible*. This
week built the **capture-cascade engine**, headless and de-risked, driven from a dev harness. No capture
UI (that's Week 9); the engine is the deliverable.

**The headline:** *Point the cascade at a photo of a real Funko box and it returns a confident, sourced
identity — segmentation → on-device Nano description → OCR → local-collection match → cloud escalation —
emitted as a per-stage `Flow<CascadeState>`. Verified end-to-end on the Pixel: a Freddy Funko #32 photo
resolves **on-device as an owned item**, and an Aang #406 photo the user doesn't own **escalates to cloud**
and comes back "Aang with Armor - Metallic" in ~5.4 s. Four new `ai-samples` capabilities land in one
feature (subject segmentation, on-device multimodal, text recognition, cloud multimodal) — 2 shipped → 6.*

---

## 1. What shipped (per the Week-8 plan)

- **Day 1 — three gates.**
  - *Gate C:* cloud model `gemini-2.5-flash-lite` → **`gemini-3.1-flash-lite`** (2.5 deprecated / already
    404-ing). Verified with one real cloud call.
  - *Gate B:* Firebase **App Check** debug provider, wired the release-safe way (variant-split
    `installAppCheck()` — real in `src/debug`, no-op in `src/release`). Enforcement isn't yet on for this
    project, so cloud works today; the provider is ready for when it flips.
  - *Gate A (the real find):* the fixed **ML Kit Image Description** captioner **deterministically
    safety-refuses** fantasy/horror Funko imagery (Aang #406 → ErrorCode 11). Switched to the **ML Kit
    GenAI Prompt API** with a product-framed prompt — it passes safety *and* reads identity + Pop number
    on-device. A benign Freddy Funko #32 confirmed the refusal is content-driven, not universal.
- **Day 2 — deterministic ML Kit stages.** `TextExtractor`/`MlKitTextExtractor` (OCR) + a pure
  `PopNumberParser` (the Pop number is the visually **largest** number — real boxes rarely print `#`) +
  `BoxLayoutParser` that extracts **franchise / series / number / character / finish / rarity-or-exclusive
  / edition by the box's fixed positional layout**. `ImageSegmenter`/`MlKitImageSegmenter` for the masked
  subject. Grounded and tested on real OCR from two boxes.
- **Day 3 — the catalog chain.** `CatalogProvider` seam + one structured `CatalogQuery` (evolving DESIGN's
  UPC/name/image triad) + `CatalogEntry`. `LocalCollectionCatalogProvider` matches the user's 504 items on
  the Pop number (strong key) + franchise/character corroboration, rejecting same-number coincidences.
  `CatalogProviderChain` short-circuits on the first confident hit. `CascadeHintFusion` merges OCR +
  Nano into one query (OCR wins; Nano's labels swap). Proven on the real imported collection.
- **Day 4 — cloud escalation + confidence.** `CloudMultimodalCatalogProvider` sends the frame + hints to
  `gemini-3.1-flash-lite` with a structured-JSON schema. The **composed-confidence gate** is written down
  (local score vs 0.7 threshold): a Pop number needs a corroborating signal to resolve on-device;
  otherwise it escalates. `CatalogEntry` carries `executedOn` + latency for the badge flip.
- **Day 5 — orchestrator, barcode, harness.** `CaptureCascade.identify(bitmap): Flow<CascadeState>`
  (segment → describe → OCR → fuse → chain → settle) with per-stage telemetry; `NanoMultimodalDescriber`
  (real Prompt API); `BarcodeScanner`/`MlKitBarcodeScanner` + `identifyFromBarcode` (UPC → chain,
  skipping the vision stages); full Hilt wiring; and a **debug-only dev harness** in Settings (pick a
  photo → watch the cascade run).

## 2. The numbers — real end-to-end run (Pixel 10 Pro XL, Aang #406, unowned → cloud)

```
settled 'Aang with Armor - Metallic' via Cloud in 5372ms
  stages = { ocr: 1053, describe: 496, catalog: 2597, segment: 1224 } ms
```

- **OCR ~1 s**, **catalog ~2.6 s** (dominated by the cloud multimodal call), **segment ~1.2 s**. An owned
  pop resolves *without* the cloud leg — the chain short-circuits on the local hit (no network).
- On-device Prompt-API description: **~6–7 s** for terse JSON, ~15–19 s for prose (Gate A); it's a
  best-effort hint, not on the critical path.
- OCR Pop-number recovery on 6 real Freddy Funko #32 photos (glare/angle/backlight): **5/6** as best.

## 3. Prediction scorecard (pre-registered)

- "Nano yields a generic description, no reliable number/identity — the catalog chain carries ~all the
  weight" → **half right, inverted reason.** The catalog chain *does* carry identification, but not
  because Nano is weak: the Prompt API read `#406` and the character fine. It's because (a) the fixed
  captioner *refuses* and (b) the capable Prompt API is too slow to block on. Surface choice + prompt
  framing was the whole game.
- "OCR gives the number cleanly" → **mostly** (5/6); the miss was an ML Kit **glyph** misread (32→82 under
  backlight), not a parser fault — which is exactly why fusion + multi-frame matter.

## 4. The honest failure catalogue (Week 9's low-confidence UI spec)

- **On-device description refuses fantasy/horror boxes** (ErrorCode 11). Expect a "no on-device hint"
  state; the cascade already treats a null description as normal.
- **OCR glyph misreads under harsh backlight** (32 → 82). Single-frame OCR isn't infallible; Week 9's
  camera should sample a few frames and let fusion/majority vote win.
- **Barcode not decoded on an angled side-panel shot** (returned null, no error). The barcode path needs a
  reasonably front-on barcode; Week 9's camera can frame/guide it.
- **Nano's field labels are unreliable** — it swapped character/franchise on both test boxes. The
  positional `BoxLayoutParser` is the authority; Nano corroborates.

## 5. Bugs found + fixed by the real end-to-end test (only an e2e would surface these)

1. **OCR on the segmented bitmap fails** — the box text lives on the box, not the isolated figure. OCR,
   describe, and cloud all read the **full frame**; segmentation's mask is UI-only.
2. **Invoking AICore (Nano) or subject segmentation before text recognition breaks the recognizer
   process-wide.** OCR now runs **first**; description and the UI-only segmentation run after.
3. **The Nano Prompt API recycles its input bitmap** — which then killed the cloud stage ("Can't compress
   a recycled bitmap"). The describer now hands Nano a throwaway copy so the shared frame survives.

## 6. Files & artifacts

- **New (`core/ai/cascade/`):** `TextExtraction`, `TextExtractor`/`MlKitTextExtractor`, `PopNumberParser`,
  `BoxLayoutParser`, `ImageSegmenter`/`MlKitImageSegmenter`, `BarcodeScanner`/`MlKitBarcodeScanner`,
  `MultimodalDescriber`/`NanoMultimodalDescriber`, `CascadeHintFusion`, `CascadeState`, `CaptureCascade`.
- **New (`core/ai/catalog/`):** `CatalogProvider`/`CatalogQuery`/`CatalogEntry`, `CatalogProviderChain`,
  `LocalCollectionCatalogProvider`, `CloudMultimodalCatalogProvider`.
- **Wired:** `AiModule` (provides `CaptureCascade` with its seams + ordered chain), `HybridGeminiService`
  (model bump), `ArcanaApplication` (App Check), Settings dev-harness card + ViewModel.
- **Tests:** JVM units for `PopNumberParser`, `BoxLayoutParser`, `CascadeHintFusion`,
  `LocalCollectionCatalogProvider` (incl. UPC), `CatalogProviderChain`; device integration for the
  extractor+layout, segmenter, cloud (real call), the real-CSV local match, the orchestrator (fakes), and
  the full real e2e. Full suite green.
- **Deps:** `genai-prompt`, `text-recognition`, `play-services-mlkit-subject-segmentation`,
  `barcode-scanning`, `firebase-appcheck(-debug)`.
- **Tooling:** `tools/restore-model.sh` (re-side-load the own-model after test uninstalls wipe it).

## 7. Gotchas worth carrying forward

- **Stage order is load-bearing:** OCR must run before any AICore/segmentation call, or text recognition
  fails process-wide. The Prompt API recycles its input bitmap — copy it.
- **`connectedAndroidTest` uninstalls the app**, wiping the side-loaded `.litertlm` *and* the App Check
  debug token. `tools/restore-model.sh` handles the model; re-register the token from logcat only if App
  Check enforcement is ever on.
- **Box beats retail** on conflicting metadata (edition size); **"Freddy Funko" is a generic recurring
  name** so number+name can collide across series — franchise is the disambiguator.
- Real capture fixtures (the user's own photos) are **gitignored**; the committed coverage embeds real OCR
  as test data instead.

## 8. What Week 9 inherits

A proven, telemetried `CaptureCascade` emitting a clean per-stage `Flow<CascadeState>` — so the Review
screen's animated states (segmentation outline, streaming description, `#NNN` callout, on-device↔cloud
badge flip, "you already own this") are a **rendering** of that flow, not new logic. Plus the barcode
fallback (`identifyFromBarcode`), the composed-confidence gate, the honest failure catalogue above as the
low-confidence UI spec, and the dev harness as a manual way to feed the cascade real photos. Camera +
Review UI + save-to-collection (which must prompt for the list/"category", since captured pops carry
none) become a clean, one-week UI build on a known-good engine.

## 9. Reading order for Week 9

`WEEK_08_SUMMARY.md` (this) → `WEEK_08_PLAN.md` (scope) → `DESIGN.md` (the cascade + `CatalogProvider`
seams) → `arcana-wireframes.html` (the Review screen this renders) → `core/ai/cascade/CaptureCascade.kt`.
