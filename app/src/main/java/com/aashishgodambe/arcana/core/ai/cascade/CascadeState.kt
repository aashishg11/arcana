package com.aashishgodambe.arcana.core.ai.cascade

import android.graphics.Bitmap
import com.aashishgodambe.arcana.core.ai.catalog.CatalogEntry
import com.aashishgodambe.arcana.core.ai.model.InferenceLocation

/**
 * Per-stage progress of a [CaptureCascade] run, designed so the Week-9 capture Review screen is a pure
 * *rendering* of this stream, not a re-implementation: each state maps to a visible beat — the
 * segmentation outline, the streaming description, the floating Pop-number callout, the catalog-chain
 * feedback, and the settled identity with its on-device/cloud badge.
 */
sealed interface CascadeState {

    /** Isolating the subject. [subject] is null until segmentation returns, then the masked bitmap. */
    data class Segmenting(val subject: Bitmap? = null) : CascadeState

    /** On-device LLM streaming a description (best-effort; may be brief or never arrive if refused). */
    data class Describing(val text: String) : CascadeState

    /** OCR + layout done: the structured box read, incl. the Pop number for the floating callout. */
    data class Read(val layout: BoxLayout) : CascadeState

    /** Walking the catalog chain — backs the "Checking your collection… eBay…" feedback. */
    data object Matching : CascadeState

    /** Terminal: the cascade settled (identified, best-effort, or unresolved), with telemetry. */
    data class Settled(val result: CascadeResult) : CascadeState

    /** Terminal: a required stage failed hard (e.g. OCR threw). */
    data class Failed(val stage: String, val cause: Throwable) : CascadeState
}

/**
 * The outcome of a settled cascade. [entry] is null when nothing identified it; [confident] is whether it
 * cleared the confidence gate (drives high- vs low-confidence UI); [owned] is whether it matched an item
 * the user already has (the "you already own this" callout + deep-link).
 */
data class CascadeResult(
    val entry: CatalogEntry?,
    val confident: Boolean,
    val owned: Boolean,
    val telemetry: CascadeTelemetry,
)

/** End-to-end + per-stage timing and where the identity resolved — feeds the badge and benchmarks. */
data class CascadeTelemetry(
    val totalMs: Long,
    val perStageMs: Map<String, Long>,
    val resolvedOn: InferenceLocation?,
)
