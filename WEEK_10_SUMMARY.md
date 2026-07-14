# Week 10 — Completion Summary (handoff for Week 11)

Status: **complete.** The app is **feature-complete: 8 of 8 `ai-samples` on-device capabilities shipped.**
Week 10 replaced Ask Arcana's lexical grounding with on-device **hybrid retrieval** (the week's real design),
shipped the **listing writer** (the last capability), and fixed a known-wrong capture path.

**The headline:** *Ask Arcana now answers "how many Marvel do I own?" with the **exact count (100, not a
truncated top-k)** by routing counting questions to SQL, while "any pops with dragons?" surfaces **Vhagar**
(a dragon whose name contains no form of "dragon") by on-device semantic search — a hybrid retriever with a
rules-based router that I can read and defend. EmbeddingGemma-300M runs on the raw LiteRT interpreter with a
**pure-Kotlin Gemma tokenizer I wrote from scratch** (262k vocab, zero native deps). Detail → "Draft a
listing" writes eBay-ready sale copy on-device via ML Kit Rewriting — with **no eBay data ever fed to the
model** (eBay's 2025 API terms). Verified end-to-end on the Pixel against the real 504-item collection.*

---

## 1. What shipped (per the Week-10 plan)

- **Day 1 — the embedding foundation.** `core/ai/rag/`: `CollectionEmbedder` seam (query/document
  asymmetry), `EmbeddingGemmaEncoder` (LiteRT interpreter, runtime tensor introspection, presence-gated on
  both side-loaded files), `EmbeddingMath` (MRL truncate + L2-normalize + cosine + top-k), `CollectionDocument`
  (doc shape + EmbeddingGemma task prefixes), and — the real cost of the raw path — a **pure-Kotlin Gemma
  SentencePiece BPE tokenizer** (`SentencePieceVocab` protobuf parser + `GemmaSentencePieceTokenizer`).
- **Day 2 — the vector store + index.** `VectorEntity`/`VectorDao` (768-dim float BLOB, CASCADE, docHash),
  `CollectionVectorStore` (brute-force cosine, MRL-truncated at read time), `CollectionIndexer` (incremental
  by document hash), `EmbeddingIndexWorker`/`Scheduler` (headless CoroutineWorker — LiteRT CPU needs no
  foreground), enqueued on launch / capture-save / import. **Document shape chosen by measured A/B.**
- **Day 3 — hybrid retrieval (the real design).** `QueryRouter` (rules-based, inspectable), `StructuredRetriever`
  (SQL count/filter/rank → authoritative facts), `SemanticRetriever` (vectors), `HybridCollectionRetriever`
  (the `CollectionRetriever` grounding seam, lexical fallback). `AskViewModel` moved onto the seam; the prompt
  states verified facts exactly.
- **Day 4 — the listing writer (8/8).** `ListingComposer` (raw text from the item's OWN data only),
  `MlKitListingWriter` (ML Kit GenAI Rewriting, streaming, provisioning/refusal handled). Detail overflow →
  "Draft a listing" → sheet: streams the copy, shows the eBay median **separately**, copy-to-clipboard.
- **Day 5 — burst tie-break fix + docs.** The Week-9 queued correctness bug fixed; README + this summary.

## 2. Key decisions (each surfaced, most measured)

- **Decision A — raw LiteRT, not the AI Edge RAG SDK.** The SDK (0.1.0) ships no EmbeddingGemma embedder
  (Gecko only, verified by inspecting the AAR) and its vector store over-engineers 504 items. So EmbeddingGemma
  runs directly on the LiteRT interpreter; storage is Room + brute-force cosine.
- **The tokenizer, from scratch.** Gemma's tokenizer is SentencePiece **BPE** (byte-fallback, digit-split,
  262k vocab). Every off-the-shelf option (DJL, the RAG SDK's SentencePiece) needs a native `.so` that
  downloads at runtime — unusable in a shipped Android app — so it's **pure Kotlin, zero native deps, fully
  JVM-tested.** A genuinely strong artifact.
- **Decision C — MRL dimension 256.** Measured on-device: top-1 retrieval identical at 768/256/128; 256 ships
  (⅓ the storage, headroom over 128).
- **Document shape = Natural.** Benchmarked 3 shapes over labeled queries on-device: BareName 4/5, Labelled
  5/5, Natural 5/5 top-1. Natural ships (ties best, cleanest — the `Series:` scaffolding *dilutes* the
  embedding when the name already carries the franchise).
- **eBay-AI compliance.** eBay's 2025 API terms ban training on eBay Content and ingesting *Restricted-API*
  data into gen-AI. Our Browse API is standard (not restricted), but to stay clear entirely, **no eBay data
  ever enters an LLM prompt** — the listing is written from the item's own data; the median shows separately.
  (Recorded as a durable decision.)
- **LiteRT 1.4.2, not 2.1.6.** The 2.x split `litert`/`litert-api` share a namespace and break AGP 9's
  manifest merger; 1.4.2 is a single AAR with the classic `org.tensorflow.lite.Interpreter`.

## 3. The numbers (real, on the Pixel 10 Pro XL)

- **Embedding:** EmbeddingGemma seq256 → 768-dim, cold ~1.3 s / warm ~200–355 ms per item. Full 504-item
  index builds in ~1.5–3.8 min in the background; incremental after (a re-launch embedded **0**, skipped 504).
- **Hybrid retrieval, live over 504:** "how many Marvel" → **100** (124 incl. duplicates), $2,263 (structured);
  "how many pops" → **504**, $29,811; "NFT redeemable" → **141**, $15,702; "dragons" → Daemon/Daenerys/**Vhagar**
  (semantic); "spooky horror" → Pennywise/Annabelle/Freddy.
- **Listing writer:** on-device, foreground; Aang 7.1 s, Pennywise 4.8 s. **Horror pops are NOT safety-refused**
  (unlike the Week-8 fixed captioner) — the writer works across the whole corpus.

## 4. Prediction scorecard (pre-registered)

- "EmbeddingGemma embeds < 150 ms warm" → **close: ~200–355 ms warm** (short strings faster; the seq256 model
  is ~200 ms once fully warm).
- "256-dim ships" → **right, measured** (top-1 identical to 768).
- "ML Kit Rewriting is the fit for `genai-writing-assistance`" → **right**, but as a **tone-transform on a
  composed draft** (ELABORATE), not free-form generation; and it needed the **foreground** (ErrorCode 30 from
  background) and did **not** refuse horror (the open risk).
- "The plan's 'rich descriptor always beats bare name'" → **wrong, and measured**: for a name that already
  implies its franchise, bare can match a franchise query as well or better; the honest comparison is top-1
  accuracy over a query set, not single-item cosine.

## 5. The honest reframe that shaped the week

RAG does **not** fix counting. Semantic top-k over 504 vectors structurally cannot answer "how many Marvel?"
— it returns k of them, not the total. The deliverable is therefore **hybrid retrieval**: a rules-based router
sends aggregate/filter questions to SQL (the real count, handed to the LLM as fact) and fuzzy ones to vectors.
That is a stronger story than "I added RAG": *I found semantic search can't count, so I built a hybrid
retriever and a router I can defend.*

## 6. Gotchas worth carrying forward

- **Nano/ML Kit GenAI is foreground-only** (ErrorCode 30 from a background instrumented test). The listing
  writer works because "Draft a listing" is a user tap on a visible screen; the index embedder (LiteRT CPU)
  is *not* Nano and runs headless.
- **`connectedAndroidTest` clean-installs and wipes side-loaded `/files/models`.** For on-device tests that
  need the side-loaded model, use `installDebug + installDebugAndroidTest + am instrument` (not the gradle
  connected task).
- **The DB schema bump wiped the collection** (`fallbackToDestructiveMigration`) — re-import the fixture CSV.
  Real migrations are deliberately deferred to schema-freeze (recorded).
- **Run `:app:testDebugUnitTest` before committing** — held; 158 JVM tests green.

## 7. Files & artifacts

- **New (`core/ai/rag/`):** `CollectionEmbedder`, `EmbeddingGemmaEncoder`, `EmbeddingTokenizer`,
  `GemmaSentencePieceTokenizer`, `SentencePieceVocab`, `EmbeddingMath`, `CollectionDocument`,
  `CollectionVectorStore`, `CollectionIndexer`, `QueryRouter`, `CollectionRetriever`, `StructuredRetriever`,
  `SemanticRetriever`, `HybridCollectionRetriever`, `EmbeddingBenchmark`.
- **New (`core/ai/writing/`):** `ListingComposer`, `ListingWriter`, `MlKitListingWriter`.
- **New (data):** `VectorEntity`, `VectorDao`, `EmbeddingIndexWorker`, `EmbeddingIndexScheduler`.
- **Changed:** `AskViewModel` (retriever seam + facts), `CollectibleRepository`/`Impl` + `CollectibleDao`
  (matching/nftRedeemable/addedInYear + 2 queries), `DetailScreen` (Draft-a-listing sheet), `OcrBurstVote` +
  `CaptureCascade` (tie-break), `ArcanaDatabase` v2→v3, `AiModule`/`DataModule`, `ArcanaApplication`,
  `SettingsScreen`/`SettingsViewModel` (RAG + listing harness cards), `ImportScreen`/`CaptureReviewViewModel`
  (re-index triggers).
- **Tests:** RAG (math/document/tokenizer/vocab/store/indexer/router/structured/hybrid) + `ListingComposer` +
  `AskViewModel` + `OcrBurstVote` (JVM); `CollectionRagE2e`/`RealCollectionQuery`/`HybridRetrieverE2e`/
  `RewritingSafety` (instrumented). **158 JVM tests green.**
- **New deps:** `com.google.ai.edge.litert:litert:1.4.2`, `com.google.mlkit:genai-rewriting:1.0.0-beta1`.
- **Side-loaded (gated, not in the APK):** `embeddinggemma-300m.tflite` + `sentencepiece.model` in
  `getExternalFilesDir("models")`.

## 8. What Week 11 inherits

**8 of 8 `ai-samples` capabilities, feature-complete.** What remains is no longer feature work:
- **The Snapdragon cross-vendor benchmark** — parked since Week 7; run the same `LiteRtGeminiService` on a
  Hexagon NPU to see whether the accelerator engages where Tensor's didn't. A conscious hardware purchase.
- **Eval harness** — extend the benchmark from *latency* to *accuracy*: cascade identification across the real
  photo set, retrieval quality across query types.
- **Deferred robustness (optional):** re-prompt `NanoMultimodalDescriber` for a visual description ("a masked
  figure in a red suit, gold crown") — since describe no longer feeds identification, this honestly restores
  the wireframe's streaming "AI describing the item" beat. Not started this week (docs took Day 5).
- **Polish:** Room migrations at schema-freeze (baseline + `exportSchema` + `@AutoMigration`); multi-module
  split; Edit Details; capture history.

## 9. Reading order for Week 11

`WEEK_10_SUMMARY.md` (this) → repo `CLAUDE.md` → `DESIGN.md` → `core/ai/rag/HybridCollectionRetriever.kt`
(+ `QueryRouter`) for the retrieval design → `core/ai/writing/MlKitListingWriter.kt` for the last capability.
