# Week 4 — Completion Summary (handoff for Week 5+)

Status: **complete, shipped, and pushed** (branch `main`).
Week-4 commits (on `main`, newest first):
- `9c2cb1e` — Write README v1 with measured benchmark numbers and demo media (Day 5)
- `c1624c1` — Add mock price jitter and a FallbackCollectionSummarizer unit test (Day 4 loose ends)
- `9dc7bc9` — Add Settings screen with a working background-sync toggle and theme (Day 4)
- `23a3b2b` — Add benchmark screen with an on-device readiness gate (Day 3)
- `4c0df56` — Add on-device vs cloud benchmark harness with p50/p95 aggregation (Days 1–2)

All JVM unit tests green; every screen verified on a physical **Pixel 10 Pro XL** (Tensor G5, Gemini Nano on-device). This document states what exists, what's stubbed, the verified facts (several hard-won), and the entry points — so later weeks can be scoped without re-reading the tree.

Week 4 was **Track A: prove the on-device rigor** — no new `ai-samples` capability, deliberately. It buys *depth* on the two shipped capabilities: the benchmark is the difference between "I used Gemini Nano" and "I measured Nano's latency distribution against cloud and can show the tradeoff."

---

## 1. What Week 4 delivered (vs. the plan)

`WEEK_04_PLAN.md` scoped the benchmark surface, a Settings screen, README v1, and two cheap loose ends. **All shipped:**

- **In-app benchmark** — a `BenchmarkHarness` that reuses the `GeminiService` seam, forces on-device vs cloud via `RoutingHint`, aggregates to p50/p95, and renders on a designed screen with live per-cell progress and cold-start called out distinctly.
- **On-device readiness gate** — a `DeviceCapabilityChecker` seam over the Firebase on-device provisioning API; the sweep runs cloud-only (never a column of 606s) when Nano isn't provisioned, and can trigger a model download.
- **Settings screen** — a **working** weekly-worker enable/disable toggle (schedules/cancels the real `WeeklyPriceSyncWorker`), an on-device AI status readout, a live System/Light/Dark theme, and About.
- **Loose ends** — `MockPriceProvider` per-sync jitter (non-zero deltas on "Sync now"); `FallbackCollectionSummarizer` unit-tested after a qualifier-based DI refactor.
- **README v1** — real p50/p95 numbers, the honest tradeoff narrative, an on-device "Ask Arcana" GIF as the lead visual, plus benchmark/settings screenshots. Structured so the Week-7 capture cascade drops in as the hero with no rewrite.
- **Cloud-token frugality pass** (mid-week correction) — the cloud column runs fewer iterations than on-device and paces calls, to respect the scarce free-tier budget.

Not done, on purpose (unchanged deferrals): multi-module split, real `EbayBrowsePriceProvider`, Play Store track, `genai-writing-assistance`.

---

## 2. Architecture added

New seams and packages (single `:app` module, package-by-feature):

```
core/ai/
  benchmark/    BenchmarkHarness (drives GeminiService, sequential, cold-once-per-process),
                BenchmarkEngine (RoutingHint-forced columns), BenchmarkPrompt(+Prompts),
                BenchmarkSample, BenchmarkProgress, BenchmarkResult, Percentiles (type-7),
                BenchmarkAggregator
  capability/   DeviceCapabilityChecker (+ FirebaseDeviceCapabilityChecker) — wraps the
                on-device checkStatus()/download() provisioning API behind ModelReadiness
  summary/di/   SummaryQualifiers (@MlKitEngine / @GeminiEngine)
core/data/
  settings/     SettingsStore (SharedPreferences; weeklyWorkerEnabled + ThemeMode StateFlows)
  worker/       WeeklyPriceSyncScheduler (schedule/cancel, extracted from ArcanaApplication)
feature/
  benchmark/    BenchmarkViewModel, BenchmarkScreen
  settings/     SettingsViewModel, SettingsScreen
```

**Layering discipline held:** features depend on the `DeviceCapabilityChecker` seam, never Firebase on-device types; the summarizer composite now depends on the `CollectionSummarizer` interface (qualifier-injected), not concrete engines. `ArcanaApplication` respects the persisted toggle on launch.

---

## 3. The benchmark (Week-4 centerpiece)

**Reuses the exact seam the app uses.** `BenchmarkEngine.{OnDevice,Cloud}` map to `RoutingHint.{OnlyOnDevice,OnlyCloud}`, so a future `ExecuTorchGeminiService` (Gemma 3 1B, Week 7) slots in as another column with no screen change. The result model and screen already accept N engines.

- **Sequential by construction** — every call is fully awaited before the next (engine-outer), so the harness never races the `HybridGeminiService` `Mutex` (no BUSY). Verified `maxConcurrent == 1` in tests.
- **Cold is once per process** — the harness (a `@Singleton`) tracks attempted engines, so the first on-device call in the process is the one honest cold sample; re-running in the same process is all warm (verified on device: a "Run again" showed no cold chip).
- **Aggregation** — p50/p95 (type-7 / linear interpolation) over *warm* first-token and total per (engine × prompt); the single cold call is kept aside; errored calls (e.g. cloud rate-limits) are excluded from percentiles and surfaced as an `errorCount`.
- **Null tokens are honest** — on-device never reports `outputTokenCount`; the UI renders "n/a", never 0. Cloud populates it.
- **Readiness gate** — before the sweep, `checkStatus()`; if Nano isn't `Available`, the sweep is cloud-only and the screen offers "download on-device model".

**Measured numbers (Pixel 10 Pro XL, this week):**

| Prompt | Engine | First-token p50 | Total p50 | Tokens |
|---|---|--:|--:|--:|
| Short | On-device (Nano) | ~397 ms | ~3.38 s | n/a |
| Grounded | On-device (Nano) | ~439 ms | ~2.70 s | n/a |
| Short | Cloud (2.5 Flash-Lite) | ~0.8–1.0 s | ~0.8–1.0 s | ~30 |
| Grounded | Cloud (2.5 Flash-Lite) | ~0.61 s | ~0.61 s | ~18 |
| Short (cold) | On-device (Nano) | ~0.8 s | ~4.1–5.7 s | n/a |

**The tradeoff, measured:** on-device reaches the **first token faster than cloud** (no network hop) but generates the full answer **~3–4× slower** (Nano decodes ~36 tok/s locally). This is the curve the README leads with. *Small-N (~4–8 warm) — indicative, not production-grade statistics; labeled as such in-app and in the README.*

---

## 4. Settings & data

- **Worker toggle is real** — `SettingsStore.weeklyWorkerEnabled` drives `WeeklyPriceSyncScheduler.schedule()/cancel()`. Verified via `dumpsys jobscheduler`: OFF → the `WeeklyPriceSyncWorker` job is gone, ON → it's back.
- **AI status readout** — `DeviceCapabilityChecker.onDeviceReadiness()` → "Ready / Downloading… / Not available — using cloud", with a "Re-check".
- **Theme** — `ThemeMode.{System,Light,Dark}` persisted and applied at `MainActivity` (both palettes already existed); verified the whole app flips live to the Light palette.
- **Mock jitter** — `MockPriceProvider` multiplies the deterministic model value by a small non-seeded factor (roughly −2%…+3%) per fetch, so consecutive "Sync now" snapshots differ. The `MockPriceModel` (shared with the history seeder) stays deterministic — jitter lives only in the provider's reported "now".
- **Navigation** — the ⚙ icon now opens Settings, which hosts the Benchmark entry (`Portfolio → Settings → Benchmark`).

---

## 5. Current app surface

`Router → Welcome/Import → Portfolio → Category → Detail`; `Ask` is a `ModalBottomSheet` off Portfolio; **⚙ → Settings → Benchmark**.
- **Benchmark:** Run → live progress (per-cell count + current engine/prompt badge) → grouped results (on-device / cloud), first-token & total p50/p95, gold cold-start chip, "n/a"/token chips, rate-limited counts.
- **Settings:** weekly-sync toggle · on-device AI status · System/Light/Dark · Benchmark row · About (version, GitHub, licenses).

---

## 6. Stubs / deliberately deferred

- **Capture cascade** (camera → segmentation → OCR → catalog → cloud) and **ExecuTorch/Gemma 3 1B** — Week 7; the benchmark and `GeminiService` seam are already shaped to receive them.
- **Real `EbayBrowsePriceProvider`**, **multi-module split** — still deferred (boundaries continue to hold).
- **Clean cloud benchmark numbers in one sitting** — blocked by the free-tier daily cap this session (see §7); the harness handles it honestly (partial cells, `errorCount`). Capture cloud reference numbers early next session or use a paid key.
- **More demo GIFs** (chat beyond the one captured, summary) — quick to grab now that the collection is restored; only the on-device Ask GIF shipped in v1.

---

## 7. Verified device facts (Pixel 10 Pro XL) — several hard-won

- **On-device Nano un-provisions across system updates.** Nano that streamed on-device on 07-07 was cloud-falling-back on 07-08. `PREFER_ON_DEVICE` masks it (silently uses cloud); `ONLY_ON_DEVICE` throws `FirebaseAIOnDeviceNotAvailableException`. **A reboot did NOT fix it.** ML Kit GenAI summarization kept running on-device the whole time — its AICore model is provisioned separately from the Firebase-AI base Nano.
- **Recovery is `FirebaseAIOnDevice.download()`** (a Kotlin `object` — call it directly, not `.INSTANCE`). `checkStatus(): {UNAVAILABLE, DOWNLOADABLE, DOWNLOADING, AVAILABLE}`; `download(): Flow<DownloadStatus>`. Provisioning **and** inference are foreground-gated (drive from a foreground scope / `ActivityScenario` RESUMED in tests). The download took ~8 min over Wi-Fi; `DownloadCompleted` may not emit before `checkStatus()` already reports `AVAILABLE`, so poll status, don't await the terminal event. `DownloadStatus.DownloadFailed.exception` is an interop type NOT on the compile classpath — log `$status`, don't reference `.exception`'s type.
- **Cloud free tier has a per-minute (20 req/min) AND a daily cap.** A repeated sweep exhausted the minute window, then a fresh sweep with a clean window exhausted the daily budget (all 16 cloud calls failed). Under quota pressure the SDK's retry/backoff **inflates "successful" cloud latency** (saw ~9 s vs ~0.7 s clean) — so the harness both paces and runs fewer cloud iterations. On-device (local) has no quota.
- **AICore build** `aicore_20260528`, **bootloader green** (`verifiedbootstate`), unchanged.
- **On-device Ask, populated collection:** first-token 937 ms, total 3484 ms (grounded, streamed on-device) — the README's lead GIF.
- **DB flakiness:** the imported 504-item collection went empty mid-session, then **auto-restored** (Android backup, `allowBackup="true"`) after a force-stop. Worth noting if demos show `$0`/0 items — a relaunch (or re-import via onboarding) restores it.

See memory notes: `firebase-ai-ondevice-api`, `ondevice-nano-blocked-606`, `cloud-free-tier-quota-20-per-min`, `conserve-cloud-tokens`.

---

## 8. Toolchain / build

- **No new dependencies.** Settings uses `SharedPreferences`; the About version reads `PackageManager` (no `BuildConfig`).
- `app/build.gradle.kts`: added `testOptions.unitTests.isReturnDefaultValues = true` so JVM tests can exercise main code that calls `android.util.Log` (the benchmark harness logs).

---

## 9. Test status

- **New JVM tests (all green):** `BenchmarkHarnessTest` (forcing, sequential `maxConcurrent==1`, cold-once-per-process, per-cell grouping, fewer cloud iterations, error handling, progress), `PercentilesTest` (type-7 median/p95 edges), `BenchmarkAggregatorTest` (warm p50/p95, cold aside, n/a tokens, error cells), `BenchmarkViewModelTest` (readiness gate → both engines vs cloud-only; download flips readiness), `FallbackCollectionSummarizerTest` (ML-Kit-primary → Gemini-fallback via qualifier fakes), `MockPriceProviderTest` (jitter varies, stays anchored).
- **New instrumented tests (run manually on device):** `BenchmarkHarnessDeviceTest` (real Nano vs cloud; forcing via `executedOn`; foreground via `ActivityScenario`), `OnDeviceModelProvisionTest` (checkStatus → download → AVAILABLE).

---

## 10. Candidate directions after Week 4

Per the roadmap, **Weeks 5–6 are curriculum** (quantization study; first ExecuTorch deploy) with **no Arcana code**. The next Arcana surfaces:
1. **Capture cascade + Gemma 3 1B (ExecuTorch)** — Week 7. The benchmark screen becomes the "Nano vs Gemma vs cloud" toggle (Week 8) by adding a `BenchmarkEngine`.
2. **On-device RAG** (LiteRT embeddings, semantic search) — Week 9.
3. **Eval harness** — Week 10 extends the same aggregation from latency to accuracy.
4. **Cheap follow-ups anytime:** refresh cloud benchmark numbers (fresh quota), capture chat/summary GIFs, real `EbayBrowsePriceProvider`, multi-module split.

Reference docs in-repo: `ARCANA_CONTEXT.md`, `DESIGN.md`, `WEEK_02_SUMMARY.md`, `WEEK_03_SUMMARY.md`, `WEEK_04_PLAN.md`, `arcana-wireframes.html` (design source of truth), `README.md`.
