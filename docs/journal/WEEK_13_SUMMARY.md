# Week 13 — Completion Summary (Snapdragon / Hexagon NPU benchmark)

Status: **complete.** The last parked item in the project — the **Snapdragon NPU benchmark** — is done. A
Galaxy S26 (Snapdragon 8 Elite Gen 5) arrived, so the benchmark that had been stated as a *bounded open
question* since Week 6 is now a **measured result**. No app feature work: this is a hardware/measurement week,
in the spirit of Week 11.

**The headline:** *Arcana's own-model engine now has a real NPU number. On the Galaxy S26's Hexagon (Arch
V81), the self-hosted LiteRT-LM stack runs **Gemma-4-E2B (a 2B-class model) at 44.5 tok/s decode / 293 tok/s
prefill** on the NPU — **~1.7× the decode (and ~9× the prefill) of the same model on CPU** (26.0 tok/s), and
above our Week-6 CPU floor (27.4 on the Pixel, 34.4 on this S26's CPU) while running a **2× larger, newer
model**. It even edges the published S26 reference (41.7 tok/s). And the No-#2 **memory wall** is measured, not
guessed: ~2 GB resident on both backends, but the unreclaimable heap drops ~1.1 GB → ~0.85 GB CPU→NPU. Getting there disproved the easy assumption: **on-device JIT compilation does not
work** for this model (976 subgraphs exhaust the NSP), so the win is an **AOT precompiled context** — and the
surprise is that the **sm8750 (Hexagon V79) context runs forward-compatibly on the S26's V81**. The whole
toolchain (runner + dispatch + compiler plugin) was built from source; the entire path is **gate-free** (the
QAIRT "Community" SDK is a public download). Measured on the S26 (`RFGL52XFZMZ`, `SM8850`).*

---

## 1. What was measured

Two devices are now in play: the Pixel 10 Pro XL (`57130DLCQ000ZJ`, Tensor G5 — the old baseline) and the new
**Galaxy S26 (`RFGL52XFZMZ`, `ro.soc.model=SM8850`, Snapdragon 8 Elite Gen 5, Hexagon Arch V81, Android 16)**.

- **S26 CPU/Nano baseline** — a device-free probe of the two existing on-device engines on Snapdragon silicon,
  to establish the floor the NPU has to beat. Added `OnDeviceVsOwnModelBaselineTest` (Nano + LiteRT-CPU only,
  no cloud), driving the real `DelegatingGeminiService` seam. **Committed + pushed (`f6b341e`).**
- **S26 NPU spike** — a standalone-runner spike (in WSL `/root/npu-spike/`, mirroring the Week-6 methodology)
  proving the Hexagon NPU path end-to-end: build the LiteRT-LM NPU toolchain from source, push to the device,
  run `--backend=npu`, read tok/s. **Not in the app repo** — it's a benchmark, like the Week-6 ExecuTorch/LiteRT
  spikes.

## 2. The numbers (real, on the S26)

| Engine | Model | Decode | Prefill | Notes |
| :--- | :--- | :--- | :--- | :--- |
| Nano (AICore) | Gemini Nano | — (n/a) | — | warm total ~1.1–1.7 s, first-token ~100–135 ms; **Nano provisions on Samsung** |
| LiteRT **CPU** | Gemma-3-**1B** q4 | **34.4 tok/s**\* | — | our floor; beats the Pixel's Week-6 27.4 (~25% faster silicon) |
| CPU (XNNPACK) | Gemma-4-**E2B** (2B) | 26.0 tok/s | 33 tok/s | same 2B model, clean `--backend=cpu` |
| **NPU (Hexagon V81)** | Gemma-4-**E2B** (2B) | **44.47 tok/s** | **293.03 tok/s** | AOT context; the result |
| *reference (published S26)* | Gemma-4-E2B | *41.7* | — | we're slightly above it |

\* The LiteRT-CPU 34.4 (row 2) is the **1B** model, end-to-end (incl. prefill); rows 3–4 are the **same 2B
model, CPU vs NPU**, so *that* pair is the clean comparison: NPU is **~1.7× the decode and ~9× the prefill**.
(An earlier "9.85 tok/s / 4.5×" figure came from a **broken JIT-fallback** run, not a clean CPU baseline —
corrected here to the real `--backend=cpu` number, 26.0.)

**Memory (the No-#2 wall, finally measured).** Peak resident memory is **~2 GB on both** backends — the NPU
doesn't shrink the footprint. But the *shape* differs: the unreclaimable **anonymous heap drops ~1.1 GB (CPU)
→ ~0.85 GB (NPU)**, because the AOT path **memory-maps** its weights (clean, reclaimable pages) instead of
dequantizing the embedding into fp32 heap. So the NPU is faster **and** lighter on the axis that matters — but
it still can't reach Nano's ~0 app-resident memory (AICore keeps Nano in a separate process). The wall is
**lowered, not removed.**

## 3. Key decisions (each surfaced)

- **Standalone runner, not the app AAR, for the spike.** Route A (`litert_lm_main` pushed to `/data/local/tmp`)
  is isolated from the app and mirrors the proven Week-6 method. Route B (the `litertlm-android` AAR) would drag
  AAR/coroutine/AGP-9 integration risk into a throwaway measurement — and per [LiteRT #6889] it *still* needs a
  bazel-built Qualcomm dispatch `.so`. Route B is Phase B (see §7).
- **Gemma-4-E2B, not our Gemma-3-1B, for the NPU column.** Gemma-3-1B has an NPU prefill bug on Qualcomm
  ([LiteRT-LM #3508]); Gemma-4 runs cleanly and is the S26-tested model. The NPU column running a *bigger, newer*
  model than the CPU column is a stronger story, not a weaker comparison.
- **AOT, not JIT** (forced by measurement — see §5). On-device JIT is a dead end here.
- **The sm8750 (V79) AOT artifact on the V81 device** — there is **no published sm8850 artifact** (only
  `_qualcomm_sm8750` and `_qcs8275`). The V79 context ran forward-compatibly on V81, with the QAIRT SDK
  version matched (both 2.49.0). This sidesteps building an sm8850-native AOT compile for the spike.
- **QAIRT 2.49.0 "Community" SDK** — bazel's `third_party/qairt/workspace.bzl` fetches it from a **public**
  `softwarecenter.qualcomm.com` URL (no Qualcomm account/NDA gate; confirmed by probe). Combined with the Gemma
  license already accepted, the whole spike is gate-free.

## 4. The engineering trail (built from source)

LiteRT-LM v0.16.0 (the S26-proven release), `@litert` pin `0ff2811`, bazel 7.6.1, NDK r28b, QAIRT 2.49.0 via
`LITERT_QAIRT_SDK`. Three bazel `android_arm64` artifacts:

- `//runtime/engine:litert_lm_main` — the runner (39.5 MB; compiles `llm_litert_npu_compiled_model_executor.cc`).
- `@litert//litert/vendors/qualcomm/dispatch:dispatch_api_so` → `libLiteRtDispatch_Qualcomm.so` (runtime execution).
- `@litert//litert/vendors/qualcomm/compiler:qnn_compiler_plugin_so` → `libLiteRtCompilerPlugin_Qualcomm.so`
  (needed by the JIT attempt; harmless for the AOT run).

Push layout: runner + both `.so` + `prebuilt/android_arm64/libGemmaModelConstraintProvider.so` + QAIRT V81 libs
(`lib/aarch64-android/libQnn*.so` + `lib/hexagon-v81/unsigned/*Skel*.so`) + model → `/data/local/tmp/npu-push`;
run `LD_LIBRARY_PATH=$D ADSP_LIBRARY_PATH=$D ./litert_lm_main --backend=npu --model_path=… --benchmark`.

## 5. The honest reframe: JIT fails, AOT wins

The obvious path — point `--backend=npu` at the generic model and let it JIT-compile for V81 on-device —
**fails**. The model has **976 subgraphs**; the compiler plugin loads, the HTP backend comes up, and *hundreds*
of graphs compile successfully, but around graph ~346 the NSP runs out of mappable memory
(`Failed to map weights buffer on NSP, err 4`, `QnnDsp Error 6020`) and it aborts. That is exactly why the
ecosystem ships **pre-compiled per-SoC AOT contexts** rather than JIT-compiling large LLMs on device. Switching
to the AOT artifact — where the QNN context binary is already prepared and memory-planned — the run is clean:
6 NPU context loads, 0 XNNPACK partition replacements, only 2 tiny `Gather` (embedding) ops fall back to CPU.
**Reading "NPU-capable" is not the same as "JIT-compilable on device"** — the same shape of lesson as Week 11's
"reading the number isn't resolving the identity."

## 6. Gotchas worth carrying forward

- **On-device JIT of a 2B LLM exhausts the NSP** (976 subgraphs). Use an **AOT** `.litertlm`; don't rely on
  `--backend=npu` JIT-compiling a generic model.
- **No sm8850 AOT artifact is published** — but the **sm8750 (V79) context runs on the S26 (V81)** as long as
  the QAIRT runtime version matches the context's ([LiteRT-LM #2226] is a *version* mismatch, not an arch one).
- **Flat push-dir arch collision:** `lib/hexagon-v81/unsigned/` also contains a **32-bit DSP** `libQnnSystem.so`;
  bulk-copying that dir over the 64-bit `aarch64-android` libs yields `"…is 32-bit instead of 64-bit"` and every
  dispatch init fails. Copy **only** `*Skel*` from the hexagon dir.
- `litert_lm_main` needs `libGemmaModelConstraintProvider.so` pushed too (it's a `DT_NEEDED`, in
  `prebuilt/android_arm64/`). Read the binary's `DT_NEEDED` up front instead of playing whack-a-mole.
- **`bash script.sh` in WSL is a non-login shell** → bazelisk isn't on `PATH`; export `/root/.local/bin`.
  And (as always) **drive WSL from `.sh` files, never nested `wsl -lc "…$VAR…"`** — the quoting eats variables.
- **QAIRT is a public "Community" download**; the Hexagon Arch for SM8850 is **V81** (per QAIRT's own
  `QAIRT_SDK.md`), so the libs are `libQnnHtpV81*.so` + `hexagon-v81/unsigned/libQnnHtpV81Skel.so`.

## 7. What's next (if pursued)

- **Phase B — put it in the app.** A 4th `BenchmarkEngine` / `AskEngine` column ("Your Gemma · NPU") via the
  `com.google.ai.edge.litertlm:litertlm-android` AAR (`Backend.NPU()`), shipping the QNN libs + dispatch as
  jniLibs / a per-SoC feature module (the AAR doesn't bundle them — [LiteRT #6889]). Watch the AAR's
  coroutine-version conflict ([LiteRT-LM #2812]) against the AGP-9 build. This turns the CLI number into a live,
  user-selectable engine and a benchmark row — the existing `RoutingHint` seam already anticipates it.
- **An sm8850-native AOT compile** could squeeze a little more than the forward-compatible V79 context, but the
  V79-on-V81 result already proves the thesis.
- **Write-up & apply** — this is strong portfolio/interview material: a from-source NPU toolchain, a measured
  JIT→AOT pivot, and a forward-compat discovery, all on shipping Snapdragon silicon.

## 8. Files & artifacts

- **Committed (app repo):** `OnDeviceVsOwnModelBaselineTest` (the S26 CPU/Nano baseline), commit **`f6b341e`**,
  pushed to `origin/main`.
- **Not committed (WSL spike, `/root/npu-spike/`):** LiteRT-LM v0.16.0 checkout + the three bazel artifacts,
  the extracted QAIRT 2.49.0 SDK, and the models (`gemma-4-E2B-it.litertlm` generic/JIT 2.59 GB;
  `gemma-4-E2B-it_qualcomm_sm8750.litertlm` AOT 3.02 GB, from `litert-community/gemma-4-E2B-it-litert-lm`).
- **On device (`/data/local/tmp/npu-push/`):** the runner + libs + both models (safe to prune).

## 9. Reading order

`WEEK_13_SUMMARY.md` (this) → repo `CLAUDE.md` → `docs/journal/WEEK_06_SUMMARY.md` (the LiteRT-LM CPU/ExecuTorch
lineage this NPU number completes) → `docs/journal/WEEK_11_SUMMARY.md` (the measurement-week template this
follows).
