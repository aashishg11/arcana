# Arcana

**A privacy-first Android companion for serious collectors — powered by on-device AI.**

Arcana catalogs a collection of *anything* — Funko Pops, FigPins, trading cards, sneakers — and uses **on-device Gemini Nano** to do what cloud apps can't or won't: "chat with your collection," value tracking over time, weekly AI summaries, and (soon) instant in-store identification. Every inference attempts on-device first; the cloud is a fallback, never the default. Your collection's data stays on your device.

> **Status: 🚧 In active development (Week 7 of a 12-week build).** Built in the open as a portfolio piece demonstrating production-grade on-device AI on Android. See [What works today](#what-works-today) for exactly what runs, and the [benchmark](#benchmark-three-engines-measured) for measured latency across three engines.

<p align="center">
  <img src="docs/media/ask-ondevice.gif" width="300" alt="Ask Arcana streaming a grounded answer on-device from Gemini Nano" />
  &nbsp;&nbsp;
  <img src="docs/media/ask-yourgemma.gif" width="300" alt="Ask Arcana streaming from a self-deployed Gemma 3 1B on-device" />
</p>

<p align="center"><em>"Ask Arcana" answering the same grounded question two ways — streamed token-by-token from <strong>Gemini Nano</strong> (left) and from <strong>my own self-quantized Gemma 3 1B</strong> running in-process via LiteRT (right). The engine is a live, user-selectable choice; the badge flips iris→gold to show which one served.</em></p>

---

## Why this project

- **Privacy by construction.** The whole point is that inference happens on the device. Where each call executed (`OnDevice` vs `Cloud`) is captured as first-class telemetry, read straight from the SDK — not an afterthought, and not guessed.
- **Real data, not toy data.** Bootstrapped from a real ~500-item HobbyDB export (~$29k tracked value, 60% digital/NFT). The importer survives real-world CSV quirks (`=HYPERLINK()` wrappers, comma-separated multi-value fields, leading-zero UPCs), not a clean fixture.
- **Measurement rigor.** On-device isn't a checkbox — it's measured. The in-app [benchmark](#benchmark-three-engines-measured) sweeps Nano vs my own Gemma vs cloud and reports p50/p95 first-token and total latency, cold vs warm, forced onto each engine through the same interface the app uses.

## What works today

Weeks 1–7, all verified on a physical **Pixel 10 Pro XL** (Tensor G5, Gemini Nano on-device):

- **Import → portfolio.** HobbyDB CSV → Room → a value-first portfolio home: tracked total, week-over-week delta, a live sparkline, and a duplicate-aware per-list breakdown.
- **"Ask Arcana," on three engines.** A grounded chat over your collection that **streams token-by-token on-device**, with a badge showing where each answer ran. The engine is a **live, user-selectable choice** — Gemini Nano (default), **my own self-deployed Gemma 3 1B**, or cloud.
- **My own model, shipped.** A self-quantized **Gemma 3 1B (INT4)** runs in-process on the device via **LiteRT / MediaPipe LLM Inference** (CPU), behind the exact same `GeminiService` interface as Nano and cloud. Pick it in Settings → Ask Arcana streams from it with a gold "Your Gemma" badge. (See [Bringing my own model on-device](#bringing-my-own-model-on-device).)
- **On-device weekly summary.** A "what moved this week" card generated on-device via **ML Kit GenAI Summarization** (with a Gemini Nano fallback), narrating your own tracked price deltas — never an external feed.
- **Value tracking.** A `PriceProvider` seam (mock stand-in for eBay Browse) writing a `ValueSnapshot` time-series; per-item 90-day charts; a weekly background sync worker.
- **Three-engine benchmark.** Tap *Run benchmark* → live per-cell progress → a designed p50/p95 results surface comparing **Nano vs my Gemma vs cloud**, first-token vs total, cold-start called out separately.
- **Settings.** The engine picker, a working background-sync toggle, on-device AI readiness readout, live light/dark theme.

Hybrid inference is real: under `PREFER_ON_DEVICE` the SDK runs Nano on-device and transparently falls back to cloud (`gemini-2.5-flash-lite`) when the model isn't provisioned — and the app reports which one actually served, per call.

<p align="center">
  <img src="docs/media/engine-picker.png" width="300" alt="Settings engine picker: Gemini Nano, Your Gemma, or Cloud" />
</p>
<p align="center"><em>The engine picker in Settings — Nano stays the default (zero app-resident memory); "Your Gemma" is opt-in and presence-gated on the side-loaded model.</em></p>

## Bringing my own model on-device

Nano is Google's model. The harder question a portfolio should answer is: **can *you* take an open model, quantize it yourself, and ship it on-device behind the same interface?** Arcana does — and the interesting part is what the measurement said.

I self-quantized **Gemma 3 1B to INT4** and deployed it two ways — first with **ExecuTorch** (Week 5), then evaluated Google's **LiteRT-LM** (Week 6) — and benchmarked both against Nano on the Pixel's **Tensor G5**. The verdict is a **negative result reached by measurement**, which is the whole story:

- **The TPU is a dead end for decode.** The Tensor G5 NPU *does* engage (the Google-Tensor dispatch delegate loads and claims partitions), then fails with `contradictory buffer requirements` / `InvalidArgument` and falls back to CPU — [LiteRT #7787](https://github.com/google-ai-edge/LiteRT/issues/7787), reproduced **in-app** through the shipping MediaPipe AAR, not just my from-source build.
- **The GPU is a dead end too.** The G5's GPU is an Imagination **PowerVR**, and LiteRT disables GPU weight-prep for PowerVR/Mali/Broadcom — decode collapses to ~6 tok/s.
- **So the win came from the boring layer.** LiteRT's **CPU** path (XNNPACK, INT4) beats my ExecuTorch build on *both* axes — **27.4 tok/s / 1077 MB** vs 19.9 tok/s / 1477 MB — because its per-layer-embedder design keeps the embedding from materializing to fp32. "Use the vendor runtime on the vendor's chip" was right, for an unglamorous reason. Two independent toolchains (my Bazel build and Google's AAR) reach the identical CPU-only conclusion.

**The ship decision:** Nano stays the **default** — it runs out-of-process in AICore, so it costs **zero app-resident memory**; a permanently-resident 1 GB own-model shouldn't be forced on every user. The own-model is a **user-selectable** engine, proving the *producer* capability without paying its footprint by default. Model delivery is a dev **side-load** (the ~584 MB INT4 file isn't bundled in the APK); the picker presence-gates the option accordingly.

The payoff for the architecture: this was a **full rewrite of the inference layer** — native runtime, a streaming listener bridged to a `Flow`, a single-inference `Mutex`, side-load lifecycle — that dropped in behind the **unchanged** `GeminiService` interface. The call sites, the benchmark, and the badge didn't move. That's the abstraction earning its keep.

## Benchmark: three engines, measured

Measured through the in-app benchmark on the Pixel 10 Pro XL — the same `GeminiService` seam that powers the app, forced onto each engine via `RoutingHint`. p50/p95 over warm samples (small N — *indicative, not production-grade statistics*); cold-start is the first call in the process, reported separately.

| Prompt | Engine | First-token (p50) | Total (p50) | Output tokens |
|---|---|--:|--:|--:|
| Grounded | **Nano** (on-device) | **0.44 s** | 2.70 s | n/a¹ |
| Grounded | **Your Gemma** (LiteRT INT4, CPU) | 1.86 s | 3.02 s | 28 |
| Grounded | **Cloud** (2.5 Flash-Lite) | 0.45 s | 0.49 s | 17 |
| Short | **Nano** (on-device) | **0.39 s** | 3.35 s | n/a¹ |
| Short | **Your Gemma** (LiteRT INT4, CPU) | 0.58 s | 1.88 s | 31 |
| Short | **Cloud** (2.5 Flash-Lite) | 0.80 s | 0.80 s | 28 |

**What the numbers say:** there's no free lunch. **Nano** gives the fastest first token (no network, TPU prefill) but can't report token counts. **Cloud** wins wall-clock total when the network is good. **My Gemma** is the honest middle — competitive decode, real token counts, and a first-token that scales with prompt length because CPU prefill is linear in tokens (0.58 s short → 1.86 s grounded). It's the *engineering* answer, not the fastest number: my own model, running privately, on a phone. Shipping the benchmark — rather than asserting "it's fast" — is the point.

¹ Nano never reports token counts (a Firebase-AI on-device limitation); the UI renders "n/a", never a misleading 0. My Gemma (`sizeInTokens`) and cloud both do. Your Gemma's one-time ~3 s model load is amortized once per process and excluded from these figures.

| Engine picker → live switch | Three-engine results | On-device answer |
|---|---|---|
| ![Settings engine picker](docs/media/engine-picker.png) | ![p50/p95 results across Nano, Your Gemma, Cloud](docs/media/benchmark-3engine.png) | ![Ask Arcana answering from Your Gemma with the gold badge](docs/media/ask-yourgemma.png) |

## Architecture

All model access sits behind one honest abstraction, so features never touch Firebase (or ML Kit, or ExecuTorch) types directly — swapping the backend is a DI binding change, not a call-site rewrite:

```kotlin
interface GeminiService {
    fun generateText(prompt: String, routingHint: RoutingHint = RoutingHint.Auto): Flow<InferenceResult>
}

data class InferenceMetadata(
    val executedOn: InferenceLocation,     // OnDevice | OnDeviceOwnModel | Cloud — read from the SDK/runtime per call
    val totalLatencyMs: Long,
    val firstTokenLatencyMs: Long?,        // kept separate — Nano's cold start lives here
    val outputTokenCount: Int?,
)
```

The abstraction has now been proven the hard way: three concrete engines live behind that one interface — `HybridGeminiService` (Nano + cloud), `LiteRtGeminiService` (my self-quantized Gemma via the LiteRT/MediaPipe runtime), and a `DelegatingGeminiService` that routes to whichever the Settings picker selected. The picker, the benchmark's third column, and the badge's third colour all fell out of that one seam **with no call-site changes**. The same pattern applies to five pluggable interfaces (`GeminiService`, `CollectibleRepository`, `CollectionImporter`, `CatalogProvider`, `PriceProvider`) and one sealed domain model (`Collectible`).

The identification centerpiece (roadmap) is a **confidence-based cascade** that short-circuits early and only reaches the cloud when on-device confidence is too low: on-device segmentation → on-device classification (Nano) → OCR → local collection lookup → cloud fallback (Gemini multimodal). See [DESIGN.md](DESIGN.md) for the full architecture and [SCREENS.md](SCREENS.md) for the screen/state model.

## Tech stack

- **Android:** Kotlin, Jetpack Compose, Material 3, Hilt, Room, Coroutines/Flow, WorkManager, Coil
- **On-device AI (now):** Firebase AI Logic hybrid inference (`firebase-ai` + `firebase-ai-ondevice`), Gemini Nano via AICore; ML Kit GenAI Summarization; a self-quantized **Gemma 3 1B (INT4)** via **LiteRT / MediaPipe LLM Inference** (`tasks-genai`), CPU, as a same-interface engine
- **On-device AI (roadmap):** LiteRT for on-device RAG embeddings (Week 9); cross-vendor NPU benchmark (Snapdragon / Hexagon)
- **Testing:** JUnit, Turbine, MockK, Compose UI test; a device benchmark harness for latency

## Requirements & setup

**Build:** Android Studio with AGP 9.2 / Gradle 9.4.1 / JDK 21, `compileSdk 37`. From the CLI, prefix the JDK: `JAVA_HOME=".../Android Studio/jbr" ./gradlew :app:installDebug`.

**On-device inference:** a physical device with AICore + Gemini Nano (Pixel 9/10 series; a Tensor-G5 Pixel 10 for Nano v3). The Nano model is provisioned through Google Play system updates and can un-provision across updates — the app checks readiness and can trigger a re-download. Emulators won't run the on-device path.

**Firebase config (bring your own):** `google-services.json` is intentionally **not committed**. To build:
1. Create a Firebase project and add an Android app with package `com.aashishgodambe.arcana`.
2. Enable **Firebase AI Logic** (Gemini Developer API provider).
3. Drop the downloaded `google-services.json` into `app/`.

## Roadmap

| Week | Milestone | Status |
|---|---|---|
| 1–2 | CSV import → Room → portfolio grid → "Ask Arcana" answers streamed on-device | ✅ |
| 3 | Firebase hybrid escalation; on-device weekly summary (ML Kit GenAI) | ✅ |
| 4 | p50/p95 on-device-vs-cloud benchmark screen, Settings, README | ✅ |
| 5–7 | Self-quantize & deploy **Gemma 3 1B** on-device (ExecuTorch → LiteRT); measure across the Tensor G5's TPU/GPU/CPU; user-selectable engine picker; three-engine benchmark | ✅ |
| 8 | Cross-vendor NPU benchmark — the same LiteRT engine on a Snapdragon/Hexagon device | ◻︎ |
| 9 | On-device RAG: embed each item (LiteRT), semantic search over the collection | ◻︎ |
| 10 | Eval harness: on-device vs cloud vs fine-tuned identification accuracy | ◻︎ |
| 11 | Capture cascade (camera → segmentation → OCR → catalog → cloud fallback) | ◻︎ |

## About

Built by **Aashish Godambe** ([@aashishg11](https://github.com/aashishg11)) — Senior Android Engineer focused on on-device AI. Companion project: [Ansa Aura](https://github.com/aashishg11/ansa-aura), a private family AI home platform (edge AI/systems, complementary to Arcana's mobile-first focus).
