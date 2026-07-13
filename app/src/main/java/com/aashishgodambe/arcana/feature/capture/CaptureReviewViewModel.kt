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

    init {
        start()
    }

    private fun start() {
        val payload = store.take()
        if (payload == null) {
            // No frame to identify (e.g. a stale re-entry after process return) — nothing to render.
            _state.value = CaptureReviewUiState(running = false, failure = "Nothing to identify")
            return
        }

        val frame = when (payload) {
            is CapturePayload.Image -> payload.frames.first()
            is CapturePayload.Barcode -> payload.frame
        }
        _state.value = CaptureReviewUiState(frame = frame, running = true)

        viewModelScope.launch {
            val flow = when (payload) {
                is CapturePayload.Image -> cascade.identify(payload.frames)
                is CapturePayload.Barcode -> cascade.identifyFromBarcode(payload.frame)
            }
            flow.collect { reduce(it) }
        }
    }

    /** Fold one cascade emission into the UI state. */
    private fun reduce(cascadeState: CascadeState) {
        when (cascadeState) {
            is CascadeState.Segmenting ->
                _state.update { s ->
                    s.copy(
                        subject = cascadeState.subject ?: s.subject,
                        statusLines = s.withStatus("Segmenting subject…"),
                    )
                }

            is CascadeState.Read ->
                _state.update { s ->
                    s.copy(
                        popNumber = cascadeState.layout.popNumber,
                        statusLines = s.withStatus("Box number read · #${cascadeState.layout.popNumber ?: "?"}"),
                    )
                }

            is CascadeState.Describing ->
                _state.update { it.copy(description = cascadeState.text) }

            CascadeState.Matching ->
                _state.update { it.copy(statusLines = it.withStatus("Checking your collection…")) }

            is CascadeState.Settled ->
                _state.update { it.copy(running = false, settled = cascadeState.result) }

            is CascadeState.Failed ->
                _state.update {
                    it.copy(running = false, failure = "Couldn't identify (${cascadeState.stage})")
                }
        }
    }
}

/**
 * The capture Review UI state — a flat projection of the cascade stream. Carries more than Day 1 renders
 * ([subject], resolved location via [settled]) so Days 2–4 extend this rather than reshape it.
 */
data class CaptureReviewUiState(
    val frame: Bitmap? = null,
    val subject: Bitmap? = null,
    val statusLines: List<String> = emptyList(),
    val popNumber: String? = null,
    val description: String? = null,
    val settled: CascadeResult? = null,
    val failure: String? = null,
    val running: Boolean = true,
) {
    /** Append a status line, de-duping consecutive repeats (the cascade emits Segmenting twice). */
    fun withStatus(line: String): List<String> =
        if (statusLines.lastOrNull() == line) statusLines else statusLines + line
}
