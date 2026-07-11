# Arcana — Architecture Design

*Living document. Last updated end of Conversation 2. Revisit at Week 4 (post-cascade), Week 7 (post-capture), and Week 9 (post-RAG).*

---

## Why Arcana exists

Arcana is a portfolio piece built to demonstrate Google's [`ai-samples`](https://github.com/android/ai-samples) on-device AI capabilities in a real Android app with a real dataset (a 283-item Funko Pop collection). Every architectural decision should serve that goal. Features that don't exercise an `ai-samples` capability are gold-plating.

The capabilities being demonstrated, and the Arcana features that exercise them:

| `ai-samples` capability | Arcana feature |
|---|---|
| `gemini-hybrid` (Firebase Hybrid Inference, `InferenceMode.PREFER_ON_DEVICE`) | All LLM calls in the app; cascade fallback logic |
| `gemini-multimodal` (cloud vision) | Cascade Stage 5 — image-based identification when on-device fails |
| `genai-image-description` (Gemini Nano on-device classification) | Cascade Stage 2 — first-pass classification |
| `magic-selfie` (ML Kit Subject Segmentation) | Cascade Stage 1 — isolate subject from background |
| ML Kit Text Recognition | Cascade Stage 3 — read Pop number off the box |
| LiteRT (on-device embeddings) | "Ask Arcana" RAG semantic search |
| `genai-summarization` | Weekly portfolio summary feature |
| `genai-writing-assistance` | Listing description writer (P2) |

Beyond `ai-samples` but core to the portfolio narrative: deploying my **own** Gemma 3 1B on-device as a **user-selectable** engine for "Ask Arcana," behind the same `GeminiService` interface. I self-quantized and deployed with ExecuTorch (Week 5), then evaluated Google's LiteRT-LM (Week 6) and measured both against Gemini Nano. LiteRT q4-on-CPU won (27.4 tok/s / 1077 MB; the Tensor G5 TPU is #7787-broken and its PowerVR GPU is a dead end), so it ships as `LiteRtGeminiService`; ExecuTorch stays a benchmark column and the "producer" capability. Nano remains the default (zero app-resident memory); the own-model is opt-in via a Settings engine picker (Week 7).

---

## Design goals

**Privacy by default.** Every inference attempts on-device first. Cloud is fallback, never default. Where each call executed is captured in `InferenceMetadata.executedOn` and surfaced in a debug overlay.

**Honest abstractions.** Features depend on five interfaces (`GeminiService`, `PriceProvider`, `CollectionImporter`, `CatalogProvider`, `CollectibleRepository`) and one sealed domain model (`Collectible`). They never import Firebase, ML Kit, LiteRT, or HTTP client types directly. Swapping any backend is a DI binding change.

**Real benchmarks.** `InferenceMetadata` captures total latency, first-token latency, and output token count on every inference call from Day 1. Benchmarks are first-class data, not a Week 10 addition.

**Ship the primary use case first.** The visual identification cascade is the portfolio centerpiece. P0 features (import, RAG chat, value tracking) make Arcana useful day to day. Both matter; don't let the showcase eat the core product.

**Generic from day one, single category in v1.** Schema, domain model, and interfaces all support arbitrary categories. v1 ships Funko only. FigPin and Pokemon are documented holes — enum values exist, no impls wired up. Adding them later is new files, not schema migrations.

---

## The five pluggable interfaces

Same architectural pattern applied to five different concerns. Each interface is defined once; multiple impls are wired via Hilt; features depend on the interface, never the impl.

| Interface | Concern | v1 impls |
|---|---|---|
| `GeminiService` | LLM inference (text generation, embeddings) | `HybridGeminiService`, `LiteRtGeminiService` (own-model, Week 7), `DelegatingGeminiService` (picker router), `FakeGeminiService` |
| `PriceProvider` | Fetch current value plus market context | `EbayBrowsePriceProvider` |
| `CollectionImporter` | Parse external collection exports | `HobbyDbCsvImporter` |
| `CatalogProvider` | Look up collectible metadata by UPC, name, or image | `LocalCollectionCatalogProvider`, `UpcItemDbCatalogProvider`, `EbayBrowseCatalogProvider`, `CloudVisionCatalogProvider` |
| `CollectibleRepository` | Local persistence + query API | `CollectibleRepositoryImpl` |

The consistency is the architectural story. An engineer reviewing one interface will recognize the same pattern in the others.

---

## The two pipelines

Two distinct AI pipelines share the `GeminiService` and `CatalogProvider` infrastructure but operate independently.

### Pipeline A — Image cascade (the capture flow)

Triggered by the user tapping the shutter in the camera screen. The cascade runs visibly on the frozen captured frame, with each stage producing user-visible feedback. Confidence gates short-circuit the chain on success.

```
User taps shutter → frame freezes
    │
    ▼
[1] ML Kit Subject Segmentation         — isolate Pop from background, on-device
    │  Visual: animated outline appears around detected subject
    │
    ▼
[2] Gemini Nano Image Description       — first-pass classification, on-device
    │  Visual: streaming text — "Marvel character… red and black… Deadpool?"
    │  confidence ≥ 0.85 → use as catalog query
    │
    ▼
[3] ML Kit Text Recognition             — OCR Pop number from box side
    │  Visual: floating "#1234" callout near the box number
    │
    ▼
[4] CatalogProvider chain (UPC → name → image identification)
    │  Visual: "Checking your collection… Looking up UPCitemdb… eBay…"
    │  short-circuit on first hit
    │  ├─ LocalCollectionCatalogProvider     → owned item; instant
    │  ├─ UpcItemDbCatalogProvider           → free public DB; 100/day
    │  ├─ EbayBrowseCatalogProvider          → free with signup; broad coverage
    │  └─ CloudVisionCatalogProvider         → Gemini multimodal cloud fallback
    │     Visual: on-device/cloud badge flips
    │
    ▼
CollectibleMetadata (typed, structured)
    │
    ▼
Capture review screen
```

**Capture gesture:** standard tap-to-capture shutter button. Barcode-scanner fallback button always available in the corner. When cascade returns low confidence, the review screen leads with "Wrong? Scan barcode" prompt; when confidence is high, the prompt is a small tertiary link.

**The cascade's UI is the demo.** Each stage produces visible, designed output on the frozen frame — not as debug spew but as designed features. Segmentation outline, OCR callout, on-device-vs-cloud badge. The "AI happening in front of you" quality is what separates Arcana's capture flow from generic camera apps.

### Pipeline B — RAG chat ("Ask Arcana")

Triggered by the chat feature. Stateful within a session.

```
User message
    │
    ▼ embed query (LiteRT)
[CollectionVectorStore.topK(query, k=5)]
    │
    ▼ retrieved items + conversation history
[Build grounded prompt]
    "You are Arcana, an assistant for this collector's personal collection.
     Answer based only on the context below. If you can't answer from context, say so.
     Context: {top-k items serialized}
     History: {last N turns}
     Question: {user message}"
    │
    ▼
GeminiService.generateText(prompt, Auto)
    │
    ▼ streaming response → UI
```

Multi-turn handling is manual. `ChatViewModel` holds `List<ChatMessage>` in memory; `ChatRepository` serializes the last N turns into each prompt. N is tuned to Nano's context window (~2,048 tokens; verify at integration).

---

## Capture review screen

After the cascade completes, the review screen presents:

**Always shown:**
- Captured image (segmented, background removed) — large, top of screen
- Identification: name, series, exclusive info
- Source-of-identification badge: "Identified on-device" / "Identified via eBay catalog" / "Identified via cloud"
- Confidence indicator (thin bar or percentage)
- Market section (rendered as soon as identification settles):
  - Median active listing price
  - Top 3-5 active eBay listings, each with seller rating and "View on eBay" link via Chrome custom tabs
  - Freshness timestamp ("Listings from 30 seconds ago")
  - Median sold price (Week 4 addition, requires PriceCharting subscription)

**Conditional:**
- "You already own X of these" — when identification matches an item in the user's collection
- "Wrong? Scan barcode" — prominent if low confidence, small tertiary link if high confidence

**Primary action:**
- "Save to collection" — for unowned items
- "Add another to my collection" — for owned items (no greyed-out state; user is in charge)

After tapping Save, the item is written to Room with whatever fields the cascade and catalog returned. Gap fields (price paid, acquired from, storage location, notes) are all null. The user lands on the detail screen.

**Gap fields are not collected at capture.** They go through a separate "Edit details" affordance on the detail screen. Capture is for capturing; details are for details. Don't combine them.

---

## Detail screen

The canonical view for a single collectible. Used both post-save and as the long-term "I want to look at this Pop later" view.

Sections (top to bottom):
- **Header:** image, name, series, exclusive info, "NFT Redemption Pop" badge if applicable (a provenance label, not state)
- **Owned badge:** "You own X of these" — links to all owned copies for multi-quantity items
- **Market section:** live on render — median sold, median active, top listings with View on eBay links, freshness timestamp. Same component used on the capture review screen.
- **Value history chart:** time-series from `value_snapshots`, defaulting to past 90 days
- **"Snapshot today's price" button:** explicit action that commits a manual `ValueSnapshotEntity` row. Naming is deliberate — this is *adding to history*, not *refreshing display*. Debounced: if the last snapshot is from the same day and within 5% of the current value, the button surfaces "Already up to date" instead of creating a duplicate row.
- **"Edit details" affordance:** opens the gap-fill editor for price paid, acquired from, storage location, notes
- **Secondary actions at bottom:** "Add another" / "Delete from collection"

The market section being live-on-view eliminates any need for a "Refresh price" button. The freshness timestamp tells the user the data is fresh. "Snapshot today's price" is a different action — it's the user choosing to commit a price point to their portfolio's tracked history.

---

## Key abstraction — GeminiService

```kotlin
interface GeminiService {
    fun generateText(
        prompt: String,
        routingHint: RoutingHint = RoutingHint.Auto,
    ): Flow<InferenceResult>
}

sealed interface InferenceResult {
    data class Streaming(val partialText: String) : InferenceResult
    data class Success(val fullText: String, val metadata: InferenceMetadata) : InferenceResult
    data class Error(val cause: Throwable, val fallbackAvailable: Boolean) : InferenceResult
}

data class InferenceMetadata(
    val executedOn: InferenceLocation,
    val totalLatencyMs: Long,
    val firstTokenLatencyMs: Long?,
    val outputTokenCount: Int?,
)

enum class InferenceLocation { OnDevice, Cloud }
enum class RoutingHint { Auto, PreferOnDevice, OnlyOnDevice, OnlyCloud }
```

### On RoutingHint

`RoutingHint` is an escape hatch, not a primary control surface. Features pass `Auto` almost always. The escape hatch handles:

- `OnlyOnDevice` — "Ask Arcana" when offline. The caller knows they're offline; they shouldn't let the service attempt a network call and time out.
- `OnlyCloud` — benchmark mode, forcing cloud for direct comparison.

`PreferOnDevice` is documentation-only and behaves identically to `Auto`.

### Implementations

**`HybridGeminiService`** — production. Backed by Firebase AI Logic with `InferenceMode.PREFER_ON_DEVICE`. Handles `FEATURE_NOT_FOUND` (ErrorCode 606) by routing to cloud. Handles `BUSY` by serializing inference calls behind a `Mutex`. Captures first-call vs steady-state latency separately.

**`FakeGeminiService`** — tests and Compose previews. Returns configurable canned responses with configurable latency. Emits `Streaming` events on a coroutine delay.

**`LiteRtGeminiService`** — Week 7 addition (the own-model engine; supersedes the placeholder `ExecuTorchGeminiService` this doc used to name — see the Week-6 decision). My self-quantized Gemma 3 1B (INT4) running in-process on the **CPU** via the MediaPipe LLM Inference runtime (LiteRT-LM under the hood), loading a **side-loaded** `.litertlm`. Implements `OwnModelEngine` (`GeminiService` + `isModelAvailable()`) so the picker can presence-gate it. A real rewrite of the AI layer behind the same interface boundary, not a thin wrapper — the abstraction holds at the call site; the impl complexity (native runtime, streaming listener → `Flow`, single-inference `Mutex`, side-load lifecycle) is real. CPU-only is deliberate: the Tensor G5 TPU (#7787) and PowerVR GPU are measured dead ends, re-confirmed in-app by the runtime's own accelerator probe.

**`DelegatingGeminiService`** — Week 7 addition. The `GeminiService` actually injected app-wide. For `RoutingHint.Auto` it routes to whichever engine the Settings picker selected (Nano → `HybridGeminiService` prefer-on-device; Your Gemma → `LiteRtGeminiService`; Cloud → `HybridGeminiService` cloud). Explicit hints (`OnlyOnDevice`/`OnlyCloud`/`OnlyOwnModel`) bypass the selection — used by the benchmark harness so each engine stays one column on one seam.

---

## CatalogProvider chain

```kotlin
interface CatalogProvider {
    val sourceName: String
    val supportsLookupByUpc: Boolean
    val supportsLookupByName: Boolean
    val supportsImageSearch: Boolean

    suspend fun lookupByUpc(upc: String): CatalogEntry?
    suspend fun lookupByName(query: String, limit: Int = 10): List<CatalogEntry>
    suspend fun identifyFromImage(imageBytes: ByteArray): CatalogEntry?
}

data class CatalogEntry(
    val sourceName: String,
    val externalId: String,        // UPC, eBay item ID, opaque
    val name: String,
    val brand: String,
    val series: List<String>,
    val number: String?,            // Pop number for Funko
    val exclusiveTo: String?,
    val imageUrl: String?,
    val confidence: Float,
)
```

Methods that an impl doesn't support throw `UnsupportedOperationException`. Null returns mean "supported but no match found." The chain consults `supports*` flags to skip non-applicable providers.

### v1 impls

| Impl | UPC | Name | Image | Notes |
|---|---|---|---|---|
| `LocalCollectionCatalogProvider` | yes | yes | no | Queries Room. Always tried first. Free, instant. |
| `UpcItemDbCatalogProvider` | yes | yes | no | Free public API. 100 lookups/day. No signup. |
| `EbayBrowseCatalogProvider` | yes | yes | no | Free with developer signup. 5k/day. Broad coverage. |
| `CloudVisionCatalogProvider` | no | no | yes | Image identification via Gemini multimodal. |

`PriceChartingCatalogProvider` (paid; UPC and name) is a documented upgrade path. If the user subscribes, it slots in ahead of `UpcItemDbCatalogProvider` because it has richer Funko-specific data.

### Chain orchestration

```kotlin
class CatalogProviderChain(
    private val providers: List<CatalogProvider>,
) {
    suspend fun lookupByUpc(upc: String): CatalogEntry? {
        for (provider in providers) {
            if (!provider.supportsLookupByUpc) continue
            try {
                provider.lookupByUpc(upc)?.let { return it }
            } catch (e: RateLimitException) {
                continue  // skip rate-limited provider
            }
        }
        return null
    }
    // Similar for lookupByName and identifyFromImage
}
```

Chain order is configured in the Hilt module, not hardcoded. Order is a config decision — swappable for benchmarks or for a future premium tier that reshuffles based on subscription state.

---

## PriceProvider with market context

```kotlin
interface PriceProvider {
    val sourceName: String
    val supportedCategories: Set<CollectibleCategory>
    suspend fun fetchPrice(collectible: Collectible): PriceResult
}

sealed interface PriceResult {
    data class Success(
        val valueCents: Int,
        val source: ValueSource,
        val confidence: PriceConfidence,
        val marketContext: MarketContext?,
        val fetchedAt: Instant,
    ) : PriceResult
    data class Unavailable(val reason: String) : PriceResult
    data class RateLimited(val retryAfter: Duration) : PriceResult
}

data class MarketContext(
    val medianSoldPriceCents: Int?,       // null if not supported (eBay Browse can't see sold)
    val medianActivePriceCents: Int?,
    val recentSales: List<SoldListing>,    // empty if not supported
    val activeListings: List<ActiveListing>,
    val sampleSize: Int,
)

data class ActiveListing(
    val title: String,
    val priceCents: Int,
    val sellerRating: Float?,
    val ebayUrl: String,
)

data class SoldListing(
    val priceCents: Int,
    val soldAt: Instant,
    val condition: String?,
)

enum class PriceConfidence { High, Medium, Low }
```

`PriceProvider.fetchPrice()` returns both a primary value and the market context the value was derived from. This makes the answer to "what is this worth?" honest by exposing the underlying data and letting the UI decide how to present it.

### v1 impl — EbayBrowsePriceProvider

Uses eBay's Browse API to fetch active listings. Computes median active price as the primary value. Returns top 5 listings in `MarketContext.activeListings`. Cannot populate `recentSales` or `medianSoldPriceCents` — eBay's Marketplace Insights API (which has sold data) is gated.

### v2 impl — PriceChartingPriceProvider

Paid subscription. Has sold-listing data, populates the full `MarketContext`. Documented upgrade path.

The provider chain orchestration mirrors `CatalogProviderChain`: ordered list, try in sequence, short-circuit on success.

---

## CollectionImporter and the ImportedItem boundary

```kotlin
interface CollectionImporter {
    val sourceName: String                  // "HobbyDB", "PopGrinder"
    val supportedMimeTypes: Set<String>
    suspend fun parse(uri: Uri): ImportResult
}

sealed interface ImportResult {
    data class Success(
        val items: List<ImportedItem>,
        val itemsParsed: Int,
        val itemsSkipped: Int,
        val warnings: List<String>,
    ) : ImportResult
    data class Failed(val cause: Throwable, val message: String) : ImportResult
}

data class ImportedItem(
    val sourceId: String,                   // HDBID for HobbyDB; opaque elsewhere
    val sourceName: String,
    val category: CollectibleCategory,

    // Common fields
    val name: String,
    val brand: String,
    val quantity: Int,
    val estimatedValueCents: Int?,
    val itemCondition: String?,
    val packagingCondition: String?,
    val dateAdded: LocalDate?,
    val imageUrl: String?,
    val series: List<String>,
    val productionTags: List<String>,

    // Category-specific metadata (nullable; populated when applicable)
    val funkoMetadata: FunkoImportMetadata?,
    // val figPinMetadata: FigPinImportMetadata?,     // Future
    // val pokemonMetadata: PokemonImportMetadata?,   // Future

    // Gap-fillable fields — usually null on import
    val pricePaidCents: Int? = null,
    val acquiredFrom: String? = null,
    val datePurchased: LocalDate? = null,
    val storageLocation: String? = null,
    val privateNotes: String? = null,

    val warnings: List<String> = emptyList(),
)

data class FunkoImportMetadata(
    val upc: String?,
    val popNumber: String?,
    val exclusiveTo: String?,
    val isNftRedeemable: Boolean,
    val scale: String?,
    val releaseDate: LocalDate?,
    val hdbcNumber: String?,
)
```

`ImportedItem` is the contract between importers and the repository. Importers map their source format → `ImportedItem`. The repository consumes `ImportedItem` → `CollectibleEntity` + category metadata + junction rows.

### v1 impl — HobbyDbCsvImporter

Parses HobbyDB CSV exports. Handles known data quirks:
- Strips `=HYPERLINK(...)` wrappers from URL columns
- Parses semicolon-and-comma-separated `Series` and `Production Status` into `List<String>`
- Casts `UPC` to String to preserve leading zeros
- Treats `Exclusive To = "NFT Redeemable"` as a meaningful flag

### Import is one-shot in v1

The user imports their CSV once at onboarding. After that, the user is the source of truth for their collection. Re-import is not a v1 feature.

If the user wants to import another CSV later (Settings → "Import another CSV"), the import is **append-only**: new rows are added, existing rows are untouched. There is no re-import merge logic, no `userEditedFields` mechanism, no user-edit conflict handling. Duplicates from re-import are the user's problem to resolve manually.

This is a deliberate simplification. The "import and forget" model is correct for v1; sophisticated re-import logic is a v2 feature when there's a real need.

### v2 — PopGrinderCsvImporter

Documented but not in v1. Removes the HobbyDB Premium paywall from onboarding for users who use PopGrinder instead. Add when v1 is shipping.

### Failure mode — lenient with warnings

Single-row parse failures log a warning and skip that row. Remaining rows succeed. Strict "all or nothing" is wrong for a personal-collection app. Best-effort partial-row insertion is also wrong — it pollutes the data. Either parse cleanly or skip.

---

## Room schema

### Schema philosophy — common table + per-category metadata tables

```
collectibles                  # Common to all categories
  ├─ funko_metadata           # 1:1 with collectibles where category=Funko
  ├─ figpin_metadata          # Future
  └─ pokemon_metadata         # Future

collectible_series            # Junction: collectible ↔ series
collectible_production_tags   # Junction: collectible ↔ tag

series                        # Canonical series names
production_tags               # Canonical tag names

value_snapshots               # 1:N with collectibles, time-series
```

Generic queries operate on `collectibles`. Category-specific queries join the metadata table. Adding a new category creates a new metadata table; existing data and code are untouched.

### Core entities

```kotlin
@Entity(tableName = "collectibles")
data class CollectibleEntity(
    @PrimaryKey(autoGenerate = true) val localId: Long = 0,
    val category: CollectibleCategory,
    val origin: CollectibleOrigin,
    val sourceId: String?,                   // HDBID, PopGrinder ID, or null for captured
    val sourceName: String?,                 // "HobbyDB", "PopGrinder", null for captured
    val name: String,
    val brand: String,
    val imageUrl: String?,
    val itemCondition: String,
    val packagingCondition: String,
    val quantity: Int,
    val estimatedValueCents: Int,
    val lastKnownValueCents: Int?,
    val lastKnownValueSource: ValueSource?,
    val lastKnownValueAt: Instant?,
    val pricePaidCents: Int?,
    val acquiredFrom: String?,
    val datePurchased: LocalDate?,
    val dateAdded: LocalDate,
    val storageLocation: String?,
    val privateNotes: String?,
)

enum class CollectibleCategory { Funko, FigPin, Pokemon }
enum class CollectibleOrigin { HobbyDbImport, PopGrinderImport, ArcanaCapture, Wishlist }

@Entity(
    tableName = "funko_metadata",
    foreignKeys = [ForeignKey(
        entity = CollectibleEntity::class,
        parentColumns = ["localId"],
        childColumns = ["collectibleLocalId"],
        onDelete = ForeignKey.CASCADE,
    )],
)
data class FunkoMetadataEntity(
    @PrimaryKey val collectibleLocalId: Long,
    val upc: String,
    val popNumber: String?,
    val exclusiveTo: String?,
    val isNftRedeemable: Boolean,            // Catalog fact about the Pop, not user state
    val scale: String?,
    val releaseDate: LocalDate?,
    val hdbcNumber: String?,
)
```

`isNftRedeemable` is a catalog fact ("this is a Pop that originated through NFT redemption") used as a display badge on the detail screen. It is not a tracked state.

`CollectibleOrigin.Wishlist` is unused in v1; it exists so a v2 wishlist feature can be added without schema migration.

### Junction tables

```kotlin
@Entity(tableName = "series")
data class SeriesEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    @ColumnInfo(index = true) val name: String,
)

@Entity(
    tableName = "collectible_series",
    primaryKeys = ["collectibleLocalId", "seriesId"],
)
data class CollectibleSeriesCrossRef(
    val collectibleLocalId: Long,
    val seriesId: Long,
)

// Same pattern for production_tags / collectible_production_tags
```

### Value snapshots

```kotlin
@Entity(
    tableName = "value_snapshots",
    indices = [Index("collectibleLocalId"), Index("snapshotAt")],
)
data class ValueSnapshotEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val collectibleLocalId: Long,
    val valueCents: Int,
    val source: ValueSource,
    val trigger: SnapshotTrigger,
    val snapshotAt: Instant,
)

enum class ValueSource { HobbyDbImport, PopGrinderImport, EbayBrowse, PriceCharting, ManualEntry }
enum class SnapshotTrigger { Import, WeeklySync, UserRefresh, ManualEdit }
```

`source` and `trigger` are split deliberately because they answer different questions. `source` is "where did the number come from" (matters for confidence). `trigger` is "what caused this row to be written" (matters for analytics and chart filtering).

---

## Domain model — sealed Collectible

```kotlin
sealed interface Collectible {
    val localId: Long
    val name: String
    val brand: String
    val imageUrl: String?
    val estimatedValueCents: Int
    val lastKnownValueCents: Int?
    val quantity: Int
    val itemCondition: String
    val packagingCondition: String
    val series: List<String>
    val productionTags: List<String>
    val dateAdded: LocalDate
    val pricePaidCents: Int?
    val storageLocation: String?
}

data class FunkoPop(
    override val localId: Long,
    override val name: String,
    // ... all common fields ...
    val upc: String,
    val popNumber: String?,
    val exclusiveTo: String?,
    val isNftRedeemable: Boolean,
) : Collectible

// Future:
// data class FigPin(...) : Collectible
// data class PokemonCard(...) : Collectible
```

`CollectibleRepository.getById(localId): Collectible?` returns the sealed interface from day one. ViewModels handle polymorphism via exhaustive `when` even in v1, with `NotImplementedError` branches for FigPin and Pokemon. This forces every call site to handle polymorphism correctly before the second category ships.

```kotlin
@Composable
fun DetailScreen(collectible: Collectible) {
    when (collectible) {
        is FunkoPop -> FunkoDetailScreen(collectible)
        // is FigPin -> FigPinDetailScreen(collectible)       // Future
        // is PokemonCard -> PokemonDetailScreen(collectible) // Future
    }
}
```

---

## Onboarding and the HobbyDB integration

### Two-path onboarding

First launch presents two equally weighted options:

| Path | Free? | Audience |
|---|---|---|
| **Start fresh** | Free | New collectors, casual collectors, anyone without HobbyDB |
| **Import existing collection** | Requires HobbyDB Premium ($30/yr) for the CSV export | Established collectors with HobbyDB history |

"Start fresh" is presented first. The import path is *not* the primary CTA. The free path should not be hidden behind a paywall the user doesn't own.

### CSV import mechanics

User exports CSV from HobbyDB's web UI (Premium feature). Arcana picks it up via Android's Storage Access Framework (`ACTION_OPEN_DOCUMENT`). Arcana also registers a share-intent filter for `text/csv`, `application/csv`, and `text/comma-separated-values` so users can share email attachments directly into Arcana.

First-time users see a brief in-app walkthrough: "Sign into hobbydb.com → My Collection → Export → Save the CSV → Come back here and tap Import." Three screens, dismissible.

### Import is one-shot

After initial import, the user is the canonical source of truth for their collection. Re-import logic, user-edit protection, and HobbyDB reconciliation are not v1 features. See [CollectionImporter](#collectionimporter-and-the-importeditem-boundary) for details.

### "Add to HobbyDB" convenience action

For captured items, the detail screen offers an "Add to HobbyDB" deep-link that opens HobbyDB's add-item form with the item's metadata pre-filled. The user completes the form on HobbyDB. Arcana does not track whether they completed it. Arcana does not later try to reconcile the new HobbyDB row with the captured Arcana row.

This is a convenience for users who like HobbyDB as a hub; it imposes no logic on Arcana.

---

## Value tracking

Two paths, same persistence layer:

### Weekly background sync

`PeriodicWorkRequest` runs `WeeklyPriceSyncWorker` every 7 days, constrained to require network and unmetered connection. Each item is fetched via the `PriceProvider` chain; each result writes one `ValueSnapshotEntity` with `trigger = WeeklySync`. Per-item try/catch — partial failures don't abort the worker. User can disable the schedule in Settings.

For 283 items at 1 req/sec, a full run is ~5 minutes. Trivial within all relevant API rate limits.

### On-demand manual snapshot

Detail screen has a "Snapshot today's price" button. Tap → fetch fresh data via the `PriceProvider` chain → write a `ValueSnapshotEntity` with `trigger = UserRefresh`, plus update `lastKnownValueCents` on the collectible row.

Debounced: if the last snapshot is from the same day and within 5% of the current value, the button surfaces "Already up to date — last snapshot today at $X" instead of creating a duplicate row.

Naming is deliberate: this is *adding to history*, not *refreshing display*. The market section on every screen is already live-on-render; users don't need a "refresh price" button. They need a "commit this moment to my history" button.

### Value chart

`SELECT * FROM value_snapshots WHERE collectibleLocalId = ? ORDER BY snapshotAt` produces the per-item time-series. Aggregate portfolio chart sums across items per snapshot date.

Until at least 2 snapshots exist for an item, the chart shows "Tracking started — first sync this Sunday" rather than an empty axis.

For Week 2, the only `ValueSource` actually populated is `HobbyDbImport` (one snapshot per item at initial import time). `EbayBrowse` data starts populating when Week 3-4 integrates `EbayBrowsePriceProvider`.

---

## Package structure

```
app/src/main/kotlin/com/aashishgodambe/arcana/
├── ArcanaApplication.kt
├── MainActivity.kt
├── ui/
│   ├── theme/
│   ├── component/
│   │   ├── StreamingText.kt
│   │   ├── InferenceBadge.kt
│   │   ├── MarketSection.kt           # Shared between capture review and detail
│   │   └── ErrorState.kt
│   └── navigation/
├── core/
│   ├── ai/
│   │   ├── GeminiService.kt
│   │   ├── HybridGeminiService.kt
│   │   ├── FakeGeminiService.kt
│   │   ├── cascade/
│   │   │   ├── ImageCascade.kt
│   │   │   └── stage/
│   │   │       ├── SegmentationStage.kt
│   │   │       ├── ClassificationStage.kt
│   │   │       ├── OcrStage.kt
│   │   │       └── CatalogLookupStage.kt
│   │   ├── catalog/
│   │   │   ├── CatalogProvider.kt
│   │   │   ├── CatalogProviderChain.kt
│   │   │   ├── LocalCollectionCatalogProvider.kt
│   │   │   ├── UpcItemDbCatalogProvider.kt
│   │   │   ├── EbayBrowseCatalogProvider.kt
│   │   │   └── CloudVisionCatalogProvider.kt
│   │   ├── pricing/
│   │   │   ├── PriceProvider.kt
│   │   │   ├── PriceProviderChain.kt
│   │   │   └── EbayBrowsePriceProvider.kt
│   │   ├── rag/
│   │   │   ├── CollectionVectorStore.kt
│   │   │   └── EmbeddingEncoder.kt
│   │   ├── capability/DeviceCapabilityChecker.kt
│   │   ├── instrumentation/InferenceTelemetry.kt
│   │   ├── model/
│   │   │   ├── InferenceResult.kt
│   │   │   ├── InferenceMetadata.kt
│   │   │   ├── RoutingHint.kt
│   │   │   ├── InferenceLocation.kt
│   │   │   ├── CatalogEntry.kt
│   │   │   ├── PriceResult.kt
│   │   │   └── MarketContext.kt
│   │   └── di/AiModule.kt
│   ├── data/
│   │   ├── repository/
│   │   │   ├── CollectibleRepository.kt
│   │   │   └── CollectibleRepositoryImpl.kt
│   │   ├── database/
│   │   │   ├── ArcanaDatabase.kt
│   │   │   ├── dao/
│   │   │   │   ├── CollectibleDao.kt
│   │   │   │   ├── FunkoMetadataDao.kt
│   │   │   │   ├── SeriesDao.kt
│   │   │   │   ├── ProductionTagDao.kt
│   │   │   │   └── ValueSnapshotDao.kt
│   │   │   └── entity/
│   │   ├── importer/
│   │   │   ├── CollectionImporter.kt
│   │   │   ├── HobbyDbCsvImporter.kt
│   │   │   └── model/
│   │   │       ├── ImportedItem.kt
│   │   │       └── ImportResult.kt
│   │   ├── worker/
│   │   │   └── WeeklyPriceSyncWorker.kt
│   │   └── di/DataModule.kt
│   ├── domain/
│   │   ├── model/
│   │   │   ├── Collectible.kt
│   │   │   ├── FunkoPop.kt
│   │   │   └── ValueSnapshot.kt
│   │   └── usecase/
│   │       ├── ImportFromCsv.kt
│   │       ├── ChatWithCollection.kt
│   │       ├── SnapshotItemPrice.kt
│   │       └── SyncAllPrices.kt
│   └── common/
│       ├── Result.kt
│       └── DispatcherProvider.kt
└── feature/
    ├── onboarding/                # Two-path onboarding
    ├── collection/                # Grid of items
    ├── detail/                    # Single-item view
    │   ├── DetailScreen.kt        # Routes by sealed Collectible type
    │   ├── EditDetailsScreen.kt   # Gap-fill editor
    │   └── funko/FunkoDetailScreen.kt
    ├── chat/                      # "Ask Arcana"
    ├── capture/                   # Camera → cascade (Week 7+)
    │   ├── CameraScreen.kt
    │   ├── CaptureReviewScreen.kt
    │   └── CaptureViewModel.kt
    └── value/                     # Portfolio value chart
```

**Single-module in Weeks 1–4.** Promote to multi-module at Week 4 when boundaries are proven stable. Eventual split: `:core:ai`, `:core:data`, `:core:domain`, `:feature:*`. `:app` depends on features; features depend on core; core layers don't cross.

---

## Error handling and known gotchas

**ErrorCode 606 FEATURE_NOT_FOUND.** Gemini Nano unavailable. `HybridGeminiService` catches and routes to cloud. `DeviceCapabilityChecker` exposes a `modelReadinessFlow` for UI to surface cloud-only mode.

**BUSY errors.** AICore runs one inference at a time. `HybridGeminiService` uses a `Mutex`. Trigger UI is disabled while inference is in progress via an `isInferenceRunning: Boolean` ViewModel state.

**Model not yet downloaded.** First launch downloads the model in background. `modelReadinessFlow` exposes the state; chat shows "Setting up on-device AI…" rather than an error.

**Cold-start warm-up.** First inference on Nano is 2-5 seconds. `InferenceMetadata.firstTokenLatencyMs` is captured separately from `totalLatencyMs`. Benchmark charts distinguish first-call vs steady-state explicitly.

**Catalog provider rate limits.** Each provider tracks its rate-limit state. When a provider returns `RateLimited`, the chain skips to the next provider.

**Re-import behavior.** Not supported in v1. Settings may expose a "Import another CSV" action that appends rows without deduplication; duplicates are the user's problem.

---

## Testing strategy

**Unit tests (JVM).** Every ViewModel, every use case, every importer parsing edge case. `FakeGeminiService` and fake `CatalogProvider`/`PriceProvider` impls make AI-dependent code testable without a device. MockK for repositories. Turbine for Flow assertions.

**Integration tests (JVM).** `HobbyDbCsvImporter` against the real 283-item CSV. Assert row count (283), series junction count, NFT-redeemable count (142), `=HYPERLINK()` stripping.

**Instrumented tests (device).** Compose UI tests for the collection grid, detail screen, chat input/output, capture review screen rendering with mock cascade results.

**Benchmark tests (Pixel 9 Pro).** `androidx.benchmark` for inference latency: on-device vs cloud, cold vs warm. Runs manually, generates data for blog posts and resume.

---

## Open questions

Resolved in this design pass:

| Question | Resolution |
|---|---|
| HobbyDB API? | Not viable ($1,200 setup fee). CSV import only. |
| Value data source v1? | `EbayBrowsePriceProvider` via Browse API. PriceCharting documented as upgrade. |
| Sold listings data? | Not in v1 (eBay Marketplace Insights API is gated). Active listings only via Browse API. PriceCharting in Week 4 if subscribing. |
| Multi-module timing? | Week 4, after boundaries proven stable. |
| Non-Funko categories? | Schema supports them via per-category metadata tables. v1 ships Funko only; FigPin and Pokemon are documented holes. |
| NFT items in collection? | All physical Pops acquired via NFT redemption. `isNftRedeemable` is a catalog flag for display, not a tracked state. No `nft_redemption_state` table. |
| Bundled Funko catalog? | No. `kennymkchan/funko-pop-data` is deprecated, 5 years stale, missing all NFT items. Catalog is satisfied by the `CatalogProvider` chain. |
| Cascade design? | Single image-based cascade (segmentation → classification → OCR → catalog → cloud). Barcode scanner is a UI fallback button, not a separate cascade. |
| Captured items in HobbyDB? | Optional. "Add to HobbyDB" deep-link in detail screen; no reconciliation. |
| Re-import on Day 2? | Not v1. "Import another CSV" in Settings is append-only with manual duplicate handling. |
| Capture gesture? | Standard tap-to-capture shutter. |
| Gap-fill on capture review? | No. Pushed to "Edit details" affordance on detail screen post-save. |
| Market section for owned items? | Yes — show full market context regardless of ownership. The "you already own X" callout is additive. |
| "Refresh price" button? | Renamed "Snapshot today's price." Distinct action: commits a `ValueSnapshotEntity` row rather than refreshing display. Market section is already live-on-render. |
| Wishlist? | Post-MVP. `CollectibleOrigin.Wishlist` reserved in schema. |

Deferred to future conversations or implementation:

| Question | Defer to |
|---|---|
| Embedding model for RAG vector store? | Week 9 |
| Exact prompt template for "Ask Arcana"? | Week 7 |
| Capture flow UX details (animation timing, label positioning, etc.) | Conversation 3 (SCREENS.md) |
| Multi-quantity items in capture flow? | Week 7 |
| Listing description writer (ML Kit Rewriting)? | Post-MVP |
| Play Store decision? | Post-MVP |
