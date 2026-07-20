# Arcana — Week 2 Build Plan

*Conversation 5 output. The first artifact about what you do Monday morning, not what Arcana is. Commit to repo root as `WEEK_02_PLAN.md`.*

---

## The week's goal, in one sentence

Fresh install → import the real 283-item HobbyDB CSV → see the collection as a Compose grid → tap into an item → ask "what's my most valuable item?" and watch Gemini Nano stream the answer **on-device**, with the inference badge and latency telemetry visible.

That is one vertical slice through every architectural layer: importer → Room → repository → ViewModel → Compose, plus the `GeminiService` boundary and the on-device inference path. If this slice holds, the rest of the 12 weeks is widening it, not discovering it.

## Working assumptions

- This is side-project time around a full-time job. Each "day" is one focused block (≈2–3 hours), not eight hours. Seven blocks across the week with weekend buffer.
- The Pixel 10 Pro XL is in hand, running Nano v3 on-device. The hardware gate is cleared — the first live inference is real on Day 1, not stubbed.
- The three planning docs (README, DESIGN, SCREENS) are the source of truth for *what* to build. This plan is only *sequence and done-criteria*.

---

## Scope discipline — what Week 2 is and isn't

**In, on purpose:**
- Real schema including the **series junction tables** — because the importer's correctness on comma-separated `Series` is a tested assertion and a core data-engineering showcase, and series is displayed on detail.
- A **DB-grounded** answer to the one question — the top items are queried from Room and serialized into the prompt. This is the honest minimal version and a clean precursor to Week 9 RAG.

**Out, deliberately — these are not gaps:**
- The full chat UI / `ModalBottomSheet`. Week 2 is **one button**, not the chat feature.
- RAG / embeddings / vector store → Week 9.
- The image cascade, camera, segmentation, OCR → Week 7.
- `PriceProvider`, market section, eBay → Week 3–4.
- Value-history chart, weekly sync worker → later.
- `production_tags` junction (not displayed in Week 2's UI), multi-module split (Week 4).

If you find yourself building any "out" item this week, stop — that's the over-engineering pattern the project keeps catching.

---

## Day 1 — De-risk the inference path, then scaffold

Build the highest-unknown thing first. AICore has sharp edges (model download, warm-up, 606, BUSY); you want them to surface on Day 1, not Day 5 when they can sink the week.

**Tasks**
- New project: `com.aashishgodambe.arcana`, Kotlin + Compose + Material 3, Hilt (`@HiltAndroidApp ArcanaApplication`). Lay down the `ui/ core/ feature/` package skeleton from DESIGN even if mostly empty — organize as if modules from line one.
- Dependencies: Compose BOM, Room, Hilt, Coil, coroutines/Flow, Firebase AI Logic SDK, Turbine + MockK + JUnit.
- Firebase project + `google-services.json`, enable Firebase AI Logic. **This is the likeliest setup-friction point — budget for it.**
- Throwaway spike: a single composable that fires one Firebase AI Logic hybrid call (`InferenceMode.PREFER_ON_DEVICE`) with a hardcoded prompt and streams tokens to the screen. Log `executedOn`, first-token latency, total latency. No DB, no abstraction — this code gets deleted Day 5. Read the `gemini-hybrid` sample line by line first; verify its SDK surface against current docs, since this space has moved.

**Definition of done**
- [ ] App launches on the Pixel 10 Pro XL.
- [ ] A token stream from **on-device** Nano renders on screen — confirm `executedOn == OnDevice`, not a silent cloud call.
- [ ] You've seen the cold-start latency and the warm latency with your own eyes, and know which Nano model Firebase routes to on this device.

---

## Day 2 — Room schema and DAOs

The persistence layer the importer writes into.

**Tasks**
- Entities: `CollectibleEntity`, `FunkoMetadataEntity`, `SeriesEntity` + `CollectibleSeriesCrossRef`, `ValueSnapshotEntity`. Enums + `TypeConverters` for `LocalDate` / `Instant`.
- DAOs: `CollectibleDao` (`insert`, `getAll(): Flow`, `getById`, `getMostValuable(limit): List`), `FunkoMetadataDao`, `SeriesDao` (insert-or-get canonical), `ValueSnapshotDao`.
- `ArcanaDatabase` + Hilt `DataModule`.

**Definition of done**
- [ ] Schema compiles; Room generates cleanly.
- [ ] Instrumented test: insert a collectible with two series, read it back with the series joined.
- [ ] `getMostValuable()` returns rows ordered by `estimatedValueCents DESC`.

---

## Day 3 — The importer (the data-engineering showcase)

Turn the real 283-item CSV into correct rows, every quirk handled. This is one of the strongest "handles real, messy data" signals in the whole project — treat the test as the deliverable.

**Tasks**
- `CollectionImporter` interface + `ImportedItem` / `ImportResult` boundary (per DESIGN).
- `HobbyDbCsvImporter`: strip `=HYPERLINK(...)` wrappers; split comma-separated `Series` and `Production Status` into lists; keep `UPC` as String (leading zeros); flag `Exclusive To = NFT Redeemable`; honor `Quantity`. Lenient failure — a bad row logs a warning and is skipped, the rest succeed.
- `Repository.importFrom(...)`: map `ImportedItem` → `CollectibleEntity` + `FunkoMetadataEntity` + series cross-refs (insert-or-get canonical series). Write one `ValueSnapshotEntity(trigger=Import, source=HobbyDbImport)` per item — cheap, and it seeds the schema for later.

**Definition of done** — integration test against the real CSV (committed to `seed-data/`)
- [ ] 283 collectibles inserted.
- [ ] 142 funko rows with `isNftRedeemable = true`.
- [ ] Series junction populated — a known multi-series item resolves to the right number of rows.
- [ ] No `=HYPERLINK(` substring survives anywhere; a known leading-zero UPC is preserved exactly.
- [ ] Skipped-row count surfaced in `ImportResult.warnings`.

---

## Day 4 — Onboarding import, grid, detail (the visible app)

Launch → import → see your collection → tap in.

**Tasks**
- Onboarding Welcome (minimal, two paths; "Import from HobbyDB" opens SAF `ACTION_OPEN_DOCUMENT` for CSV mime types).
- Import Progress: Writing state with determinate count, auto-nav on complete; PartialComplete / Failed handled minimally.
- Collection grid: `LazyVerticalGrid`, Coil images from `imageUrl`, name + value per cell. `CollectionViewModel` exposes `Flow<List<Collectible>>` from the repo.
- Detail (minimal): image, name, series chips, exclusive, NFT badge, value. Route `detail/{localId}`. `DetailScreen` routes through the **sealed `Collectible`** `when` with `TODO()` branches for FigPin/Pokemon — forces polymorphism now, before the second category exists.

**Definition of done**
- [ ] Fresh install → import the real CSV → 283 items render in the grid with images.
- [ ] Tap any item → detail shows correct fields.
- [ ] Detail dispatches through the sealed-type `when`.

---

## Day 5 — Promote the spike into `GeminiService`, answer the one question

The `ai-samples` money-shot. Replace Day-1 throwaway with the real abstraction and ground the answer in the actual collection.

**Tasks**
- `GeminiService` interface + `InferenceResult` / `InferenceMetadata` / `RoutingHint` / `InferenceLocation` (verbatim from DESIGN).
- `HybridGeminiService`: Firebase AI Logic, `PREFER_ON_DEVICE`; catch `606 FEATURE_NOT_FOUND` → cloud; `Mutex` for `BUSY`; capture first-token vs total latency separately.
- `FakeGeminiService`: canned streaming for previews and tests.
- `AiModule` binds the impl.
- The one question — **not chat, not RAG**: a button "What's my most valuable item?" → query Room for the top item(s) → build a small grounded prompt embedding those facts → `generateText(...)` → stream into a `StreamingText` surface → render `InferenceBadge` from `metadata.executedOn`.
- `AskViewModel` with one meaningful unit test (FakeGeminiService + Turbine).

**Definition of done**
- [ ] Tap → on-device Nano streams a sentence naming **Daenerys with Egg ($690)**.
- [ ] `InferenceBadge` reads "On-device"; `executedOn` + latency captured.
- [ ] Force the fallback once (airplane mode or simulated 606) → it routes to cloud and the badge flips.
- [ ] `AskViewModel` test passes against the fake.

---

## Weekend / buffer

- One meaningful test per ViewModel (Collection, Detail, Ask) — the architecture rule, not optional.
- Model-not-ready state: the ask surface shows "Setting up on-device AI…" until `modelReadinessFlow` is ready (minimal `DeviceCapabilityChecker`).
- README v0: what works, how to run, one screen-recording GIF of the streamed on-device answer — that GIF is the artifact you'll actually show people.
- Push the first commit: README, DESIGN, SCREENS, this plan, `seed-data/`, LICENSE, `.gitignore`, and the code, to `github.com/aashishg11/arcana`.

---

## Week 2 — Definition of Done (the single checklist)

- [ ] Installs and runs on the Pixel 10 Pro XL
- [ ] Real 283-item CSV imports; integration test green (283 / 142 NFT / series junction / hyperlink-stripped / leading-zero UPC)
- [ ] Grid of 283 items with images
- [ ] Tap → detail with correct fields, dispatched via sealed type
- [ ] "What's my most valuable item?" answered on-device, streamed, badge shows On-device, telemetry captured
- [ ] 606 → cloud fallback verified once, badge flips
- [ ] One meaningful test per ViewModel
- [ ] Code in the module-as-packages structure
- [ ] First commit pushed, README v0 with the demo GIF

---

## Gotchas to watch (from the planning docs, now live)

- **Firebase AI Logic setup** — most likely Day-1 time sink. Get `google-services.json` and API enablement right before the spike.
- **Model download ≠ ready** — AICore downloads the model in background on first launch; calls fail until it finishes. The "Setting up on-device AI…" state covers this.
- **Cold-start warm-up** — 2–5s first call even here. `firstTokenLatencyMs` separate from `totalLatencyMs` from Day 1, so the benchmark story distinguishes first-call from steady-state.
- **606 / BUSY** — fallback and `Mutex` are Day-5 tasks; the 606 path must be exercised once this week, not assumed.
- **Single-turn** — irrelevant for one question; becomes real when chat history arrives later.

## What Week 3 inherits

A working `GeminiService` boundary and a populated DB. Week 3 escalates: when Nano isn't confident, go to Gemini multimodal cloud, and stub ML Kit Text Recognition. Build the Day-5 fallback cleanly and Week 3 is an extension of it, not a rewrite.
