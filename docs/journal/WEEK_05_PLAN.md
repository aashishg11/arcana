# Arcana — Week 5 Plan (curriculum / de-risk week)

*Following Week 4 completion. This is a **study week**, not a feature week — no Arcana app code ships. The deliverable is a capability, a set of measurements, and a decision. Commit to repo root as `WEEK_05_PLAN.md`.*

*Assumes the Week 4 state in `WEEK_04_SUMMARY.md` — two shipped `ai-samples` capabilities, the `GeminiService` seam, and the benchmark screen already built to accept **N engines** (so a future `ExecuTorchGeminiService` slots in as another column with no screen change).*

---

## The week's goal, in one sentence

Find out — early and cheaply — whether deploying your own quantized **Gemma 3 1B** on-device via **ExecuTorch** is tractable on this hardware, by driving a model through the full export → quantize → deploy → run pipeline onto the Pixel 10 Pro XL, and ending the week with a **go/no-go** that also answers *ExecuTorch vs LiteRT for Tensor*.

This is the roadmap's riskiest, least-bounded stretch. Week 5 exists to hit the toolchain wall in week one — not to disappear into it on faith. The original roadmap split this into Week 5 (study) + Week 6 (first deploy); we deliberately **pull the deploy attempt forward** so the go/no-go lands a week earlier.

## Why this week (the "what you achieve")

Everything shipped so far consumes Google's on-device models through managed SDKs (Nano via Firebase, ML Kit summarization). That's *consumer* of the stack. This week is the jump to *producer*: quantize a raw open-weights model yourself, convert it to a mobile runtime, and run your own build on-device. That capability — "I can take an arbitrary PyTorch model and ship it on-device, and I measured the tradeoffs" — is the single strongest evidence for the on-device-AI half of the pivot, the half a resume can't yet claim.

**What this is NOT:** a faster or better Arcana. On this hardware (see below) your Gemma will likely run on **CPU/GPU, not the Tensor NPU**, so it will probably be *slower* than Nano-on-NPU. The prize is the capability + the measured, understood tradeoff + the vocabulary to defend both — not a speed win. A working-but-slower deployment with a rigorous comparison is a **complete success** by this week's actual purpose.

---

## Hardware reality (decides the whole strategy)

- **Workstation:** Dell Pro Max 16, Intel Core Ultra 9 285H (Arrow Lake — has an Intel NPU + Arc iGPU, **no NVIDIA GPU**), 64 GB RAM, Windows 11 Pro.
- **No CUDA.** Nearly every ExecuTorch LLM export example assumes `--device cuda`. You'll **export on CPU** (64 GB RAM makes a 1B model tractable — minutes, not hours); some CUDA-first recipes will need adapting or the cloud escape hatch.
- **Cloud escape hatch (ready, not default):** export on a rented NVIDIA instance / Colab, download the `.pte`. Costs cents for a 1B model. The phone runs the model; the cloud is only for *building* it. Reach for it the moment a recipe fights you — don't burn a day on a CUDA-only path.
- **Use WSL2 (Ubuntu) for the ML toolchain.** ExecuTorch's build-from-source is friendlier on Linux than native Windows. Android Studio + adb + Pixel stay on native Windows. Try native Windows Python first; drop to WSL2 the moment the install fights you.
- **Target execution path on the Pixel = CPU (XNNPACK) or GPU (Vulkan), NOT the Tensor NPU.** ExecuTorch's mature NPU delegate is Qualcomm Hexagon; Google's Tensor NPU is not a paved road. NPU acceleration is **explicitly out of scope** this week — and its absence is part of the go/no-go, not a failure.

---

## Scope discipline — what Week 5 is and isn't

**In, on purpose:**
- WSL2 + ExecuTorch + Optimum-ExecuTorch toolchain standup, with concept-grounding.
- The **difficulty ladder**: toy vision model → tiny open LLM (+ quantization) → Gemma 3 1B.
- CPU export (cloud-GPU escape hatch flagged), on-device run via the ExecuTorch Android runtime.
- Measurement (tokens/sec, load time, memory, size; CPU vs GPU where both run) and the go/no-go.

**Out, deliberately — not gaps:**
- **NPU acceleration on Tensor** — not a paved road; CPU/GPU only this week.
- **Wiring into `GeminiService` / the app** — that's Week 7. Week 5 runs a **standalone runner**, not an integrated feature.
- **Any Arcana app code / benchmark-screen integration** — this is a curriculum week. The `.pte` files, export scripts, and writeup live in a scratch/learning location (or `notes/`); `.pte` files are gitignored (hundreds of MB); the main app tree is untouched.

---

## The ladder (the safety net)

Don't start on Gemma. Each rung adds exactly one new layer, so a documented dead-end on any rung still banks the toolchain + the learning below it:

1. **Toy vision model** (MobileNetV2 / the getting-started example) — proves export → lower → `.pte` → run on device, with *no* tokenizer and *no* LLM runner. Isolates "does the toolchain work at all."
2. **Tiny open LLM** (SmolLM2-135M or Qwen2.5-0.5B — **ungated**, small, fast to iterate) — first tokenizer, first `LlmModule`, first quantization. This is where the quantization *learning* happens, unblocked by gated downloads.
3. **Gemma 3 1B** — the real target: gated (HF license), INT4, the actual goal.

---

## Day 1 — Toolchain standup + the mental model

Highest-risk day. The install is bleeding-edge; treat getting a clean environment as the deliverable, not a preamble.

**Concept to deposit:** the ExecuTorch pipeline — `torch.export` (capture the graph) → `to_edge_transform_and_lower` (partition subgraphs to a backend delegate, CPU fallback for the rest) → `.to_executorch()` (the `.pte`) → the on-device runtime. Be able to say what each stage does before running anything.

**Tasks**
- WSL2 Ubuntu; Python 3.10–3.13; PyTorch (CPU build); ExecuTorch from source; Optimum-ExecuTorch.
- Hugging Face account; **accept the Gemma license now** so Day 4's gated download doesn't fail mid-session.
- Read the architecture guide + skim the `export_llm` / Optimum paths, so Days 2–4 are execution, not first contact.

**Definition of done**
- [ ] ExecuTorch imports cleanly in WSL2 (or a **precisely documented** install wall + what was ruled out).
- [ ] You can articulate export → lower → `.pte` → runtime in your own words.
- [ ] HF account has Gemma access confirmed.

---

## Day 2 — Rung 1: a toy model, end-to-end onto the Pixel

Prove the *whole* loop works before any LLM complexity.

**Concept to deposit:** what `torch.export` actually produces (the ATen graph), what the XNNPACK partitioner delegates vs leaves on CPU, what's inside a `.pte`.

**Tasks**
- Export MobileNetV2 (or the toy linear example); lower with the XNNPACK (CPU) partitioner; produce a `.pte`.
- Add the ExecuTorch Android runtime to a throwaway harness; `adb push` the `.pte`; run it on the Pixel; get output.

**Definition of done**
- [ ] A toy `.pte` runs on the Pixel and produces correct output (or a documented wall).
- [ ] You can explain what export + lower did to the model.

---

## Day 3 — Rung 2: a tiny LLM + quantization (where the learning is)

First LLM path, first quantization — on a small ungated model so iteration is fast and nothing blocks on a license.

**Concept to deposit:** INT8 vs INT4, weight-only vs activation quantization, group size, and *what compression throws away* (why quality degrades, where). This is the vocabulary the whole step exists to buy.

**Tasks**
- Export SmolLM2-135M or Qwen2.5-0.5B via Optimum-ExecuTorch; apply INT4 (and compare INT8) weight quantization.
- Record `.pte` size before/after quantization. Run on the Pixel via the `LlmModule` runner; eyeball output quality at each precision.

**Definition of done**
- [ ] A quantized tiny-LLM `.pte` runs on the Pixel and generates text (or a documented wall).
- [ ] You have before/after size numbers and can explain INT4 vs INT8 and what quantization discarded.

---

## Day 4 — Rung 3: Gemma 3 1B, the real target

The actual goal. Expect friction — CUDA-first recipes, the gated download, memory during export.

**Tasks**
- Export Gemma 3 1B via Optimum-ExecuTorch with INT4 (`--qlinear 4w`), on CPU. **If the recipe fights the no-CUDA path, switch to the cloud-GPU escape hatch** rather than losing the day — export there, download the `.pte`.
- Get the `.pte` + tokenizer onto the Pixel; run via the runner; confirm coherent generation.

**Definition of done**
- [ ] Gemma 3 1B (quantized) generates coherent text on the Pixel — **OR** a precisely documented failure with what was tried and ruled out. *Either is a legitimate deliverable in a de-risk week.*

---

## Day 5 — Measure, then the go/no-go

**Tasks**
- Measure whatever rung you reached: tokens/sec, load time, peak memory, `.pte` size; CPU (XNNPACK) vs GPU (Vulkan) if both run. Hold it against your Week-4 Nano number (~36 tok/s on-device) for context — same device, apples-ish to apples.
- Write the **go/no-go**, answering three questions explicitly:
  1. **Viability** — *green* (toy + tiny + Gemma all ran), *yellow* (pipeline works, Gemma specifically is fighting), or *red* (ExecuTorch-on-this-hardware is a swamp).
  2. **ExecuTorch vs LiteRT** — given the Tensor NPU isn't an ExecuTorch path and LiteRT *is* Google's runtime (and is already your Week-9 RAG embedding vehicle), is LiteRT-LM the better long-term on-device-model choice for this device?
  3. **What Week 6 becomes** — harden the runner (green) / make Gemma work (yellow) / pivot to LiteRT or bank the listing writer + pull RAG forward (red).

**Definition of done**
- [ ] Measured numbers for whatever ran, in a small table.
- [ ] A written go/no-go answering all three questions; Week 6 scope decided.

---

## Weekend / buffer

- **Cloud-GPU isolation** (if Gemma stalled on Day 4): export Gemma on a rented NVIDIA instance to cleanly separate "my CPU-export path is the problem" from "Gemma-via-ExecuTorch is the problem." A useful data point for the go/no-go.
- **The writeup is a real deliverable this week.** Draft the quantization/deployment learning as blog/interview-prep notes — the tradeoffs, the walls, the ExecuTorch-vs-LiteRT reasoning. For a curriculum week, the *articulated understanding* is half the point.
- Write `WEEK_05_SUMMARY.md`.

---

## Week 5 — Definition of Done (the single checklist)

- [ ] ExecuTorch toolchain stood up (WSL2), pipeline understood in your own words
- [ ] Rung 1 (toy) ran on the Pixel end-to-end
- [ ] Rung 2 (tiny LLM) quantized and ran; INT4-vs-INT8 tradeoff understood with size numbers
- [ ] Rung 3 (Gemma 3 1B) ran on the Pixel **or** failed with a precise, documented wall
- [ ] Measured numbers captured; go/no-go written answering viability + ExecuTorch-vs-LiteRT + Week-6 scope
- [ ] Learning writeup drafted; `WEEK_05_SUMMARY.md` written

---

## Gotchas to watch

- **"Learn on the go" needs artifacts, not a feeling.** Every day ends in a converted model *or* a documented failure state. A precise dead-end ("Gemma INT4 export OOMs at step X on CPU; cloud-GPU export succeeded") is a legitimate deliverable — it's exactly the information the go/no-go needs.
- **Don't fight a CUDA-only recipe for a day.** The moment a path demands CUDA, switch to cloud export. Time-box the local attempt.
- **Don't start on Gemma.** The ladder is the safety net — a wall on rung 3 still banks rungs 1–2 and the toolchain.
- **Slower than Nano is expected, not failure.** CPU/GPU vs Nano-on-NPU. The measured, *explained* tradeoff is the deliverable.
- **Gemma is gated** — accept the license Day 1.
- **`.pte` is hundreds of MB** — `adb push` to the device, gitignore it, don't bundle in an APK or commit it.
- **Windows vs WSL2** — ML toolchain in WSL2, Android on native Windows; don't fight ExecuTorch's build on native Windows.

## What Week 6 / 7 inherit (contingent on the go/no-go)

- **Green** → Week 6 hardens the runner; Week 7 wires an `ExecuTorchGeminiService` behind the existing seam, and the Week-4 benchmark screen gains a third column (Nano vs Gemma vs cloud) with **no screen rewrite** — that's Week 8's toggle, arriving early.
- **Yellow** → Week 6 becomes "make Gemma work" (or ship the tiny LLM as the proof and revisit Gemma later).
- **Red** → pivot to **LiteRT-LM** (already the Week-9 RAG vehicle, and the demonstrated Gemma-on-Tensor path) or bank the `genai-writing-assistance` listing writer and pull RAG forward. Even here, "I evaluated ExecuTorch for Tensor deployment, hit these specific walls, and chose LiteRT" is a legitimate applied-AI engineering-judgment story — the floor isn't zero.
