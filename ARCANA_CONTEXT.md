# Arcana — Project Context
*Paste this into a new Claude conversation to pick up exactly where we left off. Commit to repo root as `ARCANA_CONTEXT.md` — same pattern as Ansa Aura uses `ANSA_AURA_CONTEXT.md`.*

---

## What is Arcana?

**Arcana** (`com.aashishgodambe.arcana`) is a privacy-first Android companion app for serious collectors of *any* category — Funko Pops, FigPins, trading cards, sneakers, board games, whatever you collect. The pitch: bootstrap from an existing collection (via HobbyDB CSV import), then use on-device AI to do things cloud apps can't or won't — instant identification in stores, "chat with my collection" via on-device RAG, value tracking over time, NFT redemption state tracking, and AI-assisted listing/condition assessment.

**The name:** *arcana* means specialized secret knowledge — exactly what the app gives you about your own collection. "Ask Arcana" is the natural in-app phrase for the chat feature. Privacy story lives in the name: arcane knowledge stays within a circle. Your collection's arcana stays on your device.

**Positioning:** Portfolio piece built as part of a senior Android engineer's pivot to "Senior Android Engineer + On-Device AI Specialist." Target outcome: a working app on a Pixel 10 Pro XL with rigorous benchmarks across Gemini Nano generations, blog posts, and a coherent architecture story that lands Applied AI Engineer / ML Engineer (Mobile) interviews at $200K+ remote.

**Ship status:** Undecided — may stay personal, may go to Play Store. Package name `com.aashishgodambe.arcana` is final. Studio/domain decision deferred until actual publish decision.

**Goal app, not goal codebase.** Arcana should be something Aashish actually uses — he's a real Funko collector with 283 cataloged items in a real HobbyDB export.

---

## Why this domain works

1. **Visual + structured metadata.** Every collectible is a photo of a thing with known structured fields (name, series, number, exclusivity, vaulted status, value). Multimodal AI is central, not bolted on.
2. **Real dataset.** Aashish has a 283-item HobbyDB export, $22,634 estimated value. Most portfolio projects use toy data. His isn't.
3. **Generic from day one.** Funko Pops are the seed data, but the architecture supports any collectible category. FigPin, sneakers, cards — same AI pipeline, different metadata templates.
4. **Shareable to a real community.** r/funkopop, r/figpin, HobbyDB Squad. Can get real users, which is a portfolio multiplier nothing else gives.

---

## Real seed data (HobbyDB export)

Exported 2026-06-19. File: `seed-data/collectibles_2026-06-19_02_50_05__0000.csv` — commit this.

**Shape:** 283 distinct database items, 321 total items (some Quantity > 1), $22,634 estimated value. Median item $50. Max $690 (Daenerys with Egg, NFT Redeemable).

**100% populated columns:** HDBID, Name, Brand, UPC, Series, Image URL, Production Status, Item Condition, Quantity, Estimated Value, Packaging Condition, Date Added, HDBC Number.

**Mostly populated:** Release Date (99.6%), Exclusive To (85%), Scale (86%), List Name (97%).

**Sparse — the gaps Arcana fills going forward:** Price Paid (0%), Acquired From (0%), Date Purchased (10%), Storage Location (14%), Private Notes (4%), Description (0%).

**Critical data quirks for the importer:**
- `Database Item URL` and `Collectible URL` are wrapped in Excel `=HYPERLINK("...")` syntax — strip with regex
- `Series` is comma-separated multi-value (`"Pop! Vinyl, Pop! Digital, Marvel"`) — parse to many-to-many
- `Production Status` is also comma-separated (`"Exclusive, Limited Edition"`) — same pattern
- `Exclusive To` can be `NFT Redeemable` — treat as a meaningful category, not a retailer
- `Quantity` matters — schema needs a quantity column, not one-row-per-item
- `UPC` reads as int — but real UPCs have leading zeros; cast to string before import

---

## The plot twist: this collection is 60% digital

- **168 of 283 items** are tagged `Pop! Digital`
- **142 items** have `Exclusive To = NFT Redeemable`
- **Top 4 most valuable items are all NFT Redeemables** (Daenerys $690, Daemon Targaryen $450 ×2, Fire Nation Aang $540)
- **Collection activity** clusters in 2023 (170 items — NFT boom) and 2024 (69 items)

What this means: "point camera at shelf, identify Pops" is *less* central than initially assumed. More valuable: redemption state tracking for NFT Pops, value-over-time tracking on a volatile portfolio, RAG over collection metadata, and capture-time enrichment for *new* acquisitions.

---

## Feature priorities

| Priority | Feature | Why |
|---|---|---|
| **P0** | Import from HobbyDB CSV → Room DB | Bootstraps app with 283 real items on Day 1 |
| **P0** | "Ask Arcana" — chat with collection via on-device RAG | 283 items is perfect size for on-device embeddings |
| **P0** | Value-over-time tracking | Volatile NFT-heavy portfolio = daily snapshots matter |
| **P1** | NFT redemption state tracker | HobbyDB doesn't model this; uniquely Aashish's need |
| **P1** | Capture flow for new acquisitions (camera + AI identify) | For forward additions, not re-cataloging the existing 283 |
| **P1** | Gap-filling at capture (Price Paid, Acquired From, Storage Location) | Fixes the sparse-field problem in the seed data |
| **P2** | Duplicate / chase detection on capture | Multi-quantity items exist |
| **P2** | Series completion progress | "Pop! Star Wars: 32 in collection" |
| **P2** | Listing description writer (ML Kit GenAI Rewriting) | AI drafts listing when selling |

---

## Tech stack

**Core Android:**
- Kotlin, Jetpack Compose, Material 3
- Hilt for DI
- Room for local DB
- Coroutines + Flow
- Coil for image loading
- Turbine (Flow tests), MockK, JUnit + Compose UI test

**AI layer:**
- ML Kit GenAI APIs (Summarization, Rewriting, Image Description)
- Firebase AI Logic (Hybrid Inference API, `InferenceMode.PREFER_ON_DEVICE`)
- LiteRT for embeddings (on-device RAG)
- MediaPipe samples as reference
- ExecuTorch for custom model deployment (Project #2 — Gemma 3 1B quantized)
- ML Kit Subject Segmentation for camera capture
- ML Kit Text Recognition for box number OCR

**Target hardware:**
- **Pixel 10 Pro XL (owned).** Tensor G5 — the first Tensor chip to run Gemini Nano v3 fully on-device. This is the dev/benchmark device.
- **Model availability (verified June 2026):** The AICore Developer Preview on the Pixel 10 Pro XL exposes Gemini Nano 3 plus Nano 4 Fast and Nano 4 Full, and tracks per-prompt inference time natively. The Pixel 9 series (Tensor G4) is stuck on Nano v2 and cannot access v3 even in dev preview — so the originally-planned 9 Pro would have hit a real wall. The 10 Pro XL clears it.
- Nano 4 is preview-only and behind an AICore opt-in; treat it as a bonus benchmark axis (Nano 3 vs Nano 4 Fast vs Nano 4 Full), not a load-bearing dependency.
- Bonus: a Snapdragon 8 Elite / Gen 4 device for cross-vendor benchmark (Galaxy S24+/S25+ are Nano v2-class; pick a 2025–26 flagship if cross-vendor matters).

---

## Architecture (the cascade — the senior thing about this design)

Naive version: photo → cloud Gemini → display.

Arcana's version is a confidence-based cascade with early short-circuit:

1. **On-device segmentation** (ML Kit Subject Segmentation) — isolate item from background
2. **On-device classification** (Gemini Nano via ML Kit GenAI Image Description) — fast first-pass: "this looks like a Marvel character"
3. **On-device OCR** (ML Kit Text Recognition) — read box number/series text
4. **Local embedding lookup** (LiteRT) — match against collection vector store; instant recognition of owned items
5. **Cloud fallback** (Gemini multimodal via Firebase AI Logic) — only when on-device confidence too low
6. **Structured extraction** — return typed `CollectibleMetadata` object, not free text
7. **Value enrichment** — separate call to a price service (TBD)

Each step has a confidence threshold; cascade short-circuits early when possible. **This cascade is the architectural story for interviews.**

---

## Key abstraction (the file an interviewer will open first)

```kotlin
interface GeminiService {
    fun generateText(
        prompt: String,
        routingHint: RoutingHint = RoutingHint.Auto,
    ): Flow<InferenceResult>
}

sealed interface InferenceResult {
    data class Streaming(val partialText: String) : InferenceResult
    data class Success(val fullText: String, val metadata: InferenceMetadata) : InferenceResult
    data class Error(val cause: Throwable, val fallbackAvailable: Boolean) : InferenceResult
}

data class InferenceMetadata(
    val executedOn: InferenceLocation,
    val totalLatencyMs: Long,
    val firstTokenLatencyMs: Long?,
    val outputTokenCount: Int?,
)

enum class InferenceLocation { OnDevice, Cloud }
enum class RoutingHint { Auto, PreferOnDevice, OnlyOnDevice, OnlyCloud }
```

`HybridGeminiService` (Firebase Hybrid Inference impl) and `FakeGeminiService` (tests/previews) implement this. Features never touch Firebase types directly — swap to MediaPipe or ExecuTorch by changing the impl, not the features.

---

## Package structure

```
app/src/main/kotlin/com/aashishgodambe/arcana/
├── ArcanaApplication.kt             // @HiltAndroidApp
├── MainActivity.kt
├── ui/
│   ├── theme/                       // Material 3
│   ├── component/
│   │   ├── StreamingText.kt         // animates token-by-token
│   │   ├── InferenceBadge.kt        // debug: on-device vs cloud
│   │   └── ErrorState.kt
│   └── navigation/
├── core/
│   ├── ai/                          // THE DIFFERENTIATOR
│   │   ├── GeminiService.kt
│   │   ├── HybridGeminiService.kt
│   │   ├── FakeGeminiService.kt
│   │   ├── capability/DeviceCapabilityChecker.kt
│   │   ├── instrumentation/InferenceTelemetry.kt
│   │   ├── model/{InferenceResult, InferenceMetadata, RoutingHint, InferenceLocation}.kt
│   │   └── di/AiModule.kt
│   ├── data/
│   │   ├── repository/{CollectibleRepository, CollectibleRepositoryImpl}.kt
│   │   ├── database/{ArcanaDatabase, dao/, entity/}
│   │   ├── importer/HobbyDbCsvImporter.kt
│   │   └── di/DataModule.kt
│   ├── domain/
│   │   ├── model/Collectible.kt
│   │   └── usecase/{ChatWithCollection, SummarizeCollection, ImportFromHobbyDb}.kt
│   └── common/{Result.kt, DispatcherProvider.kt}
└── feature/
    ├── collection/                  // grid of all items
    ├── detail/                      // single-item view
    ├── chat/                        // "Ask Arcana"
    ├── capture/                     // camera → identify (Week 7+)
    └── value/                       // value over time chart
```

Mirror under `src/test/` (JVM) and `src/androidTest/` (instrumented). At least one meaningful test per ViewModel.

Start single-module, organized as if they were modules. Promote to multi-module in Week 4 polish.

---

## 12-Week timeline

| Week | Arcana deliverable |
|---|---|
| 2 | CSV import → Compose grid of 283 items → tap to detail → Gemini Nano answers one question ("what's my most valuable item?") streamed live |
| 3 | Firebase hybrid: when Nano isn't confident, escalate to Gemini multimodal cloud. ML Kit Text Recognition stub. |
| 4 | Polish: confidence cascade, streaming UX, debug badge, benchmarks (p50/p95 latency). README v1. Ship to Play Store internal track (optional). |
| 5 | Curriculum: quantization study (no Arcana code) |
| 6 | Curriculum: first ExecuTorch deploy on Pixel (no Arcana code) |
| 7 | Deploy Gemma 3 1B for "Ask Arcana" chat — powered by own model, not Gemini. **Project #2 killer feature.** |
| 8 | Qualcomm AI Hub variant; toggle between Nano vs Gemma vs cloud in-app. With Nano 3 / Nano 4 Fast / Nano 4 Full all available on the 10 Pro XL, the toggle can also compare Nano generations, not just on-device-vs-cloud. |
| 9 | On-device RAG: embed each item, semantic search ("find my horror Pops", "show me items over $200"). |
| 10 | Eval harness: 30 known items, measure identification accuracy on-device vs cloud vs fine-tuned. **The benchmark chart is interview gold.** |
| 11 | Blog posts (3): cascade architecture, on-device RAG for collectibles, Gemini Nano vs Gemma 3 benchmark. |
| 12 | Resume polish + apply + post in collector communities for real users |

---

## Samples to reference (don't fork — read and rebuild)

From `android/ai-samples`:
- **`gemini-hybrid`** — architectural reference for Week 2–4. Firebase Hybrid Inference with `InferenceMode.PREFER_ON_DEVICE`. Read line by line.
- **`genai-summarization`** — ML Kit GenAI Summarization API.
- **`genai-writing-assistance`** — ML Kit GenAI Rewriting API. For listing description writer.
- **`genai-image-description`** — ML Kit GenAI Image Description API. For capture flow.
- **`gemini-multimodal`** — cloud-side reference for hybrid multimodal calls.
- **`gemini-chatbot`** — pure cloud chat reference. Diff vs `gemini-hybrid` to see what hybrid adds.
- **`magic-selfie`** — ML Kit Subject Segmentation pattern.
- **`gemini-live-todo`** — voice-driven entry capture pattern (optional Week 4 polish).

From `android/nowinandroid` — architecture reference (modularization, repository pattern, Hilt, navigation, theming). Combine NIA architecture with `ai-samples` AI wiring patterns.

---

## Critical gotchas (will bite on Day 1 of Week 2)

1. **`ErrorCode 606 FEATURE_NOT_FOUND`** — on devices without Gemini Nano support. Ensure fallback fires.
2. **First-inference warm-up** — cold-start on Nano is 2–5 seconds even on the Pixel 10 Pro XL. Bake `InferenceMetadata` from Day 1 to distinguish first-call vs steady-state latency.
3. **BUSY errors** — AICore only runs one inference at a time. Queue or disable trigger UI mid-inference.
4. **Single-turn limitation** — on-device hybrid API is single-turn. Multi-turn chat history must be manually formatted into one prompt.
5. **Model availability ≠ feature availability** — even on the Pixel 10 Pro XL, first launch downloads the model in background. Calls fail until complete. Note Nano 3 is the mainstream model; Nano 4 variants require the AICore Developer Preview opt-in.

---

## What's already done

- HobbyDB CSV exported; analyzed (283 items, $22,634 value, 60% digital/NFT)
- App named **Arcana**, package `com.aashishgodambe.arcana`
- Resume updated — Arcana listed as Selected Project "In Development, 2026"
- Architecture designed; package structure defined
- `GeminiService` interface designed (see above)
- **Pixel 10 Pro XL acquired** — Tensor G5, Nano v3 + Nano 4 preview available. Hardware gate cleared.
- Planning conversations 1–4 complete: README.md, DESIGN.md, SCREENS.md, and annotated HTML wireframes (8 frames: 5 AI-showcase screens prioritized by `ai-samples` coverage, plus Camera, Onboarding Welcome, Import Progress).

## What's not started yet

- GitHub repo (`github.com/aashishg11/arcana`) — needs creation
- `WEEK_02_PLAN.md` — Conversation 5, the last planning artifact
- Code — nothing written yet

## Hard prerequisite for Week 2

**Cleared.** The Pixel 10 Pro XL is in hand and runs Nano v3 on-device. No remaining hardware blocker — the only thing between here and Week 2 is the Conversation 5 day-by-day plan. Per the planning budget, further planning beyond that is procrastination.

---

## About Aashish (calibration context)

- Senior Android Engineer, 10+ years. ReachMobi since 2020 (Senior Android Developer II since Mar 2025).
- Top-10 Personalization launcher on Google Play (US), 460K MAU, 310M monthly opens, ~99.5% crash-free.
- **Production on-device ML history:** shipped Firebase ML Kit Text Recognition (credit-card OCR) at Interactive Communications 2016–2020. This is the proof point that makes the pivot story credible.
- Strong: Kotlin, Compose, Hilt, Room, multi-process Android, custom IME, accessibility.
- Education: M.E. Electrical Engineering (Lamar University, GPA 4.0, Phi Kappa Phi). B.E. Instrumentation (Mumbai University).
- Based in NJ. Target: remote applied-AI roles, open to San Diego relocation.
- Active learning focus: Gemini Nano, ML Kit GenAI APIs, Firebase AI Logic, ExecuTorch, LiteRT, quantization, hybrid inference, Qualcomm AI Hub.
- Second portfolio piece: **Ansa Aura** — family private AI home platform (Home Assistant + Whisper + Piper + Anthropic Claude function calling + Android client). Public at `github.com/aashishg11/ansa-aura`. Arcana = mobile-first; Ansa Aura = edge AI/systems. Complementary, not redundant.

---

## Planning sequence for new threads

Five focused conversations before coding, each producing one committed artifact:

| Conversation | Output | Prompt to start with |
|---|---|---|
| 1 | `README.md` | "Help me write Arcana's top-level README. Reader is a recruiter or hiring manager with 60 seconds." |
| 2 | `DESIGN.md` | "Let's do the architecture deep-dive. Push back on tradeoffs. What would a principal engineer challenge?" |
| 3 | `SCREENS.md` | "Map every screen, every transition, every state as mermaid diagrams. Don't draw pixels yet." |
| 4 | Wireframe artifacts | "Generate annotated wireframes for the 5–7 core screens as HTML artifacts." |
| 5 | `WEEK_02_PLAN.md` | "Day-by-day breakdown for Week 2 with a definition-of-done checklist." |

**Budget 8 hours total across all five conversations.** If you go past that without writing Kotlin, you're procrastinating.

---

## Open questions to resolve in planning conversations

- HobbyDB API: does one exist? Falls back to CSV-only if not.
- Value data source in production: PPG API? Manual? Scraping?
- Collectible template system: how does Arcana support non-Funko categories? One schema vs. pluggable templates?
- NFT redemption state: what fields does this need? (redeemed: bool, redemption date, physical location, NFT wallet?)
- Play Store decision: personal tool vs. published app? Deferred until MVP ships.

---

## Repo URLs

- **Arcana:** `github.com/aashishg11/arcana` — does not exist yet. First commit: README, DESIGN, seed-data, LICENSE, .gitignore
- **Ansa Aura:** `github.com/aashishg11/ansa-aura` — public, has design docs, no Android code yet
