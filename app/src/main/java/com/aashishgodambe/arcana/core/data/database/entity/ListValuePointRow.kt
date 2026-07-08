package com.aashishgodambe.arcana.core.data.database.entity

import java.time.Instant

/**
 * Projection row for the per-list value series: total value of one HobbyDB list at one snapshot instant,
 * counting duplicate copies (Σ valueCents × quantity). Grouped by (listName, snapshotAt); feeds the
 * weekly "what moved" delta computation behind the on-device summary.
 */
data class ListValuePointRow(
    val name: String,
    val at: Instant,
    val totalValueCents: Int,
)
