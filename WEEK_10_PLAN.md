# Arcana — Week 10 Build Plan (RAG + the last capability)

*Following Week 9. The app is feature-complete; this week closes the `ai-samples` set and fixes the retrieval limitation carried since Week 2. Commit to repo root as `WEEK_10_PLAN.md`.*

*Assumes the Week 9 state in `WEEK_09_SUMMARY.md` — capture end-to-end, real eBay pricing, six of eight capabilities shipped, and Ask Arcana still on **lexical** retrieval (AND-semantics keyword match, `RELEVANT_LIMIT=12`).*

---

## The week's goal, in one sentence

Replace Ask Arcana's lexical retrieval with **on-device semantic search** (EmbeddingGemma via LiteRT), fix the burst-vote tie-break correctness bug, and ship the **listing writer** (`genai-writing-assistance`) — taking Arcana to **8 of 8 `ai-samples` capabilities**.

## The honest reframe: RAG does NOT fix the counting problem

`WEEK_02_SUMMARY.md` filed two Ask limitations under "→ Week-9 RAG": **synonyms** ("dragons" ≠ "Daenerys") and **undercounting** ("how many Marvel?" over 97 entries, capped at 12). **Only the first is a RAG problem.**

Semantic embeddings match *meaning*. But a query like *"how many Marvel do I own?"* or *"what did I add in 2023?"* isn't semantic at all — "2023" embeds as a *concept* near "recent" and "past," which is nothing like the literal dates in your rows. Top-k vector search over 504 items structurally **cannot count**, and cranking k until it can is just a worse `SELECT COUNT(*)`.

**So the real design work is hybrid retrieval:**
- **Aggregate / filter / count questions** ("how many Marvel", "most valuable", "added in 2023", "which are NFT-redeemable") → **structured DAO queries**. You already have `getMostValuable`; this generalises it.
- **Semantic / fuzzy questions** ("dragons", "the one with the crown", "sci-fi pops") → **vector search**.
- A **router** classifies the question and picks the path (or fuses both).

That's a stronger story than "I added RAG": *"I found that semantic search structurally can't answer counting questions, so I built a hybrid retriever that routes structured questions to SQL and semantic ones to vectors."* Own this framing in the README — it's the kind of thing that separates someone who *used* RAG from someone who *understands* it.

---

## Day 1 — The embedding gate (three decisions)

**Decision A — integration surface.** Two paths:
- **AI Edge LocalAgents RAG SDK** (official Android; embedder + vector store + retrieval, batteries included) — the sane default.
- **Raw LiteRT + `.tflite` EmbeddingGemma** — you tokenize explicitly (DJL or an equivalent SentencePiece tokenizer), run the interpreter, compute cosine similarity yourself. More control, more work. You've already built LiteRT from source, so this is *reachable* — but only justified if the SDK fights you.

Try the SDK first; fall back to raw only with a documented reason.

**Decision B — model delivery.** EmbeddingGemma-300M is **another gated Gemma download** (~a few hundred MB). Same call as the own-model engine: **side-load it** (`getExternalFilesDir("models")`, the Week-7 pattern, `chmod 644`). No download pipeline, no hosting, no redistribution question. **But note:** unlike the own-model *showcase* engine, RAG powering the default Ask experience makes this a **presence-gated core feature** — so Ask must fall back cleanly to **lexical retrieval** when the embedder is absent. That fallback is not a compromise; it's the honest degradation path, and it keeps the app working for anyone who clones the repo.

**Decision C — output dimension.** EmbeddingGemma supports **Matryoshka (MRL) output from 768 down to 128 dims** — a speed/storage-vs-accuracy knob. Your corpus is 504 *short* strings (name + franchise + series + list). Measure 768 vs 256 vs 128 on retrieval quality and pick with data, not by default. **This is a natural benchmark** — you have the habit and the harness.

**Definition of done**
- [ ] EmbeddingGemma embeds a string on-device in a dev harness; surface chosen with a reason.
- [ ] Model side-loaded + presence-gated; the lexical fallback path confirmed working when absent.
- [ ] Dimension chosen with a measured justification.

---

## Day 2 — Embed the collection + the vector store

**Tasks**
- **Document design (the part that decides quality):** what text represents an item? Name + franchise + series + list + exclusive/NFT flags. **Test a couple of shapes** — a bare name embeds far worse than a rich descriptor, and this choice matters more than the model does.
- **Vector storage:** a `VectorEntity` in Room (`collectibleId` + `FloatArray` blob) is entirely adequate for 504 items. Don't reach for a vector DB — 504 × 256 floats is trivial; brute-force cosine over the whole set is sub-millisecond. **Resisting a vector DB here is the correct engineering call**, and worth saying so.
- **Indexing job:** embed all 504 on first run (WorkManager, foreground-gated if the embedder requires it — check, per the Nano precedent). Re-embed on capture-save/import so the index stays current. Show progress; this is a one-time cost.

**Definition of done**
- [ ] All 504 items embedded and persisted; re-index triggers on save/import.
- [ ] Brute-force cosine search returns sensible top-k in negligible time.
- [ ] Document-shape choice justified by a quality comparison.

---

## Day 3 — Hybrid retrieval + the router (the week's real design)

**Tasks**
- **`SemanticRetriever`** — embed the query, cosine top-k, return items.
- **`StructuredRetriever`** — generalise the DAO path: count-by-list/franchise, filter by NFT flag, order by value, filter by date-added. This is what actually answers "how many Marvel?" **correctly** (97, not "up to 12 of them").
- **`QueryRouter`** — classify the question: *aggregate/filter* → structured; *semantic/fuzzy* → vectors; ambiguous → run both and fuse. Keep the classifier **simple and inspectable** (pattern + keyword rules over an LLM call — you'll be asked to defend it, and a rules-based router you can explain beats an opaque one you can't).
- Wire behind the existing Ask grounding seam so `AskViewModel` is largely unchanged; **lexical remains the fallback** when the embedder is unavailable.

**Definition of done**
- [ ] *"how many Marvel do I own?"* → **the correct count** (structured path), not a truncated list.
- [ ] *"any pops with dragons?"* → Daenerys/Drogon etc. via **semantic** match, with no keyword overlap.
- [ ] Router decisions are inspectable and unit-tested.
- [ ] Embedder absent → clean lexical fallback, Ask still works.

---

## Day 4 — The listing writer (`genai-writing-assistance`) — 8/8

The last capability, and a cheap fast-follow on the ML Kit GenAI groundwork (`genai-summarization`'s provisioning/status pattern applies directly).

**Tasks**
- Detail → overflow → **"Draft a listing."** Feed the item's identity + condition + **real eBay market context** (median active, comparable listings — you have this now, which makes the draft genuinely useful rather than a toy).
- ML Kit **Rewriting/Writing Assistance** API; stream into a sheet; copy-to-clipboard.
- **Reuse the Week-3 patterns:** lazy feature provisioning, `checkFeatureStatus()`, 606-until-ready, and the ARTICLE-style input-length gotcha. **And watch the safety filter** — the Week-8 finding (fixed captioner refuses fantasy/horror) is a warning: a *collectibles* corpus is full of horror/fantasy IP. Test the writer on a horror pop early.

**Definition of done**
- [ ] "Draft a listing" produces usable sale copy **on-device**, grounded in identity + market context.
- [ ] Provisioning + refusal paths handled (no crash, honest empty state).
- [ ] **8 of 8 `ai-samples` capabilities shipped.**

---

## Day 5 — Burst tie-break fix, tests, README, demo

**Tasks**
- **Burst tie-break (a correctness bug, not a nicety).** A 2-2 vote (`[62,62,32,32] → 62`) still mis-picks. The robust fix, per the Week-9 queue: **try tied candidates against the local catalog and prefer the one that resolves** (the collection has Popeye #32, not #62). Bumping to 5 frames only mitigates. This is a *known wrong-answer path in a shipped feature* — fix it.
- **Optional, if time:** re-prompt `NanoMultimodalDescriber` for what Nano is actually reliable at — a **visual description** ("a masked figure in a red suit, gold crown") rather than identity fields. Since describe no longer feeds identification, this **honestly restores the wireframe's streaming "AI describing the item" beat** without lying.
- README: the **hybrid-retrieval** story (why RAG alone can't count), 8/8 capabilities, the dimension benchmark.
- Demo: semantic query landing a keyword-free match; "how many Marvel" answering correctly.
- Tests: router, semantic retriever, structured retriever, tie-break, listing writer VM.

**Definition of done**
- [ ] Burst tie-break resolves via catalog disambiguation; unit-tested.
- [ ] README carries the hybrid-retrieval framing + 8/8.
- [ ] Demo media captured; tests green; `WEEK_10_SUMMARY.md` written.

---

## Gotchas to watch

- **RAG can't count.** Don't let a semantic top-k pretend to answer aggregate questions — route them to SQL. This is the week's central insight; getting it wrong produces a confidently wrong app.
- **Document shape > model choice.** What you embed matters more than the embedder. Test shapes.
- **Don't reach for a vector DB.** 504 items × N dims is brute-force territory. Over-engineering here is the trap the project keeps catching.
- **EmbeddingGemma is gated + side-loaded** → presence-gate it, and **keep lexical as the working fallback** (unlike the own-model engine, Ask is a core feature, not a showcase toggle).
- **ML Kit GenAI safety filters** bit you in Week 8 — test the listing writer against a horror/fantasy pop early, not on Day 5.
- **Foreground-gating** applies to on-device inference (Week 3/4 findings) — check whether the embedder can run in a background WorkManager job or must be foregrounded.
- **Run `:app:testDebugUnitTest` before committing** (Week-9 lesson — two commits shipped latent test-compile breaks).

## What Week 11+ inherits

**8 of 8 `ai-samples` capabilities, feature-complete.** What remains is no longer feature work:
- **The Snapdragon cross-vendor benchmark** — parked since Week 7, now the most attractive remaining item: run the *same* `LiteRtGeminiService` on a Hexagon NPU and see whether the accelerator engages where Tensor's didn't. A conscious purchase, evaluated as portfolio enrichment.
- **Eval harness** (roadmap Week 10) — extend the benchmark aggregation from *latency* to *accuracy*: cascade identification accuracy across your real photo set, retrieval quality across query types.
- **Polish / deferred:** multi-module split, Edit Details, capture history, the download-pipeline learning spike.
