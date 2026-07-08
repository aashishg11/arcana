package com.aashishgodambe.arcana.core.data.database.entity

import java.time.Instant

/**
 * Projection row for the aggregate portfolio value series: total value across all items at one snapshot
 * instant, counting duplicate copies (Σ valueCents × quantity). Assembled by a GROUP BY over
 * `value_snapshots` joined to `collectibles` for quantity.
 */
data class PortfolioPointRow(
    val at: Instant,
    val totalValueCents: Int,
)
