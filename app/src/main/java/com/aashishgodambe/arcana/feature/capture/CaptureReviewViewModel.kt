package com.aashishgodambe.arcana.feature.capture

import android.graphics.Bitmap
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.aashishgodambe.arcana.core.ai.cascade.CaptureCascade
import com.aashishgodambe.arcana.core.ai.cascade.CascadeResult
import com.aashishgodambe.arcana.core.ai.cascade.CascadeState
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
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

            is CascadeState.Settled ->
                _state.update { it.copy(settled = cascadeState.result) }

            is CascadeState.Failed ->
                _state.update { it.copy(failure = "Couldn't identify (${cascadeState.stage})") }
        }
    }
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
) {
    val running: Boolean get() = settled == null && failure == null
}
