---
layout: default
title: "On-Device AI Is Easy to Demo. Hard to Trust."
permalink: /
---

<img src="media/cover.svg" class="cover" alt="On-Device AI Is Easy to Demo. Hard to Trust. — Arcana, a privacy-first on-device AI app measured on a Pixel">

# On-Device AI Is Easy to Demo. Hard to Trust.
{: .sr-only}

<p align="center"><a href="https://github.com/aashishg11/arcana">View the code on GitHub →</a></p>

---

A few weeks into building Arcana, my portfolio app opened to a headline claiming my collection had gained **$3,608 in a week**. It hadn't. Nothing I owned had moved more than a few dollars. The number was confident, formatted, green, and completely wrong.

That bug — and how I found it — is the whole point of this post. Building an on-device AI feature that works in a demo is easy now; the SDKs are good. Building one you can *trust*, and knowing the difference, is the actual job. This is the story of making an on-device AI app measurable, and what broke when I did.

## The app, in three sentences

Arcana is a privacy-first Android app for a real 504-item collectible collection (Kotlin, Compose, Hilt, Room). It ships **eight on-device generative-AI capabilities** — natural-language Q&A over your collection, weekly summaries, a listing-copy writer, and photo identification — with **zero collection data leaving the device**; the cloud is an explicit, last-resort escalation, not the default. Everything below was measured on a Pixel 10 Pro XL, against my own real collection, with numbers I pre-registered as predictions before I ran them.

<p align="center"><img src="media/portfolio.png" width="300" alt="Arcana portfolio home: tracked value $30,327, up $755 this week, a sparkline, and an on-device weekly summary"></p>

<p align="center"><em>The value-first home — a real tracked total with an <strong>on-device</strong> weekly summary.</em></p>

I'm going to skip the feature tour. What's worth your time is the engineering posture: on-device AI has a measurement problem, a set of hard ceilings, and three places where the obvious approach simply doesn't work — and saying so is the senior signal, not a gap.

## Act I — Building it on-device

The foundation is Gemini Nano via AICore, behind a single interface seam (`GeminiService`) so the UI never knows or cares which engine answered. That seam earned its keep immediately, because on-device inference has an infrastructure surface that pure-cloud code doesn't:

- **Nano is foreground-only, single-turn, English/Korean, one inference at a time, ~4000-token cap.** These aren't bugs; they're the shape of the platform, and you design around them or you fight them.
- **It un-provisions.** Nano that streamed on-device one day was silently falling back to cloud the next, after a system update. `PREFER_ON_DEVICE` never re-downloads the model — it just quietly uses cloud — so "it worked yesterday" is not a state you can assume. The fix is to gate on readiness explicitly (`checkStatus() == AVAILABLE`) and degrade honestly.

The design decision I'm proudest of here is making the on-device/cloud distinction *truthful by construction*. Early on I forced `ONLY_ON_DEVICE` so that a thrown exception is an unambiguous "Nano didn't run" — no silent fallback masking a failure — and the production path both drives its own routing (try-on-device, catch, fall back, which I need anyway for `BUSY` and provisioning errors) **and** reads the SDK's `inferenceSource` back as a cross-check. The little "On-device" badge in the UI is not decorative; it's telemetry the user can see.

The honest architecture isn't "on-device instead of cloud." It's **on-device by default, cloud when on-device structurally can't** — and knowing exactly where that line is.

## Act II — Making it measurable

Here's the part most on-device-AI writeups skip. A demo shows you *one* answer. It can't tell you how *fast*, or how *often right*. So Arcana has two measurement systems.

**Latency, first.** An in-app benchmark runs each engine through the same `GeminiService` seam, cold-once-per-process, and reports p50/p95. The headline tradeoff fell straight out of it:

> On-device Nano reaches its **first token faster** than cloud (~0.4 s vs ~0.6–1.0 s — no network round-trip), but generates the full answer **~3–4× slower**. You buy privacy and a snappier first token; you pay in total latency.

That's a real, quotable engineering tradeoff, and I only have it because I measured it separately for time-to-first-token and total. (It also surfaced a methodology trap: the cloud free tier's rate-limiter silently injects retry/backoff into "successful" calls, inflating cloud latency from ~0.7 s to ~9 s. A latency benchmark goes quietly wrong when the thing you're timing includes a backoff you didn't account for.)

<p align="center"><img src="media/benchmark-3engine.png" width="300" alt="In-app benchmark comparing Nano, my own Gemma, and cloud on first-token and total latency, p50"></p>

<p align="center"><em>The in-app benchmark — Nano vs. my own quantized Gemma vs. cloud, first-token and total, measured through the same interface the app uses.</em></p>

**Accuracy, second — the missing half.** A benchmark that says "Nano's first token in 437 ms" cannot say "and how often is the answer right." So I built a separate accuracy eval: labeled fixtures, a runner, a scorer, and a committed results table. Two design decisions make its numbers mean something:

1. **Ground truth is derived independently.** The structured-query answers come from a *separate Python parse of the committed CSV* — different language, different code path from the app's Kotlin importer — and the eval asserts the app *matches*. A test that scores code against itself measures nothing. When my independent count of NFT-redeemable items came out to 141, the exact number the app produces over the live database, that agreement actually confirmed the whole import→SQL pipeline was faithful.
2. **Every number was pre-registered as a prediction.** I wrote down what I expected before running, and I was mostly wrong — which is the value. The misses are things I now *know* about the system instead of assume.

The results, unflattering where the hardware is:

| What | Score |
|---|---|
| Query router (intent classification) | **34/34** real queries |
| Structured retrieval (counts, values) | **18/18** exact, to the dollar |
| On-device semantic search (top-1 / top-k) | **46% / 77%** |
| Photo cascade — reads the number | **62%** |
| Photo cascade — resolves full identity | **32%** |
| Cloud escalation | **4/4** |

The harness earned its cost by **catching three real, user-facing bugs that green unit tests and hands-on use had both missed** — a follow-up question that silently misrouted, a "how many items?" that returned 0, and a box parser that let an edition size (`2300 PCS`) beat the actual catalog number — each fixed with a measured before/after delta.

And it produced the single most useful finding in the project: **reading the number is not the same as resolving the identity.** The cascade reads a photo's catalog number 62% of the time but confirms a full owned identity only 32%, because a match needs the number *plus* corroborating franchise/character to clear the confidence gate — and a degraded shot reads `161` but loses the franchise text. I would have quoted 62% as "identification accuracy" and been wrong. The eval forced the distinction, and that distinction is *why* the app escalates to the cloud at all. When the owner asked whether the low scores were just bad photos ("I shot them at night"), I didn't reassure — I measured the luminance of all 30 photos and grouped by pass/fail. The failures were *brighter* on average than the passes (126 vs 109 on a 0–255 scale). Low light ruled out; the real limits are contrast, geometry, and clutter. A number beats a reassurance.

## Act III — The three "no"s

This is the section that matters most, because it's where the obvious approach fails and the engineering is in knowing why.

### No #1 — RAG can't count

The plan for the Q&A feature was "replace keyword search with semantic search." I'd filed two weaknesses under "RAG will fix this": synonyms and undercounting. Before writing the retriever, I checked whether that was true. It isn't — and only *one* of them is a RAG problem.

Vector search returns the top-k most *similar* items. Ask "how many Marvel do I own?" and you get 5 Marvels, never the total. Cranking `k` until it can count is just a worse `SELECT COUNT(*)`. So I built a **hybrid retriever**: a small, rules-based, LLM-free router classifies the question and dispatches it — *aggregate/filter/rank* ("how many Marvel", "most valuable", "added in 2023") to a precise **SQL** query, *fuzzy/semantic* ("any pops with dragons?") to **vectors**, and a lexical fallback when the embedding model is absent. The count is computed in SQL and handed to the model as an **authoritative fact** in the prompt — the model *phrases* a number it's been given, rather than deriving one from rows it only partly sees.

On the real collection: "how many Marvel?" returns 100 Pops (124 including duplicates, $2,136), the true total; "dragons?" surfaces Vhagar, a dragon whose name contains no form of the word. Retrieval isn't one thing — *"find me things like X"* and *"how many X"* are different questions that deserve different machinery. (The embedding stack, for what it's worth, is EmbeddingGemma-300M with a **pure-Kotlin SentencePiece tokenizer I wrote from scratch** — every off-the-shelf option needed a native library downloaded at runtime, which isn't shippable — and brute-force cosine over 504 vectors directly in Room. No vector database; 504 items don't need one.)

<p align="center"><img src="media/ask-marvel.png" width="300" alt="Ask Arcana answering 'how many Marvel do I own?' on-device: 100 Pops, 124 including duplicates, with grounding chips"></p>

<p align="center"><em>The count is computed in SQL and handed to the model as a <strong>verified fact</strong> ("100 Pops… 124 incl. duplicates"), answered <strong>on-device</strong>, grounded in the eight most valuable matches.</em></p>

### No #2 — my own quantized model, and why it doesn't ship as the default

I wanted to say I'd deployed my *own* model, not just called a vendor's. So I self-quantized Gemma 3 1B to INT4 and got it generating coherent text on the Pixel across two runtimes. The measured verdict:

| | Decode | Resident memory |
|---|---|---|
| ExecuTorch (INT4, CPU) | 19.9 tok/s | ~1.5 GB RSS |
| LiteRT (q4, CPU) | **27.4 tok/s** | 1077 MB |
| Gemini Nano | ~36 tok/s | **~0 app RSS** |

LiteRT beats ExecuTorch on *both* axes. But look at the last row. **The blocker isn't speed, it's memory.** A 682 MB model file resides at ~1.5 GB RAM, because the INT4 embedding is dequantized back to fp32 in memory. An Android app holding 1.5 GB alongside Room, Coil, and a Compose UI is a low-memory-killer casualty the moment the user switches apps. Nano costs the app *zero* resident model memory, because AICore hosts it in a separate system process. 19.9 tok/s streams fine — it's ~2.5× reading pace — but 1.5 GB doesn't fit.

So the shipping decision: Nano is the default, my LiteRT own-model is a **user-selectable** engine (behind the same seam, with a settings picker and its own benchmark column), and ExecuTorch stays as a benchmark artifact. The deliverable of that whole exploration was a *decision* backed by numbers on two axes — not a demo. "I evaluated it, hit this specific wall, measured the cost in tok/s *and RSS*, and chose accordingly" is a stronger story than either option alone.

<p align="center"><img src="media/ask-yourgemma.png" width="300" alt="Ask Arcana answering from the self-quantized Gemma with a gold 'Your Gemma' badge and real token counts"></p>

<p align="center"><em>My self-quantized Gemma 3 1B answering <strong>on-device</strong> — the gold "Your Gemma" badge, and the token counts Nano can't report.</em></p>

### No #3 — the price chart is partly synthetic, and I say so in the README

Arcana tracks each item's market value and charts it over time. The current values are real — pulled live from eBay. But the *history* behind the chart is seeded synthetic, and I put that in the README's "known limitations" in plain language.

Why not just pull the history from eBay? Because eBay's APIs don't expose a usable price series. The Browse API returns *current active listings only*. Marketplace Insights — the one sold/price endpoint — is a rolling last-90-days window, is application-gated (Limited Release), and its terms bar feeding the data into an LLM anyway. So the honest position is: real current values, real forward-accumulating tracking (every weekly sync now writes genuine history), a synthetic backfill so the feature demos on first open, and a documented paid upgrade path if a real long-run series is ever needed. Stating that reads as rigor. Hiding it and hoping no one asks reads as the opposite.

That "$3,608 week" from the opening was this feature, too — and a good measurement story to close on. Switching to real eBay prices meant syncs that were slow, paced (to stay under eBay's rate limit), and cancellable — so an interrupted sync left a *partial* batch of prices behind. The chart's series had been summing only the items stamped at each exact moment, so a half-finished sync of 178 items reported a ~$10k "portfolio," and the week-over-week delta against a complete day looked like thousands of dollars of movement. I found it by dumping the on-device database and grouping the snapshots by timestamp — the partial batches (314, 201, 102, 178 items) were right there. The fix was to value the series *as-of* each point (every item at its latest known price, so an item an interrupted sync never reached keeps its real value instead of vanishing), and to compute the weekly delta over a fixed 7-day window so a burst of same-day syncs can't masquerade as a week's movement. The header now reads +$755, the two surfaces that quote it agree by construction, and the chart is smooth. The UI was the bug report.

## What "senior + on-device AI" actually means

If there's a thesis under all of this, it's that the interesting work in on-device AI has moved. The SDKs handle the inference. What they can't do for you is tell you whether it's *working* — how fast, how often right, where it structurally can't — or make the honest call about what to ship.

So Arcana's real deliverable isn't eight AI features. It's a way of working: measure latency *and* accuracy, derive ground truth so your tests can't grade themselves, pre-register predictions and let the misses teach you, and write the limitations down where a reviewer will see them. On-device AI you can demo is table stakes now. On-device AI you can *trust* — and can prove you can trust — is the job.

---

*Arcana is a personal portfolio project. The code, the eval methodology, and the full "open questions" list are on GitHub.*
