package com.aashishgodambe.arcana.core.domain.model

/**
 * The week's own-collection value movement, by HobbyDB list — the input the on-device summary narrates.
 * Built purely from the user's tracked `value_snapshots` (never from a live eBay response), so the
 * summary describes *their* portfolio, honoring the clean-hands guardrail.
 */
data class WeeklyDeltas(
    val totalDeltaCents: Int,
    val lists: List<ListDelta>,   // ordered by magnitude of movement, biggest mover first
)

data class ListDelta(
    val listName: String,
    val previousCents: Int,
    val currentCents: Int,
) {
    val deltaCents: Int get() = currentCents - previousCents
}
