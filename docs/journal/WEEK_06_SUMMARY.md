# Week 6 — Completion Summary (handoff for Week 7+)

Status: **complete.** A decision week, exactly as scoped: **no Arcana app code shipped, main tree
untouched.** The deliverables are a working LiteRT-LM-on-Tensor spike, one measured comparison, and one
evidenced ship-engine decision.

**The headline:** *I built Google's LiteRT-LM from source, ran my Gemma 3 1B on the Pixel 10 Pro XL across
all three backends (TPU / GPU / CPU), and measured them against my Week-5 ExecuTorch build and Gemini
Nano. The verdict is counterintuitive and now evidenced: on Tensor G5, both of Google's accelerators are
dead ends for this model — the TPU is broken (LiteRT #7787), the GPU is a weight-prep-disabled PowerVR —
so LiteRT wins not through hardware but because its **CPU** implementation is ~38% faster and ~400 MB
lighter than ExecuTorch's. LiteRT q4 on CPU (27.4 tok/s, 1077 MB) is the shippable own-model engine.*

---

## 1. What the week delivered (vs. `WEEK_06_PLAN.md`)

Every Definition-of-Done item is met.

- [x] **Day-1 gate resolved with evidence** — TPU path available (G5 is the SDK flagship, prebuilt +
  ungated for Pixel 10), *and* GPU fallback available. Toolchain proven end-to-end on desktop.
- [x] **Gemma 3 1B runs on the Pixel via LiteRT** — all three backends, from a self-built
  `litert_lm_main` / `litert_lm_advanced_main` (Bazel 7.6.1 + NDK r28b, `android_arm64`).
- [x] **tok/s + peak RSS measured with Week-5 rigor** — warm n=3 ×2 orders, 90 s cooldowns, fresh process
  per RSS reading, thermal-drift check. Directly comparable to the ExecuTorch figures.
- [x] **`<end_of_turn>` stop-criteria** — found to be a **non-issue for LiteRT** (`.litertlm` bundles the
  generation config; stops cleanly at EOS). The gap was ExecuTorch-specific.
- [x] **Three-way comparison table + written ship decision** — below.
- [x] **Week 7 scoped**; this summary written.

**Out (as planned):** no ExecuTorch RSS-chase, no `*GeminiService` wiring (Week 7), no from-source chase
of a non-accelerator, no Arcana app code. The spike lives in a throwaway harness; no `.litertlm` or binary
is committed.

---

## 2. The numbers (Pixel 10 Pro XL, Tensor G5, warm n=6 median)

| engine (own-model) | prefill tok/s | **decode tok/s** | **peak RSS** | thermal (fwd/rev decode) |
|---|--:|--:|--:|--|
| LiteRT **npu q8** (TPU) | 1047 | 13.70 | 2790 MB | 1.7% — stable |
| LiteRT **cpu q4** | 146 | **27.37** | **1077 MB** | 3.4% — stable |
| LiteRT **gpu q4** (OpenCL/ClGl) | 448 | ~6.5 (5.1–9.7) | 798 MB | 22.9% — erratic |
| ExecuTorch q4 (Week 5) | 74 | 19.9 | 1477 MB | — |
| Gemini Nano (Week 4) | — | ~36 | ~0 app | — |

**The three-way decision table:**

| engine | process | app RSS cost | decode tok/s | your model? |
|---|---|--:|--:|:--:|
| Gemini Nano | out-of-process (AICore) | **~0** | **~36** | no |
| ExecuTorch Gemma q4 | in-process | 1477 MB | 19.9 | **yes** |
| **LiteRT Gemma q4 (CPU)** | in-process | **1077 MB** | **27.4** | **yes** |

Bandwidth cross-check: LiteRT cpu_q4 decode × working-set = 27.37 × (1077−119) ≈ **26.2k**, inside Week
5's 27–34k band. The decode-is-bandwidth-bound model holds across two engines and 7×+ size range.

Model files: TPU path is **q8** (`Gemma3-1B-IT_q8_ekv1280_Google_Tensor_G5.litertlm`, 1.68 GB — the only
G5 entry in the LiteRT-LM support table); CPU/GPU path is generic **q4** (`gemma3-1b-it-int4.litertlm`,
558 MB). q8-vs-q4 is why the TPU row's RSS looks worst; but even so the TPU couldn't accelerate decode.

---

## 3. Findings that generalise

**1. On new silicon, the vendor's own accelerators can both be dead ends — and only measurement tells you.**
- **TPU (npu): LiteRT [#7787](https://github.com/google-ai-edge/LiteRT/issues/7787), confirmed on-device.**
  The Google Tensor DispatchDelegate *does* engage (loads `libLiteRtDispatch_GoogleTensor.so`,
  `libedgetpu_litert.so` resolves symbols, claims 2 partitions) — then hits `MediaTek dispatch API
  contradictory buffer requirements` + `InvalidArgument` and XNNPack/CPU carries the other 6 partitions.
  Result: **CPU-class decode (13.7 tok/s)**. The TPU *does* accelerate prefill (1047 tok/s) — it's decode
  that falls back. An open, Google-side dispatcher/runtime version mismatch; not fixable from my side.
- **GPU: the chip's GPU is Imagination PowerVR**, and LiteRT logs `Weights preparation on Gpu is disabled
  for PowerVR, Broadcom, Mali GPUs`. Decode collapses to ~6 tok/s and is thermally erratic. Great prefill
  (448), unshippable decode.

**2. The win came from the boring layer.** LiteRT beats ExecuTorch not via hardware but via a better CPU
path: the **per-layer-embedder / weight-caching** design keeps the embedding from materialising to fp32
(ExecuTorch's 1.5 GB RSS killer), so q4 resides at **1077 MB** (−27%), and its XNNPack decode is **27.4
tok/s** (+38%). "Use the vendor runtime on the vendor's chip" was the right call — for an unglamorous
reason.

**3. Three pre-registered predictions, three outcomes — the misses were the value.**
- "No published G5 model / access blocked" → **wrong**: G5 is the SDK's flagship, prebuilt and ungated
  for Pixel 10.
- "The npu path won't cleanly accelerate" → **right**: #7787, on-device.
- "GPU decode will beat CPU (30–45 tok/s)" → **wrong**: 3× *slower*, because PowerVR.
  Stating the prediction first made each miss diagnostic instead of a vibe.

**4. ABI-match the prebuilt, or repeat #7787 yourself.** The GPU accelerator ships as a Git LFS prebuilt
that Bazel's archive fetch leaves as a 132-byte pointer. Fetched the real `.so` from GitHub's LFS media
endpoint and **verified its sha256 == the pointer's oid** before trusting it — the same class of
version/ABI mismatch that broke the TPU path.

---

## 4. The decision

**Q1 — What powers "Ask Arcana"? → User-selectable engine, Gemini Nano default.**
- **Nano is the default**: zero app-resident memory, ~36 tok/s, privacy-first, already ships. An always-on
  1.1 GB own-model would reintroduce the exact LMK-casualty risk the Week-5 go/no-go flagged. Memory was
  the whole concern; the default must not pay it.
- **The own-model is user-selectable** (Settings → inference engine: Nano / **your Gemma (LiteRT q4)** /
  cloud). This turns the "swappable backends behind one interface" abstraction — held since Week 2 — into
  a *visible, interactive* feature a recruiter can touch, and it sidesteps declaring a single winner.

**Q2 — ExecuTorch's role → benchmark engine + "producer" capability (confirmed).** Not a shipping engine.
LiteRT beating it is a *point in the story*: "I self-quantized and deployed with ExecuTorch, evaluated the
vendor runtime, measured both, hit #7787 and the PowerVR wall, chose LiteRT" is stronger judgment than
either choice alone. No further RSS-chasing.

**Q3 — LiteRT's role → the ship engine for the own-model option**, and a benchmark column. q4 on CPU is
the config (not TPU, not GPU) on this silicon.

**Q4 — Week 7 scope → wire + expose.**
1. `LiteRtGeminiService` (q4 CPU) behind the existing `GeminiService` seam.
2. **Settings engine picker** (Nano / your Gemma / cloud) — the new visible feature.
3. Benchmark screen gains a LiteRT column (engine axis is already list-driven; no screen rewrite).
4. Stop-criteria: nothing carries (LiteRT stops at EOS).

**Not doing:** shipping the TPU or GPU path (both measured dead ends here); making the own-model the
forced default; committing any model/binary.

---

## 5. Environment & artifacts (nothing in the repo tree)

**Desktop CLI (smoke test):** `uv tool install litert-lm` (v0.14.0), isolated from the Week-5 ExecuTorch
venv (`torch==2.12.1` untouched). `litert-lm run --from-huggingface-repo litert-community/Gemma3-1B-IT
gemma3-1b-it-int4.litertlm --backend cpu` → "Paris".

**Android build (WSL2 Ubuntu-24.04):** Bazel **7.6.1** (Bazelisk, from repo `.bazelversion`), **NDK
r28b** (`ANDROID_NDK_HOME=/root/android-ndk-r28b`), clang 18, LiteRT-LM **v0.14.0**.
- `bazel build --config=android_arm64 //runtime/engine:litert_lm_main` (simple runner)
- `bazel build --config=android_arm64 //runtime/engine:litert_lm_advanced_main` (**has `--benchmark`,
  `--benchmark_prefill_tokens/-decode_tokens`, `--report_peak_memory_footprint`; the simple main does not**)
- `bazel build --config=android_arm64 @litert//litert/vendors/google_tensor/dispatch:dispatch_api_so`
  → `libLiteRtDispatch_GoogleTensor.so` (built clean from open `@litert`).
- Spike workspace: WSL `/root/litert-lm-week06/` (throwaway); Android target dir `/data/local/tmp/w6`.

**Runtime gotchas worth carrying forward**
- Push these `.so` alongside the binary and set `LD_LIBRARY_PATH=/data/local/tmp/w6`:
  `libGemmaModelConstraintProvider.so` (19.6 MB, a `DT_NEEDED` of the mains),
  `libLiteRtDispatch_GoogleTensor.so` (npu), `libLiteRtClGlAccelerator.so` (gpu). The
  `--litert_dispatch_lib_dir` flag does **not** exist on these mains — `LD_LIBRARY_PATH` is the mechanism.
- The GPU accelerator is a **Git LFS prebuilt** (`litert/prebuilt/android_arm64/libLiteRtClGlAccelerator.so`);
  Bazel's tarball fetch leaves a 132-byte pointer. Fetch the real 2.8 MB `.so` from
  `media.githubusercontent.com/media/google-ai-edge/LiteRT/<ref>/...` and **verify sha256 == the pointer
  oid**. Its deps are all system libs (GLESv3/EGL/dl/m/log/c).
- The **q8 G5 `.litertlm` is NPU-only** (AOT-compiled): `--backend=cpu`/`gpu` on it → `NOT_FOUND`. Use the
  generic q4 file for CPU/GPU.
- Two benchmark output formats: the compiled/npu executor prints `====== DECODE STATS ======` /
  `(e2e) Decode tokens per second (avg)`; the cpu/gpu path prints `BenchmarkInfo:` / `Decode Speed:`.
  Parse both (and require a decimal so the "2" in "(e2e)" isn't captured).
- adb runs from Windows, not WSL — stage artifacts to a Windows path, push from Git Bash; prefix adb
  `/data`/`/sdcard` commands with `MSYS_NO_PATHCONV=1`. Drive WSL via `.sh` files, never inline
  `bash -lc` with shell variables (they get mangled through the Git-Bash→wsl chain).

**HuggingFace:** `litert-community/Gemma3-1B-IT` is a **separate gate** from `google/gemma-3-1b-it`
(HF gates are per-repo) — accepted on the `aashishg11` account. Token already in WSL.

**Artifacts:** model `.litertlm` (558 MB–1.68 GB), the built binaries, and the accelerator `.so` are all
**gitignored / never committed / never bundled**. Rebuild from `/root/litert-lm-week06/`.

---

## 6. Reading order for Week 7

`notes/week06/DAY_03_MEASURE.md` (final numbers + method) → `DAY_02_LITERT_BUILD.md` (build + the #7787 and
PowerVR findings) → `DAY_01_GATE.md` (the gate + toolchain). In-repo: `WEEK_05_SUMMARY.md` (the ExecuTorch
column this compares against), `DESIGN.md` (the `GeminiService` seam Week 7 wires into),
`arcana-wireframes.html` (Settings screen source of truth for the engine picker).
