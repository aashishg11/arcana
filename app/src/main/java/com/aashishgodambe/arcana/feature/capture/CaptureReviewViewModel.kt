package com.aashishgodambe.arcana.feature.capture

import android.graphics.Bitmap
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.aashishgodambe.arcana.core.ai.cascade.CaptureCascade
import com.aashishgodambe.arcana.core.ai.cascade.CascadeResult
import com.aashishgodambe.arcana.core.ai.cascade.CascadeState
import com.aashishgodambe.arcana.core.ai.catalog.CatalogEntry
import com.aashishgodambe.arcana.core.ai.model.MarketContext
import com.aashishgodambe.arcana.core.ai.model.PriceResult
import com.aashishgodambe.arcana.core.ai.pricing.EbaySearch
import com.aashishgodambe.arcana.core.ai.pricing.PriceProviderChain
import com.aashishgodambe.arcana.core.data.database.entity.CollectibleCategory
import com.aashishgodambe.arcana.core.data.importer.model.FunkoImportMetadata
import com.aashishgodambe.arcana.core.data.importer.model.ImportedItem
import com.aashishgodambe.arcana.core.data.repository.CollectibleRepository
import com.aashishgodambe.arcana.core.data.worker.EmbeddingIndexScheduler
import com.aashishgodambe.arcana.core.domain.model.FunkoPop
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import java.time.LocalDate
import javax.inject.Inject

/**
 * Drives the capture Review screen by subscribing to the Week-8 [CaptureCascade] flow for the frame the
 * camera just stashed. This is a *rendering* of `Flow<CascadeState>` — no cascade logic lives here; each
 * emission maps to a field of [CaptureReviewUiState].
 *
 * Day 1 is deliberately a minimal, honest render (frozen frame + status lines + settled identity) that
 * proves the camera→cascade pipeline. Day 2 replaces the surface with the animated hero beats; Day 4 adds
 * save-to-collection. The state shape already carries what those need (subject bitmap, resolved location,
 * ownership).
 */
@HiltViewModel
class CaptureReviewViewModel @Inject constructor(
    private val store: CaptureSessionStore,
    private val cascade: CaptureCascade,
    private val priceChain: PriceProviderChain,
    private val repository: CollectibleRepository,
    private val indexScheduler: EmbeddingIndexScheduler,
) : ViewModel() {

    private val _state = MutableStateFlow(CaptureReviewUiState())
    val state: StateFlow<CaptureReviewUiState> = _state.asStateFlow()

    init {
        start()
    }

    private fun start() {
        val payload = store.take()
        if (payload == null) {
            // No frame to identify (e.g. a stale re-entry after process return) — nothing to render.
            _state.value = CaptureReviewUiState(failure = "Nothing to identify")
            return
        }

        val frame = when (payload) {
            is CapturePayload.Image -> payload.frames.first()
            is CapturePayload.Barcode -> payload.frame
        }
        _state.value = CaptureReviewUiState(frame = frame, barcodePath = payload is CapturePayload.Barcode)

        viewModelScope.launch {
            val flow = when (payload) {
                is CapturePayload.Image -> cascade.identify(payload.frames)
                is CapturePayload.Barcode -> cascade.identifyFromBarcode(payload.frame)
            }
            flow.collect { cascadeState ->
                _state.update { reduceCapture(it, cascadeState) }
                // The one side-effect: kick the market fetch once identified (kept out of the pure reducer).
                if (cascadeState is CascadeState.Settled) cascadeState.result.entry?.let { fetchMarket(it) }
            }
        }
    }

    /**
     * Once identified, load the live market (same real-eBay chain Detail uses) and the owned quantity for
     * the "×N" callout. For an owned pop we load the real collectible (real quantity + a good price
     * fallback); otherwise a transient one carries just enough identity to price and link out.
     */
    private fun fetchMarket(entry: CatalogEntry) {
        viewModelScope.launch {
            // Price via the entry (which now carries the box's franchise) so a generic name is
            // disambiguated; load the owned pop only for the ×N count.
            val ownedQty = entry.matchedLocalId?.let { (repository.getById(it) as? FunkoPop)?.quantity }
            val priceable = entry.toPriceable()
            _state.update {
                it.copy(ownedQuantity = ownedQty, buyUrl = EbaySearch.url(priceable), availableLists = repository.listNames())
            }
            val market = (priceChain.fetchPrice(priceable) as? PriceResult.Success)?.marketContext
            _state.update { it.copy(market = market) }
        }
    }

    /** Save an unowned captured pop into [listName] (creating the list if new); lands on Detail via [savedId]. */
    fun save(listName: String) {
        val entry = _state.value.settled?.entry ?: return
        if (_state.value.saving) return
        _state.update { it.copy(saving = true) }
        viewModelScope.launch {
            val item = capturedItem(entry, listName, _state.value.market?.medianActivePriceCents ?: 0)
            _state.update { it.copy(saving = false, savedId = repository.saveCaptured(item)) }
            // Fold the just-saved pop into the RAG index (incremental → embeds only the new item).
            indexScheduler.enqueue()
        }
    }

    /** "Add another" for an already-owned pop — bump quantity, then land on its Detail. */
    fun addAnother() {
        val id = _state.value.settled?.entry?.matchedLocalId ?: return
        if (_state.value.saving) return
        _state.update { it.copy(saving = true) }
        viewModelScope.launch {
            repository.incrementQuantity(id)
            _state.update { it.copy(saving = false, savedId = id) }
        }
    }

    /**
     * A transient [FunkoPop] from a catalog identity, used only to price + link (name/franchise/number/
     * series). The franchise is folded into the name so the eBay query disambiguates a generic name —
     * "Freddy Funko" → "Freddy Funko Popeye" — since the collection stores no franchise for these.
     */
    private fun CatalogEntry.toPriceable(): FunkoPop = FunkoPop(
        localId = matchedLocalId ?: 0L,
        name = listOfNotNull(name, franchise?.takeIf { !name.contains(it, ignoreCase = true) }).joinToString(" "),
        brand = "Funko", imageUrl = imageUrl,
        estimatedValueCents = 0, lastKnownValueCents = null, quantity = 1, itemCondition = "",
        packagingCondition = "", series = series, productionTags = emptyList(), dateAdded = LocalDate.now(),
        pricePaidCents = null, storageLocation = null, upc = "", popNumber = number,
        exclusiveTo = exclusiveTo, isNftRedeemable = false,
    )
}

/**
 * Pure reduction of one [CascadeState] into the Review UI state — the beat mapping the screen renders.
 * Side-effect-free (the VM triggers the market fetch on Settled), so it's unit-tested device-free over
 * fake cascade flows. The engine emits Segmenting twice (kickoff, then after OCR); [CaptureReviewUiState.readSeen]
 * disambiguates so the outline beat only lights once segmentation has actually run.
 */
internal fun reduceCapture(state: CaptureReviewUiState, cascadeState: CascadeState): CaptureReviewUiState =
    when (cascadeState) {
        is CascadeState.Segmenting ->
            if (state.readSeen) state.copy(subject = cascadeState.subject ?: state.subject, subjectReady = true) else state
        is CascadeState.Read -> state.copy(popNumber = cascadeState.layout.popNumber, readSeen = true)
        is CascadeState.Describing -> state.copy(description = cascadeState.text)
        CascadeState.Matching -> state.copy(matching = true)
        is CascadeState.Settled -> state.copy(settled = cascadeState.result)
        is CascadeState.Failed -> state.copy(failure = "Couldn't identify (${cascadeState.stage})")
    }

/**
 * The [ImportedItem] a captured pop is saved as: an ArcanaCapture-origin Funko into [listName], valued at
 * the eBay [valueCents]. NFT-redeemable is inferred from an "NFT" exclusivity label. Pure → unit-tested.
 */
internal fun capturedItem(entry: CatalogEntry, listName: String, valueCents: Int): ImportedItem =
    ImportedItem(
        sourceId = entry.externalId,
        sourceName = "Arcana Capture",
        listName = listName.trim().ifBlank { null },
        category = CollectibleCategory.Funko,
        name = entry.name,
        brand = "Funko",
        quantity = 1,
        estimatedValueCents = valueCents,
        itemCondition = null,
        packagingCondition = null,
        dateAdded = LocalDate.now(),
        imageUrl = entry.imageUrl,
        series = entry.series,
        productionTags = emptyList(),
        funkoMetadata = FunkoImportMetadata(
            upc = null,
            popNumber = entry.number,
            exclusiveTo = entry.exclusiveTo,
            isNftRedeemable = entry.exclusiveTo?.contains("nft", ignoreCase = true) == true,
            scale = null,
            releaseDate = null,
            hdbcNumber = null,
        ),
    )

/**
 * The capture Review UI state — a flat projection of the cascade stream that the animated hero renders as
 * beats. [subjectReady]/[popNumber]/[matching] drive the running animation (outline, #NNN callout, chain
 * lines); [settled] drives the settled card. [description] is the late/optional corroboration line.
 */
data class CaptureReviewUiState(
    val frame: Bitmap? = null,
    val subject: Bitmap? = null,
    val subjectReady: Boolean = false,   // segmentation returned (subject may still be null)
    val readSeen: Boolean = false,       // OCR Read emitted — disambiguates the two Segmenting emissions
    val popNumber: String? = null,       // OCR read — the #NNN callout
    val matching: Boolean = false,       // catalog walk started
    val description: String? = null,     // trailing on-device description
    val settled: CascadeResult? = null,
    val failure: String? = null,
    val barcodePath: Boolean = false,    // barcode fallback skips segmentation/OCR beats
    val market: MarketContext? = null,   // live eBay market, fetched after settle
    val buyUrl: String? = null,          // eBay search link for the settled identity
    val ownedQuantity: Int? = null,      // copies owned, for the "×N" callout
    val availableLists: List<String> = emptyList(),  // existing lists for the save picker
    val saving: Boolean = false,
    val savedId: Long? = null,           // set once saved — the screen navigates to this item's Detail
) {
    val running: Boolean get() = settled == null && failure == null
}
