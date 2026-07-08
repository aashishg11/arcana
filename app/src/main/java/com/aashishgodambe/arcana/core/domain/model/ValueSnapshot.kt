package com.aashishgodambe.arcana.core.domain.model

import com.aashishgodambe.arcana.core.data.database.entity.SnapshotTrigger
import com.aashishgodambe.arcana.core.data.database.entity.ValueSource
import java.time.Instant

/** One point in a collectible's tracked value history. Domain view of a `value_snapshots` row. */
data class ValueSnapshot(
    val valueCents: Int,
    val at: Instant,
    val source: ValueSource,
    val trigger: SnapshotTrigger,
)

/**
 * One point in the aggregate portfolio value series — the total across the whole collection at a given
 * snapshot instant, counting duplicate copies (Σ valueCents × quantity), consistent with the shipped
 * duplicate-aware valuation. Backs the Portfolio sparkline.
 */
data class PortfolioPoint(
    val at: Instant,
    val totalValueCents: Int,
)
