# Arcana — Week 7 Build Plan

*Following Week 6's decision week. The first **shipping** week since Week 4 — the payoff where the whole ExecuTorch/LiteRT arc becomes something a recruiter can touch. Commit to repo root as `WEEK_07_PLAN.md`.*

*Assumes the Week 6 state in `WEEK_06_SUMMARY.md` — LiteRT Gemma 3 1B q4 runs on the Pixel via a self-built runtime (27.4 tok/s / 1077 MB, CPU config; TPU #7787 and PowerVR GPU are measured dead ends), the ship decision is **Nano default + own-model as a user-selectable showcase engine**, and the benchmark screen + `GeminiService` seam are already N-engine.*

---

## The week's goal, in one sentence

Wire your self-built **LiteRT Gemma (q4 CPU)** into the app behind the existing `GeminiService` seam, expose a **Settings engine picker** (Nano / your Gemma / cloud) that switches Ask Arcana's engine live, add the **third benchmark column**, and **capture the demo** — because for a side-loaded showcase engine, the captured demo *is* the artifact.

This makes the "swappable AI backends behind one interface" abstraction — held since Week 2 — into a visible, interactive feature. Nano stays the production default (zero app-resident memory); the own-model is a real, working, user-selectable engine that proves the *producer* capability.

## The framing that keeps this a one-week build

The own-model is a **demo/showcase engine, not a production feature.** That decision (last conversation) is what makes Week 7 tractable: it cuts the entire download-pipeline / hosting / licensing problem. The model is **side-loaded via adb**; the app checks presence and gates the picker accordingly. Build the *integration* to full rigor (clean seam, real test, benchmark wiring); take the shortcut *only* on model delivery.

---

## Scope discipline — what Week 7 is and isn't

**In, on purpose:**
- `LiteRtGeminiService` (q4 **CPU** config) behind the `GeminiService` seam, reading a **side-loaded** model.
- Settings **engine picker** (Nano default / your Gemma / cloud), **presence-gated**, routing Ask Arcana live.
- Benchmark screen **third column** (Nano vs LiteRT-Gemma vs cloud).
- **Demo capture** (picker→Gemma→stream GIF/video) + README three-engine update.

**Out, deliberately — not gaps:**
- **Download pipeline / model hosting** → side-load instead. No WorkManager download, no progress UI, no GitHub/HF hosting, no Gemma-redistribution question. (Parked as an optional *separately-named* learning spike — not product scope.)
- **TPU and GPU LiteRT paths** → measured dead ends on G5 (#7787, PowerVR). CPU config only.
- **Own-model as forced default** → Nano defaults; the 1077 MB in-process footprint must not be paid by every user.
- **App-wide engine switching** → the picker scopes to the **`GeminiService`-backed Ask Arcana**. The `genai-summarization` card stays on its own `CollectionSummarizer` (ML Kit) path — different seam, left alone.
- **ExecuTorch RSS-chase** (done, benchmark-only), **Samsung purchase** (parked → Week 8+), **stop-criteria fix** (LiteRT stops at EOS — nothing carries).

---

## Day 1 — De-risk the Android integration surface (the real unknown)

Week 6 ran **standalone CLI binaries** (`litert_lm_main`) pushed to `/data/local/tmp`. An *app* needs a **library API called from Kotlin** — that's the gap, and it's the week's genuine unknown. Resolve it before building anything on top.

**The question:** how do you invoke LiteRT-LM Gemma from Kotlin?
- **MediaPipe Tasks `LlmInference`** (`com.google.mediapipe:tasks-genai`) — the established Kotlin on-device-LLM API (model path + streaming callback). Verify it accepts a `.litertlm` on the **CPU** backend. If yes, this is the low-friction path and the native `.so`s come via the AAR.
- **JNI bridge to the self-built LiteRT-LM engine library** — matches exactly what you benchmarked, full control, but you write the JNI and manage the `.so`s.
- **A LiteRT-LM AAR / Maven artifact**, if one exists at v0.14.0.

**Also nail model delivery + native packaging:**
- **Side-load target:** `adb push …/gemma3-1b-it-int4.litertlm /sdcard/Android/data/com.aashishgodambe.arcana/files/models/`. `getExternalFilesDir("models")` is **adb-writable without root and app-owned** — cleaner than `filesDir` (adb can't write it) or the `/data/local/tmp` chmod dance.
- **Native `.so`s:** bundled in the APK's `jniLibs` (Apache-2.0, small) — *only the model* is side-loaded (big, Gemma-gated). The **CPU config needs the fewest native libs** (no `google_tensor` dispatch, no ClGl accelerator — XNNPACK is built in): another reason CPU is the right ship config.

**Definition of done**
- [ ] A throwaway in-app (Compose harness) call generates a token from Gemma q4 CPU via the chosen path — or a documented wall.
- [ ] Integration surface decided (MediaPipe `LlmInference` vs JNI vs AAR), with the reason.
- [ ] Side-load path (`getExternalFilesDir`) + native-lib packaging confirmed.

---

## Day 2 — `LiteRtGeminiService` behind the seam

**Tasks**
- `LiteRtGeminiService : GeminiService`, q4 CPU. Initialize the runtime against the side-loaded model path.
- **Presence check:** model absent → the service reports *unavailable* cleanly (no crash), which the picker reads.
- Map the runtime's streaming into your `InferenceResult` flow (`Streaming` / `Success(fullText, metadata)` / `Error`). Populate `InferenceMetadata` — `executedOn` = a new on-device-LiteRT location, first-token + total latency separated. Verify what token counts LiteRT exposes in-app; render honestly (n/a if absent, like Nano).
- `Mutex` if the runtime is single-inference at a time.
- `FakeLiteRtGeminiService` (or extend the existing fake pattern) for tests.

**Definition of done**
- [ ] `LiteRtGeminiService` streams a grounded answer in-app when the model is present.
- [ ] Reports unavailable cleanly when the model is absent.
- [ ] `InferenceResult` + `InferenceMetadata` populated (token counts honest).
- [ ] Unit test against the fake passes.

---

## Day 3 — The engine picker (the visible feature)

**Tasks**
- Settings → **inference engine**: Nano (default) / **Your Gemma (LiteRT)** / Cloud. Persist via `SettingsStore`.
- **Presence-gating:** the Gemma row is selectable when the model is present; **greyed with "developer feature — model side-loaded"** when absent (a clean empty state, not a disabled mystery).
- **Routing:** a `DelegatingGeminiService` (injected into `AskViewModel`) reads the persisted selection and routes: Nano → `HybridGeminiService` (`PreferOnDevice`); Cloud → `HybridGeminiService` (`OnlyCloud`); Gemma → `LiteRtGeminiService`. Ask Arcana's calls flow through it.

**Definition of done**
- [ ] The picker switches Ask Arcana's engine live; selection persists across launches.
- [ ] Gemma gated on model presence with the explanatory empty state.
- [ ] Selecting Gemma → Ask Arcana streams from LiteRT with the on-device badge.

---

## Day 4 — Benchmark third column + badge

**Tasks**
- Add a `BenchmarkEngine` entry mapping to `LiteRtGeminiService` — the axis is already list-driven, so this is a new column, **no screen rewrite**. The sweep now compares **Nano vs LiteRT-Gemma vs cloud**, all measured live with the Week-5/6 methodology already in the harness.
- `InferenceBadge` distinguishes the three engines.
- Preserve token-count honesty in the benchmark aggregation for the LiteRT column (whatever it does/doesn't report).

**Definition of done**
- [ ] Benchmark screen runs a **3-engine** sweep (Nano / LiteRT / cloud) with p50/p95.
- [ ] LiteRT column populated (gated on model presence); badge distinguishes engines.

---

## Day 5 — Demo capture + README + tests

For a side-loaded engine, **the captured demo is the artifact** — a recruiter can't clone-and-run it (needs the side-loaded model + native libs), so the proof is media, and that makes capture a real deliverable, not an afterthought.

**Tasks**
- Capture: a GIF/video of the **picker switching to Your Gemma and Ask Arcana streaming on-device**; the **3-engine benchmark screen** with real numbers.
- README: the three-engine narrative — **Nano ships** (zero-footprint, the measured reason), **ExecuTorch + LiteRT as the *producer* capability**, the measured comparison, and the **#7787 / PowerVR cross-silicon findings** as the systems story (this is the strongest interview material — a negative result reached by measurement).
- One meaningful test per new ViewModel (picker/settings routing).

**Definition of done**
- [ ] Demo GIF/video (picker→Gemma→stream) + benchmark-screen media captured.
- [ ] README updated with the three-engine story + benchmark table + the cross-silicon dead-end findings.
- [ ] Tests green; `WEEK_07_SUMMARY.md` written.

---

## Weekend / buffer + parked enrichments

- Polish the picker empty state and the on-device-LiteRT badge treatment.
- **Parked (captured, not built this week):**
  - **Samsung/Snapdragon cross-vendor benchmark** → Week 8+. Hexagon is the most mature NPU path in the ecosystem, so the same model that falls back to CPU on Tensor may get genuine NPU decode on a Snapdragon 8 Elite — a strong cross-silicon column. A *conscious purchase* evaluated as portfolio enrichment once the core is done; device-independent from this week's wiring.
  - **Download-pipeline learning spike** → a separately-named exercise (Play Asset Delivery / Firebase model download) *if* the model-delivery skill interests you. Explicitly **not** product scope — practice named as practice.

---

## Week 7 — Definition of Done (the single checklist)

- [ ] Android LiteRT integration surface decided; in-app Gemma token generation proven
- [ ] `LiteRtGeminiService` (q4 CPU, side-loaded, presence-gated) behind the seam; fake-backed test green
- [ ] Engine picker switches Ask Arcana live, persists, gates Gemma on presence
- [ ] Benchmark screen has the LiteRT third column (Nano / LiteRT / cloud, p50/p95)
- [ ] Demo GIF/video captured; README three-engine story + cross-silicon findings
- [ ] `WEEK_07_SUMMARY.md` written; pushed per repo conventions

---

## Gotchas to watch

- **CLI binary ≠ app library.** Week 6's standalone-binary approach does not transfer; the Kotlin integration surface (MediaPipe `LlmInference` vs JNI vs AAR) is the Day-1 unknown — don't assume it's free.
- **Side-load to `getExternalFilesDir("models")`**, not `filesDir` (adb can't write it) and not the `/data/local/tmp` chmod dance (dev-hacky). App-owned, adb-writable without root.
- **Bundle the native `.so`s, side-load only the model.** `.so`s are Apache-2.0 and small; the model is the big, gated file. CPU config = fewest `.so`s.
- **Presence-gating is a designed empty state,** not a crash or a silent disabled row — "developer feature — model side-loaded."
- **Token-count honesty** carries — verify what LiteRT populates in-app and render n/a if absent, exactly like Nano.
- **Don't let the own-model become the default** — Nano defaults; the picker is opt-in.
- **The demo is the deliverable** for this engine — budget Day 5 for capture; clone-and-run won't reproduce it.

## What Week 8 inherits

A wired, benchmarked **three-engine** app with the toggle already live — which means the roadmap's Week-8 "toggle between Nano vs Gemma vs cloud in-app" is **done early**. That frees Week 8 to be the **cross-vendor benchmark** (the parked Snapdragon story — "Qualcomm AI Hub variant" in the roadmap), where the same LiteRT engine you wired this week gets measured on Hexagon to see whether the accelerator finally engages where Tensor's didn't. The `GeminiService` seam and N-engine benchmark screen carry that with no rewrite — a new device, a new column.
