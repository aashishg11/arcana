# Week 5 — Completion Summary (handoff for Week 6+)

Status: **complete.** A curriculum / de-risk week, exactly as scoped: **no Arcana app code shipped, and
the main tree is untouched.** The deliverables are a capability, a set of measurements, and a decision.

**The headline:** *I exported, quantized, and deployed my own Gemma 3 1B to the Pixel 10 Pro XL via
ExecuTorch — 682.5 MiB INT4, 19.9 tok/s — and I can explain exactly why it is 1.8× slower than Gemini
Nano, and why memory (not speed) is what would stop it shipping.*

This is the jump from **consumer** of Google's on-device stack (Weeks 2–4: Nano via Firebase, ML Kit
summarization) to **producer**: taking raw open weights, quantizing them, and running my own build
on-device.

---

## 1. What the week delivered (vs. `WEEK_05_PLAN.md`)

Every Definition-of-Done item is met.

- [x] **ExecuTorch toolchain stood up** (WSL2 Ubuntu 24.04), pipeline understood and written up in prose
- [x] **Rung 1 (toy)** — MobileNetV2 ran end-to-end on the Pixel, verified against host eager PyTorch
- [x] **Rung 2 (tiny LLM)** — SmolLM2-135M quantized and generating; INT4-vs-INT8 understood with sizes
- [x] **Rung 3 (Gemma 3 1B)** — ran on the Pixel. **Not** a documented wall: an actual working deployment
- [x] **Measured numbers captured**; go/no-go written (viability, ExecuTorch-vs-LiteRT, Week-6 scope)
- [x] **Learning writeup drafted** (`notes/week05/`, `notes/INTERVIEW_PREP.md`); this summary

The plan pulled Week 6's deploy attempt forward into Week 5. That bet paid: **the go/no-go lands a week
early**, and the CPU-export path worked, so the rented-GPU escape hatch was never needed.

---

## 2. The numbers

**Gemma 3 1B, self-quantized, on the Pixel 10 Pro XL** (warm, n=3 median, 90 s cooldown between models):

| variant | `.pte` | peak RSS | load | prefill tok/s | decode tok/s |
|---|--:|--:|--:|--:|--:|
| `8da4w` | 1689.5 MiB | 1477 MiB | 4394 ms | 77.9 | 19.80 |
| `8da4w` + `qembedding 4w` | **682.5 MiB** | 1477 MiB | 4204 ms | 74.1 | **19.93** |
| `8da8w` + `qembedding 4w` | 1102.4 MiB | 2022 MiB | 4546 ms | 58.3 | 13.68 |

**SmolLM2-135M** (same method): `8da4w` **180.8 MiB / 138.4 tok/s**; `+qembedding` **86.5 MiB / 131.4 tok/s**;
`8da8w` **237.7 MiB / 93.2 tok/s**.

**Reference (Week 4, same phone):** Gemini Nano ≈ **36 tok/s** on the Tensor NPU. **Nano is ~1.8× faster.**

**Rung 1, and the week's most reusable lesson:** the *same* MobileNetV2, lowered with and without the
XNNPACK partitioner — **6 ms vs 1002 ms** warm median (~**167×**), *both numerically correct*.

Export cost: 700–900 s per Gemma variant on CPU, **~9 GB peak host RAM** (of 31 GB in WSL).

---

## 3. Three findings that generalise

**1. On-device ML fails silently and plausibly. The artifact is the only witness.**
Three separate instances, same shape:
- An **un-delegated** model is *correct* and **167× slower**. Nothing in the output says which you shipped.
- `--qlinear 4w` (weight-only) on XNNPACK is **worse than a no-op**: torchao rounds the weights to int4,
  then lowering **constant-folds the dequantize back into an fp32 tensor**. You get the full accuracy cost
  of 4-bit and none of the size or speed benefit — 621 MB, fp32 speed, gibberish output. **Exit code 0, no
  warning.** Use `8da4w`/`8da8w`, the configs the backend has real integer kernels for.
- `--qlinear_group_size` is parsed, range-validated, and then **never forwarded** by
  `optimum-executorch 1.1.0`'s `causal_lm.py`. It always uses torchao's default of 32.

The checks that catch these: count `executorch_call_delegate` nodes; read `.pte` size and tensor dtypes;
read the generated text.

**2. The ladder is a control experiment, not just risk-sequencing.**
Gemma 3 1B first generated `" is is is is"`. Everything pointed at int4. It was **a missing `<bos>` token**
— Gemma requires one at position 0, the runner prepends none, and `LlmGenerationConfig.numBos()` is
ignored. `prompt_tokens` 5 → 6 turned gibberish into *"Paris."* — same `.pte`, same weights.
Without rung 2 (a *working* int4 model) as a control, the obvious, well-supported conclusion would have
been "Gemma-via-ExecuTorch degrades at int4," and the go/no-go would have been written on a false
negative. **When a system fails at the layer you feared, check the layer you didn't.**

**3. Measure warm, measure repeatedly, and control the phone's temperature.**
Day 3's single cold run per model produced a confident, wrong claim (`qembedding` gives "+29% decode").
Day 5's warm n=3 reversed the ordering. Gemma's decode declined monotonically 18.45 → 17.13 → 16.00
across consecutive runs — **thermal throttling**, revealed by re-running in reverse order with cooldowns
(19.93 vs 19.80: identical).

Corrected model: **decode is bandwidth-bound by the in-memory working set, not by `.pte` size.**
`decode × (RSS − baseline)` holds at ~27–34 k MB·tok/s across a 7.4× model-size range. Predicted before
measuring `8da8w`: 16.3 tok/s; observed 13.68 (RSS under-estimated). Prefill is compute-bound and moves
the other way.

---

## 4. The go/no-go

**Viability — GREEN.** Toy, tiny LLM, and Gemma 3 1B all ran on the Pixel. CPU export throughout.

**Viability *inside Arcana* — YELLOW, and memory is the reason, not speed.**
A 682 MiB `.pte` resides at **~1.5 GB RSS**, because the int4 embedding/lm_head is **dequantized to fp32
in RAM** (proved: 2.5× smaller file, *identical* RSS and identical decode). An app holding 1.5 GB resident
alongside Room, Coil, and Compose is an LMK casualty on the next app switch. **Gemini Nano costs the app
zero resident model memory** — AICore hosts it in a separate system process. 19.9 tok/s is a perfectly
usable streaming speed; 1.5 GB is not a usable footprint.

**ExecuTorch vs LiteRT.** ExecuTorch on Tensor is **CPU-only, structurally** — verified twice, not assumed:
`optimum-executorch` ships no Vulkan recipe, and the Maven AAR's `libexecutorch.so` contains **zero**
Vulkan symbols (vs 3 for XNNPACK), so the stock Android runtime cannot execute a GPU-delegated `.pte`.
`QnnPartitioner` isn't in the wheel, and it's Qualcomm's NPU anyway. There is no Tensor NPU delegate.

→ **LiteRT-LM is the better bet for Arcana's *shipping* engine** (Google's runtime for Google's silicon,
the demonstrated Gemma-on-Tensor path, and already the Week-9 RAG dependency).
→ **ExecuTorch stays as a measured benchmark engine and portfolio capability.** It doesn't have to be the
shipping default to be the best artifact: *same phone, same prompt, same `GeminiService` seam, three
engines.* And "I evaluated ExecuTorch for Tensor, hit these specific walls, measured the cost, and chose
LiteRT" is a stronger judgement story than either choice alone.

**Week 6 scope** (harden the runner — but the highest-value work is memory):
1. **Chase the 1.5 GB.** Can the fp32 embedding dequantization be avoided? Investigate `.ptd`-separated
   weights, `LOAD_MODE_MMAP` (on `LlmModuleConfig`, unused by the 3-arg ctor), and `qembedding 8w`.
   **This number decides whether ExecuTorch can ever ship inside Arcana.**
2. **Timeboxed LiteRT-LM spike (≤2 days):** Gemma 3 1B on the Pixel via LiteRT; measure tok/s **and peak
   RSS**. That is the apples-to-apples comparison the decision actually turns on.
3. **Fix stop criteria** — the runner doesn't halt at `<end_of_turn>` (no `generation_config.json` is
   exported). Truncate client-side or supply the EOS set.
4. **Then** wire `ExecuTorchGeminiService` behind the existing seam (Week 7); the Week-4 benchmark screen
   gains a third column with **no screen rewrite** — its engine axis is already list-driven.

**Not doing:** a from-source AAR build to chase Vulkan. Multi-day yak-shave for a GPU that still isn't the
NPU, on a device whose vendor runtime already targets the right accelerator.

---

## 5. Environment and artifacts (nothing in the repo tree)

**Toolchain — WSL2 `Ubuntu-24.04`, default user `root`, venv `/root/arcana-ml/.venv`.**
`executorch 1.3.1+cpu` · **`torch 2.12.1+cpu`** · `torchao 0.17.0+cpu` · `torchvision 0.27.1+cpu` ·
`optimum-executorch 1.1.0` · `transformers 5.0.0rc1`. Frozen: `requirements.lock.txt`; pins: `constraints.txt`.

> **Do not let torch float to 2.13.** `executorch 1.3.1` declares `torch>=2.12.0a0` with **no upper bound**,
> but its `_portable_lib*.so` links `c10::impl::cow::materialize_cow_storage(StorageImpl&)`, which torch
> 2.13 renamed to `materialize_cow(StorageImpl*)` → `ImportError: undefined symbol`. Install with
> `-c constraints.txt --index-url https://download.pytorch.org/whl/cpu` (the CPU index also drops ~18
> useless NVIDIA/CUDA packages). Diagnosed with `nm -D` in 30 seconds; a version pin can't express ABI.

**Throwaway Android harness:** `C:\Users\agodambe\AndroidStudioProjects\et-harness` — **outside the Arcana
repo**, on purpose. `org.pytorch:executorch-android:1.3.1` from Maven Central (prebuilt AAR; no NDK, no
cmake, no source build). **Match the AAR version to the exporter version.** Tests: `PteRunnerTest` (rung 1),
`LlmRunnerTest` (rung 2, one `@Test` per precision), `GemmaRunnerTest` (rung 3, incl. a test that asserts
the no-BOS *degeneration* on purpose), `MeasureTest` (Day 5, one `@Test` per model = fresh process per
peak-RSS reading).

**Model artifacts:** WSL `~/arcana-ml/out/**` and device `/data/local/tmp/<variant>/`. `.pte` files are
hundreds of MB to 1.7 GB — never committed, never bundled in an APK.

**Gotchas worth carrying forward**
- `/data/local/tmp` is `drwxrwx--x shell:shell` — an app can **traverse but not list** it. `adb push`, then
  `adb shell chmod 644 <file>` (and `755` on the dir), or the app fails to open a file that's plainly there.
- `LlmModuleConfig.create()…build()` defaults `dataPath` to `""`; the native layer opens it, fails, and
  **aborts the process** (Gradle reports only "Process crashed", no Java stack). Use the 3-arg
  `new LlmModule(pte, tokenizer, temperature)`.
- `LlmGenerationConfig.maxNewTokens()` and `.numBos()` are **ignored**. `seqLen` bounds generation; write
  `<bos>` into the prompt text (the tokenizer parses special tokens out of it).
- `optimum-cli export executorch` writes **only `model.pte`** — fetch `tokenizer.json` from the Hub yourself
  (Gemma's is 33 MB). Don't stage it in `/tmp`; `systemd-tmpfiles` will delete it mid-session.
- **Read the resolved config, not raw JSON.** `gemma-3-1b-it`'s `config.json` omits `tie_word_embeddings`;
  `AutoConfig` supplies the default (`True`). An absent key is not `False`.
- **Maven's search API is stale** (`search.maven.org` topped out at `executorch-android` 0.6.0); read
  `repo1.maven.org/.../maven-metadata.xml`, which lists through 1.3.1.

---

## 6. Reading order for Week 6

`notes/week05/DAY_05_MEASURE_GONOGO.md` (numbers + the decision) → `DAY_04_RUNG3_GEMMA.md` (the `<bos>`
story) → `DAY_03_RUNG2_QUANTIZATION.md` (quantization; **note the Day-5 correction banner at the top**) →
`DAY_02_RUNG1_PIXEL.md` (the 167× delegation result) → `DAY_01_TOOLCHAIN.md` (toolchain + ABI wall).

In-repo reference: `ARCANA_CONTEXT.md`, `DESIGN.md`, `WEEK_04_SUMMARY.md`, `arcana-wireframes.html`.
