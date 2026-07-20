# Week 7 — Completion Summary (handoff for Week 8+)

Status: **complete.** The first **shipping** week since Week 4 — the payoff where the whole
ExecuTorch/LiteRT arc becomes something a recruiter can touch.

**The headline:** *My self-quantized Gemma 3 1B now runs inside the app as a **user-selectable** engine,
behind the unchanged `GeminiService` seam. Settings → Ask Arcana engine = Nano (default) / **Your Gemma
(LiteRT INT4, CPU)** / cloud, switching live. The benchmark screen gained a third column. And the in-app
runtime independently reproduced the Week-6 silicon verdict — the Tensor G5's TPU (#7787) and PowerVR GPU
are dead ends, CPU is the only path — so "use the vendor runtime on CPU" is now evidenced twice.*

---

## 1. What shipped (per the Week-7 plan)

Every Definition-of-Done item met, verified on the Pixel 10 Pro XL.

- **Day 1 — integration surface decided + proven.** MediaPipe **LLM Inference** (`com.google.mediapipe:
  tasks-genai:0.10.35`), CPU, loading the **exact `.litertlm`** I benchmarked in Week 6. Rejected JNI (the
  AAR loads my model) and a LiteRT-LM app AAR (none exists — the runtime ships *through* tasks-genai). A
  dev smoke test generated "Paris" in-app; native logs showed pure XNNPACK CPU + the #7787/PowerVR walls.
- **Day 2 — `LiteRtGeminiService` behind the seam.** `OwnModelEngine : GeminiService` (+ `isModelAvailable`);
  streaming via `generateResponseAsync(prompt, ProgressListener)` → `InferenceResult`; new
  `InferenceLocation.OnDeviceOwnModel` (gold "Your Gemma" badge); honest token counts (`sizeInTokens`);
  single-inference `Mutex`; presence = file exists **and readable**. `FakeOwnModelEngine` + test.
- **Day 3 — the engine picker (the visible feature).** `RoutingHint.OnlyOwnModel` + `DelegatingGeminiService`
  (routes `Auto` by the persisted `SettingsStore.askEngine`; explicit hints bypass it). Settings picker
  (Nano/Your Gemma/Cloud), presence-gated. Ask Arcana switches engine live; selection persists.
  `DelegatingGeminiServiceTest` locks the routing truth table.
- **Day 4 — benchmark third column.** `BenchmarkEngine.OwnModel`; the harness drives it through the same
  seam. Gated on availability; badge distinguishes engines; token honesty preserved. Measured live.
- **Day 5 — demo + README + summary.** GIF of Ask Arcana streaming from Your Gemma; picker + 3-engine
  benchmark stills; README rewritten around the three-engine + cross-silicon story; this summary.

**Out (as planned):** no download pipeline (side-load), no TPU/GPU paths (measured dead ends), no
own-model-as-default, no capture cascade (moved to the roadmap tail).

## 2. The numbers — 3-engine sweep (Pixel 10 Pro XL, warm p50/p95, live; 40 samples, 0 errored)

| engine | prompt | first-token p50/p95 | total p50/p95 | tokens | cold |
|---|---|--:|--:|--:|--|
| **Nano** | grounded | **437 / 475 ms** | 2.70 / 2.74 s | n/a | — |
| **Your Gemma** (LiteRT q4 CPU) | grounded | 1.86 / 1.90 s | 3.02 / 3.08 s | 28 | — |
| **Cloud** | grounded | 453 / 478 ms | **490 / 536 ms** | 17 | — |
| **Nano** | short | 388 / 400 ms | 3.35 / 3.37 s | n/a | 3.88 s |
| **Your Gemma** | short | 584 / 599 ms | 1.88 / 1.92 s | 31 | 1.96 s |
| **Cloud** | short | 797 ms / 1.23 s | 799 ms / 1.88 s | 28 | 1.41 s |

- No free lunch: **Nano** fastest first-token (TPU prefill, no network) but can't report tokens; **cloud**
  wins wall-clock total on a good network; **Your Gemma** is the honest middle — real tokens, first-token
  scales with prompt length (CPU prefill is linear: 584 ms short → 1.86 s grounded).
- Ask Arcana's *real* answer (74 tok) took ~6.6–6.9 s on Your Gemma — longer than the benchmark's grounded
  prompt (28 tok) because total is dominated by output length.
- **Caveat:** Your Gemma's one-time ~3 s model load happens **before** the timed region (engine init) and is
  excluded from these figures — amortized once per process, like Nano's provisioning. A cold-including-load
  number is a possible future refinement.

## 3. Prediction scorecard (pre-registered, per the working style)

- "MediaPipe loads the q4 `.litertlm` directly on CPU, no JNI, no `.task`" → **right.**
- "in-app decode within ~15% of Week-6's 27.4 tok/s" → **not directly tested** (blocking/streaming smoke
  proved tokens generate; benchmark measures latency, not steady-state tok/s).
- "own-model first-token low seconds incl. ~3 s load" → **wrong**: 842 ms — load is excluded by design.
- "own-model total highest on grounded (~6–7 s), Nano lowest" → **half right**: own-model *was* the highest
  on-device total (3.02 s), but the ~6–7 s magnitude was wrong (prompt shape) and **cloud** was lowest, not
  Nano. The misses were the diagnostic value.

## 4. Bug found + fixed (during Day-3 verification)

The Ask-sheet footer showed "cloud" for own-model answers — an `else`-based string map
(`if location=="OnDevice" ... else "cloud"`) that a new enum value silently fell through. Fixed to an
exhaustive `when` on `InferenceLocation`. The badge was already exhaustive, which is why it showed gold
while the caption lied. **Lesson carried:** prefer exhaustive `when` over `else`-string maps — only the
former breaks at compile time when an enum grows.

## 5. Files & artifacts

- **New:** `LiteRtGeminiService`, `OwnModelEngine`, `FakeOwnModelEngine`, `DelegatingGeminiService`,
  `AskEngine`, `InferenceLocation.OnDeviceOwnModel`, `RoutingHint.OnlyOwnModel`, `BenchmarkEngine.OwnModel`.
- **Wired:** `AiModule` (Delegating over Hybrid + own-model + `SettingsStore`), `SettingsStore.askEngine`,
  `SettingsViewModel` (picker state), `SettingsScreen` (picker UI + debug smoke card), `BenchmarkViewModel`
  (availability-gated column), `BenchmarkScreen` (`badgeLocation()` + copy), `AskSheet` (footer fix), DESIGN.md.
- **Tests:** `FakeOwnModelEngineTest`, `DelegatingGeminiServiceTest`, `BenchmarkViewModelTest` (+own-model
  case), harness tests pinned to an explicit engine set. Full suite green.
- **Deps:** `com.google.mediapipe:tasks-genai:0.10.35`; `buildConfig = true`.
- **Media:** `docs/media/{ask-yourgemma.gif, ask-yourgemma.png, engine-picker.png, benchmark-3engine.png}`.
- **Model (gitignored / never committed):** `gemma3-1b-it-int4.litertlm` (584 MB) side-loaded to
  `getExternalFilesDir("models")`. Re-side-load: `adb shell 'cp /data/local/tmp/w6/gemma3-1b-it-int4.litertlm
  /sdcard/Android/data/com.aashishgodambe.arcana/files/models/; chmod 644 …/models/*.litertlm'`.
  **Gotcha:** an adb-copied file is `shell`-owned mode 640 → the app gets `PERMISSION_DENIED`; the `chmod 644`
  is required, and the presence check treats unreadable as absent.
- **Notes (gitignored):** `notes/week07/DAY_0{1..4}_*.md` — surface decision, service, picker, benchmark.

## 6. Gotchas worth carrying forward

- **tasks-genai 0.10.35 removed `setResultListener`** from the options builder; the per-call
  `generateResponseAsync(String, ProgressListener)` is the streaming surface. `javap` the AAR to confirm a
  churny API rather than trusting docs/tutorials.
- **MediaPipe LLM Inference is in maintenance mode** (Google steering to a direct LiteRT-LM API). It's still
  the correct shipping surface today and how litert-community distributes this model; a future migration.
- Device housekeeping during verification: bumped `screen_off_timeout` to 600000 and temporarily cleared the
  a11y magnifier FAB (restored to `MagnificationController`) + `svc power stayon` (cleared). The timeout was
  left at 600000 (original unknown).

## 7. What Week 8 inherits

A wired, benchmarked **three-engine** app with the toggle already live — so the roadmap's "toggle between
Nano/Gemma/cloud in-app" is **done early**. That frees **Week 8 for the cross-vendor benchmark**: run the
*same* `LiteRtGeminiService` on a Snapdragon 8 Elite / Hexagon device to see whether the accelerator engages
where Tensor's didn't. The `GeminiService` seam and N-engine benchmark screen carry it with no rewrite — a
new device, a new column.

## 8. Reading order for Week 8

`WEEK_07_SUMMARY.md` (this) → `notes/week07/DAY_04_BENCHMARK.md` (the 3-engine numbers + method) →
`DAY_01_SURFACE.md` (the MediaPipe/side-load decision + gotchas) → `WEEK_06_SUMMARY.md` (the cross-silicon
findings this builds on) → `DESIGN.md` (`GeminiService` / `OwnModelEngine` / `DelegatingGeminiService`).
