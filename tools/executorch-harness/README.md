# ExecuTorch harness (Arcana Week 5)

The throwaway harness from Arcana's Week-5 curriculum/de-risk week — the evidence
behind [`WEEK_05_SUMMARY.md`](../../WEEK_05_SUMMARY.md).

**This is not part of the Arcana app.** It is a **standalone** Android project (its own
`settings.gradle.kts`, its own Gradle wrapper) that lives here so the whole Week-5 story is
in one clone. Arcana's root `settings.gradle.kts` stays `include(":app")` and never references
this folder — "single `:app` module" still holds. Open *this directory* in Android Studio to
work on it, not the repo root.

## What it proves

The jump from **consumer** of Google's on-device stack (Gemini Nano via Firebase, ML Kit) to
**producer**: quantize an arbitrary open-weights PyTorch model and run your own build on the
Pixel 10 Pro XL. Climbed as a ladder, each rung adding one layer:

1. **MobileNetV2** — export → lower → `.pte` → run, no tokenizer, no LLM runner.
   XNNPACK-delegated **6 ms** vs un-delegated **1002 ms** (~167×), *both* matching host eager
   PyTorch to ~1e-5. (`PteRunnerTest`)
2. **SmolLM2-135M** — first tokenizer, first quantization. INT4 down to **86.5 MiB**,
   **131–138 tok/s**. (`LlmRunnerTest`)
3. **Gemma 3 1B** (gated) — the real target. INT4, **682.5 MiB**, **19.9 tok/s**, coherent.
   (`GemmaRunnerTest`)

`MeasureTest` is the Day-5 measurement pass (one `@Test` per model = fresh process per peak-RSS
reading). The go/no-go lives in
[`notes/week05/DAY_05_MEASURE_GONOGO.md`](../../notes/week05/DAY_05_MEASURE_GONOGO.md) *(gitignored;
local only)*.

## Layout

```
export/     the WSL side — export + quantize + inspect scripts, and the version lockfile
app/src/androidTest/   the device side — four instrumented tests (Java)
```

## How to run it

### 1. Build the models (WSL2, CPU — no CUDA needed)

The ML toolchain runs in WSL2 (Ubuntu 24.04). Versions are pinned in
[`export/constraints.txt`](export/constraints.txt) / [`export/requirements.lock.txt`](export/requirements.lock.txt).

> **The one pin that matters:** `torch==2.12.1`. `executorch 1.3.1` declares `torch>=2.12.0a0`
> with **no upper bound**, but its `_portable_lib*.so` links a `c10` symbol that torch 2.13
> renamed → `ImportError: undefined symbol`. Install from the CPU index:
> `pip install executorch -c constraints.txt --index-url https://download.pytorch.org/whl/cpu`.

```bash
# in ~/arcana-ml with the venv active
bash export/export_gemma.sh          # writes out/gemma3_1b_8da4w_emb4w/model.pte + fetches tokenizer.json
python export/inspect_pte.py         # dtype census: proves what actually got quantized
```

**Always `--qlinear 8da4w` (or `8da8w`), never `4w`/`8w`.** Weight-only configs have no XNNPACK
kernel, so the dequantize is constant-folded back to fp32: full accuracy cost, zero size/speed
benefit, exit code 0. See the summary.

### 2. Push to the device

`/data/local/tmp` is `drwxrwx--x shell:shell` — an app can traverse but not list it, so files
must be world-readable:

```bash
adb push out/gemma3_1b_8da4w_emb4w/model.pte out/gemma3_1b_8da4w_emb4w/tokenizer.json \
  /data/local/tmp/gemma3_1b_8da4w_emb4w/
adb shell 'chmod 755 /data/local/tmp/gemma3_1b_8da4w_emb4w; chmod 644 /data/local/tmp/gemma3_1b_8da4w_emb4w/*'
```

### 3. Run on the Pixel

```bash
JAVA_HOME="C:/Program Files/Android/Android Studio1/jbr" ./gradlew :app:installDebugAndroidTest
adb shell am instrument -w -e class com.aashishgodambe.etharness.GemmaRunnerTest \
  com.aashishgodambe.etharness.test/androidx.test.runner.AndroidJUnitRunner
adb logcat -d -s ETGEMMA          # results (tok/s from the runtime's own onStats())
```

## Runner gotchas (all cost real debugging time — see the day-notes)

- Use the **3-arg** `new LlmModule(pte, tokenizer, temperature)`. `LlmModuleConfig.create()…build()`
  defaults `dataPath` to `""`; the native layer opens it, fails, and **aborts the process**.
- **Gemma needs a literal `<bos>`** in the prompt string. The runner prepends none, and
  `LlmGenerationConfig.numBos()` / `.maxNewTokens()` are **ignored** (`seqLen` bounds generation).
- `optimum-cli export executorch` writes **only `model.pte`** — fetch `tokenizer.json` separately.
- **Measure warm, n≥3, fresh process per model, with device cooldowns.** Thermal throttling shows
  up as monotonic decline across consecutive runs.

## `.pte` files are not here

They run 86 MiB → 1.7 GiB and are gitignored. Rebuild them with `export/`.
