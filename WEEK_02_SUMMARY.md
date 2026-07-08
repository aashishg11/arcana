# Week 2 — Completion Summary (handoff for Week 3 planning)

Status: **complete and shipped** (branch `main`, pushed to `github.com/aashishg11/arcana`).
Last commit: `a07e06c` — "Hybrid on-device AI — GeminiService abstraction, grounded Ask Arcana".
All tests green: **8 JVM unit + 5 instrumented**, verified throughout on a physical **Pixel 10 Pro XL** (Tensor G5, Nano v3 on-device).

This document is written for an agent planning Week 3. It states what exists, what is stubbed, the verified facts, the gotchas, and the candidate entry points — so Week 3 can be scoped without re-reading the whole tree.

---

## 1. What Week 2 delivered (vs. the plan)

`WEEK_02_PLAN.md` scoped Week 2 as: CSV import → Compose grid of items → tap to detail → Gemini Nano answers **one** question streamed live. **All of that shipped, plus materially more:**

- **Full `GeminiService` abstraction** (not just a one-shot call) with a production hybrid impl, a fake, and a Hilt binding.
- **Multi-turn "Ask Arcana"** chat (not one question) with **grounded retrieval** over the collection, an on-device↔cloud **inference badge**, streaming text, and a **benchmark compare** control.
- **UI redesigned to full wireframe fidelity** (`arcana-wireframes.html` is the design source of truth — dark-first, iris/gold, value-first Portfolio home) with bundled variable fonts.
- **Duplicate-aware valuation** correction (portfolio + per-category), a HobbyDB-style collapsed stats block, and `×N` quantity badges.

The original throwaway Nano spike (`SpikeScreen.kt`) was promoted into the real abstraction and deleted.

---

## 2. Architecture in place

Single Gradle module (`:app`), organized package-by-feature as if multi-module (promotion to modules is a later-week polish item). Base package `com.aashishgodambe.arcana`.

```
core/
  ai/            GeminiService (+ HybridGeminiService, FakeGeminiService), di/AiModule, model/*
  data/
    database/    Room: entities, DAOs, ArcanaDatabase, TypeConverters
    importer/    HobbyDbCsvImporter (+ boundary model types)
    repository/  CollectibleRepository (+ Impl)
    di/          DataModule (Room + DAO providers)
  domain/model/  Collectible (sealed) + FunkoPop
feature/
  onboarding/    OnboardingWelcomeScreen, ImportScreen (+ VM)
  portfolio/     PortfolioScreen (+ VM)   ← home
  collection/    CategoryScreen (+ VM)    ← per-list item list
  detail/        DetailScreen (+ VM)
  ask/           AskSheet, AskViewModel   ← "Ask Arcana"
ui/
  component/     Atoms (chips, buttons, QuantityBadge…), InferenceBadge, StreamingText
  navigation/    ArcanaNavHost (Routes, RouterScreen)
  theme/         Color (ArcanaColors + Local), Type (variable fonts), Theme, Format
```

**Five interface seams (features never import Firebase/ML Kit/HTTP directly):** `GeminiService`, `CollectibleRepository` are implemented; `PriceProvider`, `CatalogProvider`, `CollectionImporter` are named in `DESIGN.md` but only `CollectionImporter`'s concrete importer exists so far. Domain is a **sealed `Collectible`** with exhaustive `when` dispatch (only `FunkoPop` in v1; FigPin/Pokemon are compile-time holes).

---

## 3. The AI layer (Week 2 centerpiece)

**`GeminiService.generateText(prompt, routingHint): Flow<InferenceResult>`** — the single seam. Callers depend only on this + the sealed `InferenceResult` (`Streaming` / `Success(fullText, metadata)` / `Error(cause, fallbackAvailable)`). `InferenceMetadata` = `executedOn` + `totalLatencyMs` + `firstTokenLatencyMs` + `outputTokenCount`. `RoutingHint` = `Auto | PreferOnDevice | OnlyOnDevice | OnlyCloud`.

**`HybridGeminiService`** (Firebase AI Logic):
- `InferenceMode.PREFER_ON_DEVICE` for `Auto`; reads the real `chunk.inferenceSource` (`ON_DEVICE`/`IN_CLOUD`) to record where it actually ran.
- Explicit catch of `FirebaseAIOnDeviceNotAvailableException` (ErrorCode **606**) → cloud retry (defense-in-depth; PREFER already auto-falls-back).
- `Mutex` serializes calls (AICore runs one inference at a time → BUSY otherwise).
- First-token vs total latency captured separately (Nano's first call is a cold-start warm-up).
- Cloud model: `gemini-2.5-flash-lite`.

**Grounding / retrieval (lexical, in `CollectibleRepositoryImpl.search`)**:
- Tokenize the question, drop stopwords (question scaffolding + ranking words like "most/valuable"), keep terms ≥3 chars.
- **AND semantics**: an item must match *every* term across name/brand/listName/series (joined) — so "power rangers" returns only the Power Rangers series, not everything containing "power".
- `AskViewModel` grounding: keyword matches → else reuse current thread's focus (subject-less follow-ups like "tell me what they are") → else top-value by `getMostValuable` (ranking questions on a fresh thread). Recent turns (last 3) are fed into the prompt so pronouns resolve. Prompt forbids guessing / outside-knowledge; relies on the shown `series` field.
- This is **lexical only** — the Week-9 RAG-over-embeddings upgrade slots in behind the same UI.

**UI**: `AskSheet` is a `ModalBottomSheet` (wrap-content, grows to ~72% then scrolls; darker `sheet` surface tone). Each turn renders inline: question → tappable retrieved chips (→ item detail) → streamed answer → benchmark caption + "Compare on cloud/on-device". Shared `InferenceBadge` (animated on-device↔cloud flip) and `StreamingText` (inline blinking caret, no reflow) are reused by the future capture flow.

---

## 4. Data layer & valuation semantics

- **Room** (KSP), destructive migration (schema still churning, no released data). Entities: `CollectibleEntity` (has `listName`, `quantity`), `FunkoMetadataEntity` (1:1), `SeriesEntity` + `collectible_series` junction (M:N), `ValueSnapshotEntity`. `CollectibleWithDetails` `@Relation` POJO assembles the nested shape under `@Transaction`.
- **Importer**: `HobbyDbCsvImporter` parses the real HobbyDB export (commons-csv, RFC 4180, BOM, hyperlink unwrap, multi-value split). JVM-testable `parse(InputStream)`.
- **Grouping is by HobbyDB "List Name"** (not Series) — the Portfolio breakdown categories are Nft funko, Marvel, Starwars, Office, DC, etc.
- **Valuation counts duplicates.** Portfolio total and per-list totals use `SUM(estimatedValueCents * quantity)`. Verified numbers on the real 504-entry collection:
  - 504 unique entries; **662** incl. duplicates (Σ quantity); 121 entries have qty>1 (max 5).
  - Total: **$30,115** (with quantity) vs $26,884 (without). Matches HobbyDB's "$30,308 incl. duplicates" within rounding.
  - **Counts stay at entries (504); value includes copies** — same convention HobbyDB uses.
- `getMostValuable` and Detail/Category rows show **unit** value; Category rows show a `×N` `QuantityBadge`; Detail shows quantity in its "In your collection" row.

---

## 5. Current app surface (navigation)

`Router` (routes on `observeCount()`) → `Welcome`/`Import` (SAF `OpenDocument`) → **`Portfolio`** → `Category` → `Detail`. `Ask` is a `ModalBottomSheet` off Portfolio (not a route). Light + dark themes implemented via `LocalArcanaColors`.

---

## 6. Stubs / dead placeholders (Week-3 candidates — all need price data)

These render designed-but-inert cards today and are the most visible gaps:

- **Portfolio value-history sparkline** — needs a value time-series (weekly price sync).
- **Portfolio "◆ This week · on-device summary"** — needs price deltas to summarize on-device.
- **Detail "Median active listing" (eBay)** and **"Value history" 90-day chart** — need a `PriceProvider`.
- **"Sync now" / "Snapshot today's price" / "Edit details" / add/delete** buttons are no-ops.
- **`ValueSnapshotEntity`** exists and gets one import-time snapshot per item, but nothing writes ongoing snapshots yet — the schema is ready for a price-sync feature.

Other honest limitations:
- **Retrieval is lexical**: no synonyms/semantics ("dragons" ≠ "Daenerys"); large categories **undercount** (grounding capped at `RELEVANT_LIMIT=12`, so "how many Marvel?" over 97 entries can't be counted accurately); non-text filters (NFT flag, dates) aren't searchable. → Week-9 RAG.
- **Chat is not persisted** — `AskViewModel` state resets when the sheet/VM is recreated.
- **No benchmark aggregation surface** — `InferenceMetadata` is captured per call and shown per answer, but there's no p50/p95 sweep screen yet (cheap to add; "interview gold").
- **No confidence-based escalation** — routing is capability-based (606) not confidence-based; the plan's "escalate when Nano isn't confident" is unbuilt.
- FigPin/Pokemon categories are dimmed "Coming soon".

---

## 7. Verified device facts & benchmarks (Pixel 10 Pro XL, on real collection)

- Gemini Nano runs on-device (the earlier AICore **606** provisioning block was cleared by a Play update + reboot).
- `PREFER_ON_DEVICE` chose Nano **even with WiFi+LTE up** — privacy-by-default confirmed, not just a no-network fallback.
- Grounded ~40-word answer: **on-device** first-token ≈975 ms / total ≈3.7 s / `tokens=null`; **cloud** first-token=total ≈1.1 s / 39 tok. Cloud ~3× faster wall-clock (Nano trades latency for privacy/offline). **On-device does not populate `usageMetadata`** → `outputTokenCount` is null; cloud does.
- SDK facts (verified against the AARs): `InferenceMode`/`OnDeviceConfig`/`InferenceSource` live in `com.google.firebase.ai` (not `.type`); `GenerateContentResponse.inferenceSource` + `usageMetadata.candidatesTokenCount`; 606 type is `com.google.firebase.ai.type.FirebaseAIOnDeviceNotAvailableException`. Model build needs `@OptIn(PublicPreviewAPI::class)`.

---

## 8. Toolchain & environment (bleeding-edge — do not "fix" downward)

- **AGP 9.2.0** (built-in Kotlin 2.2.10), **Gradle 9.4.1**, **compileSdk 37**, minSdk 29, targetSdk 36, **JDK 21**.
- KSP `2.2.10-2.0.2`, Room 2.8.4, Hilt 2.59.2, Compose BOM 2026.06.00, Coil 3.5, Nav 2.9.8, firebase-ai 17.13.0 + firebase-ai-ondevice 16.0.0-beta03 (on-device is a separate beta artifact, **not** in the BoM). Tests: coroutines-test 1.10.2 + Turbine 1.2.1.
- Known workarounds already in place (see `app/build.gradle.kts` / `gradle.properties`): `-Xskip-metadata-version-check` + forced `kotlin-metadata-jvm:2.4.0` (Coil 3.5 ships Kotlin 2.4 metadata); `android.disallowKotlinSourceSets=false` (KSP + AGP-9 built-in Kotlin).
- **CLI builds need** `JAVA_HOME="C:/Program Files/Android/Android Studio1/jbr"` (bare `gradlew` fails with a jvm.cfg error after the AS update).
- **Git Bash mangles `/sdcard//data` paths** — prefix adb commands with `MSYS_NO_PATHCONV=1`.
- Device: `adb -s 57130DLCQ000ZJ`. Deploy loop used all session: `:app:installDebug` then `adb shell monkey -p com.aashishgodambe.arcana -c android.intent.category.LAUNCHER 1`.

---

## 9. Security / privacy constraints (must persist)

- `app/google-services.json` — **gitignored** (Firebase project/app ids + client key; repo is headed public).
- `seed-data/*.raw.csv` — **gitignored** (personal data: storage location, private notes, price paid, dates). Only the **sanitized** `seed-data/collectibles_2026-07-03.csv` is committed and is the test fixture.
- `notes/INTERVIEW_PREP.md` — **gitignored**, cumulative, updated each week (has a Wk2-D5 section on the hybrid AI story). Never commit.
- Git identity is **personal** (`Aashish Godambe` / `12780079+aashishg11@users.noreply.github.com`), set repo-local — not the work account.
- Commit messages: **no "Day N" prefix**; end with `Co-Authored-By: Claude Opus 4.8 <noreply@anthropic.com>`.

---

## 10. Test status

- JVM (`:app:testDebugUnitTest`): `HobbyDbCsvImporterTest` (5), `AskViewModelTest` (2 — grounded streaming success + error path, using `FakeGeminiService` + Turbine + coroutines-test), `ExampleUnitTest` (1).
- Instrumented (`:app:connectedDebugAndroidTest`, needs device): `ArcanaDatabaseTest` (3), `HobbyDbImportIntegrationTest` (1 — real sanitized CSV asset), `ExampleInstrumentedTest` (1).
- `FakeGeminiService` + fake repositories make AI/ViewModel code testable without a device — extend this pattern for Week 3.

---

## 11. Candidate Week-3 directions (with mapping)

Per `ARCANA_CONTEXT.md` the nominal Week 3 is "hybrid escalate Nano→cloud + text-recognition stub"; `DESIGN.md` frames Weeks 3–4 around price sync. Given what's already built, the highest-leverage options:

1. **Price sync (`PriceProvider`)** — biggest *visible* payoff; lights up the sparkline, weekly on-device summary, and detail market card; delivers the "value-over-time on a volatile portfolio" story. Start with a mock provider writing `ValueSnapshotEntity`, then eBay. (~Weeks 3–4 in DESIGN.)
2. **Benchmark surface + confidence escalation** — cheap (metadata already flows); adds a p50/p95 on-device-vs-cloud sweep screen ("interview gold") and confidence-based Nano→cloud routing. Closes the plan's Week 3–4 hybrid/benchmark items.
3. **RAG upgrade for Ask** — replace the lexical retrieval with LiteRT on-device embeddings; fixes the undercount/synonym limits directly. Week-9 feature pulled forward.
4. **Capture flow (camera → identify)** — largest new surface (Camera + Capture Review wireframes, ML Kit + Nano image description); ~Weeks 6–7 effort.

Reference docs in-repo: `ARCANA_CONTEXT.md` (roadmap table, hardware, gotchas), `DESIGN.md` (interfaces, provider seams, error handling), `SCREENS.md`, `arcana-wireframes.html` (8 annotated frames — **design source of truth**), `WEEK_02_PLAN.md`.
