# Arcana

**A privacy-first Android companion for serious collectors — powered by on-device AI.**

Arcana catalogs a collection of *anything* — Funko Pops, FigPins, trading cards, sneakers — and uses **on-device Gemini Nano** to do what cloud apps can't or won't: instant in-store identification, "chat with your collection," value tracking over time, and AI-assisted listing. Every inference attempts on-device first; the cloud is a fallback, never the default. Your collection's data stays on your device.

> **Status: 🚧 In active development (Week 2 of a 12-week build).** Built in the open as a portfolio piece demonstrating production-grade on-device AI on Android. See [Current status](#current-status) for exactly what works today.

---

## Why this project

- **Multimodal AI is central, not bolted on.** Every collectible is a photo of a thing with structured metadata (name, series, exclusivity, value) — a natural fit for on-device vision + language models.
- **Real data, not toy data.** Bootstrapped from a real 283-item HobbyDB export (~$22.6k estimated value, 60% digital/NFT). The importer has to survive real-world CSV quirks, not a clean fixture.
- **Privacy by construction.** The whole point is that inference happens on the device. Where each call executed (`OnDevice` vs `Cloud`) is captured as first-class telemetry, not an afterthought.

## Current status

**Week 2, Day 1 — the on-device inference path is proven on real hardware.** A throwaway spike streams a live response from **Gemini Nano running fully on-device** on a Pixel 10 Pro XL via Firebase AI Logic (`InferenceMode.ONLY_ON_DEVICE`), with first-token and total latency measured separately:

| | First-token | Total |
|---|---|---|
| Cold start | ~1.9 s | ~6.8 s |
| Warm | ~1.0 s | ~6.2 s |

The cold-start penalty lives almost entirely in time-to-first-token (model warmup) — which is why the telemetry keeps the two numbers separate from day one. The data layer, importer, and UI land over the following weeks (see [Roadmap](#roadmap)).

## Architecture

The interesting part is a **confidence-based cascade** for identification that short-circuits early and only reaches the cloud when on-device confidence is too low: on-device segmentation → on-device classification (Gemini Nano) → OCR → local collection lookup → cloud fallback (Gemini multimodal). Each step has a confidence gate.

All model access sits behind one honest abstraction, so features never touch Firebase (or ML Kit, or ExecuTorch) types directly — swapping the backend is a DI binding change:

```kotlin
interface GeminiService {
    fun generateText(prompt: String, routingHint: RoutingHint = RoutingHint.Auto): Flow<InferenceResult>
}

data class InferenceMetadata(
    val executedOn: InferenceLocation,     // OnDevice | Cloud — captured on every call
    val totalLatencyMs: Long,
    val firstTokenLatencyMs: Long?,
    val outputTokenCount: Int?,
)
```

The same pattern applies to five pluggable interfaces (`GeminiService`, `CollectibleRepository`, `CollectionImporter`, `CatalogProvider`, `PriceProvider`). See [DESIGN.md](DESIGN.md) for the full architecture and [SCREENS.md](SCREENS.md) for the screen/state model.

## Tech stack

- **Android:** Kotlin, Jetpack Compose, Material 3, Hilt, Room, Coroutines/Flow
- **On-device AI (now):** Firebase AI Logic — hybrid inference (`firebase-ai` + `firebase-ai-ondevice`), Gemini Nano via AICore
- **On-device AI (roadmap):** ML Kit GenAI APIs, LiteRT for on-device RAG embeddings, ExecuTorch (Gemma 3 1B) as a same-interface backend swap
- **Testing:** JUnit, Turbine, MockK, Compose UI test; `androidx.benchmark` for latency

## Requirements & setup

**Build:** Android Studio with AGP 9.2 / Gradle 9.4.1 / JDK 17, `compileSdk 37`.

**On-device inference:** a physical device with AICore + Gemini Nano (Pixel 9/10 series). The Nano model is provisioned through **Google Play system updates** — on first run AICore may report the model as unavailable until it finishes downloading. Emulators won't run the on-device path.

**Firebase config (bring your own):** `google-services.json` is intentionally **not committed**. To build:
1. Create a Firebase project and add an Android app with package `com.aashishgodambe.arcana`.
2. Enable **Firebase AI Logic** (Gemini Developer API provider).
3. Drop the downloaded `google-services.json` into `app/`.

## Roadmap

| Week | Milestone |
|---|---|
| 2 | CSV import → Room → Compose grid of 283 items → tap to detail → Gemini Nano answers "what's my most valuable item?" streamed on-device |
| 3 | Firebase hybrid escalation: when Nano isn't confident, fall back to Gemini multimodal cloud |
| 4 | Confidence cascade, streaming UX, on-device/cloud debug badge, p50/p95 latency benchmarks |
| 7 | Swap Gemini Nano for a self-deployed **Gemma 3 1B (ExecuTorch)** behind the same `GeminiService` interface |
| 9 | On-device RAG: embed each item (LiteRT), semantic search over the collection |
| 10 | Eval harness: on-device vs cloud vs fine-tuned identification accuracy |

## About

Built by **Aashish Godambe** ([@aashishg11](https://github.com/aashishg11)) — Senior Android Engineer focused on on-device AI. Companion project: [Ansa Aura](https://github.com/aashishg11/ansa-aura), a private family AI home platform (edge AI/systems, complementary to Arcana's mobile-first focus).
