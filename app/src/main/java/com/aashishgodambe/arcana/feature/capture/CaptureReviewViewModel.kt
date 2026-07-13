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
import com.aashishgodambe.arcana.core.data.repository.CollectibleRepository
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
) : ViewModel() {

    private val _state = MutableStateFlow(CaptureReviewUiState())
    val state: StateFlow<CaptureReviewUiState> = _state.asStateFlow()

    // The engine emits Segmenting() once as kickoff (before OCR) and again after segment (after Read);
    // this disambiguates the two so the outline beat only lights once segmentation has actually returned.
    private var readSeen = false

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
            flow.collect { reduce(it) }
        }
    }

    /** Fold one cascade emission into the UI state — the screen renders the beats from these fields. */
    private fun reduce(cascadeState: CascadeState) {
        when (cascadeState) {
            is CascadeState.Segmenting ->
                _state.update {
                    // Only the post-OCR emission means segmentation actually ran.
                    if (readSeen) it.copy(subject = cascadeState.subject ?: it.subject, subjectReady = true) else it
                }

            is CascadeState.Read -> {
                readSeen = true
                _state.update { it.copy(popNumber = cascadeState.layout.popNumber) }
            }

            is CascadeState.Describing ->
                _state.update { it.copy(description = cascadeState.text) }

            CascadeState.Matching ->
                _state.update { it.copy(matching = true) }

            is CascadeState.Settled -> {
                _state.update { it.copy(settled = cascadeState.result) }
                cascadeState.result.entry?.let { fetchMarket(it) }
            }

            is CascadeState.Failed ->
                _state.update { it.copy(failure = "Couldn't identify (${cascadeState.stage})") }
        }
    }

    /**
     * Once identified, load the live market (same real-eBay chain Detail uses) and the owned quantity for
     * the "×N" callout. For an owned pop we load the real collectible (real quantity + a good price
     * fallback); otherwise a transient one carries just enough identity to price and link out.
     */
    private fun fetchMarket(entry: CatalogEntry) {
        viewModelScope.launch {
            val owned = entry.matchedLocalId?.let { repository.getById(it) as? FunkoPop }
            val collectible = owned ?: entry.toPriceable()
            _state.update { it.copy(ownedQuantity = owned?.quantity, buyUrl = EbaySearch.url(collectible)) }
            val market = (priceChain.fetchPrice(collectible) as? PriceResult.Success)?.marketContext
            _state.update { it.copy(market = market) }
        }
    }

    /** A transient [FunkoPop] from a catalog identity — only name/number/series are used, to price + link. */
    private fun CatalogEntry.toPriceable(): FunkoPop = FunkoPop(
        localId = matchedLocalId ?: 0L, name = name, brand = "Funko", imageUrl = imageUrl,
        estimatedValueCents = 0, lastKnownValueCents = null, quantity = 1, itemCondition = "",
        packagingCondition = "", series = series, productionTags = emptyList(), dateAdded = LocalDate.now(),
        pricePaidCents = null, storageLocation = null, upc = "", popNumber = number,
        exclusiveTo = exclusiveTo, isNftRedeemable = false,
    )
}

/**
 * The capture Review UI state — a flat projection of the cascade stream that the animated hero renders as
 * beats. [subjectReady]/[popNumber]/[matching] drive the running animation (outline, #NNN callout, chain
 * lines); [settled] drives the settled card. [description] is the late/optional corroboration line.
 */
data class CaptureReviewUiState(
    val frame: Bitmap? = null,
    val subject: Bitmap? = null,
    val subjectReady: Boolean = false,   // segmentation returned (subject may still be null)
    val popNumber: String? = null,       // OCR read — the #NNN callout
    val matching: Boolean = false,       // catalog walk started
    val description: String? = null,     // trailing on-device description
    val settled: CascadeResult? = null,
    val failure: String? = null,
    val barcodePath: Boolean = false,    // barcode fallback skips segmentation/OCR beats
    val market: MarketContext? = null,   // live eBay market, fetched after settle
    val buyUrl: String? = null,          // eBay search link for the settled identity
    val ownedQuantity: Int? = null,      // copies owned, for the "×N" callout
) {
    val running: Boolean get() = settled == null && failure == null
}
