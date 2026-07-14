package com.aashishgodambe.arcana.core.ai.writing

import com.aashishgodambe.arcana.core.domain.model.FunkoPop

/**
 * Composes the **raw** sale-listing text that ML Kit Rewriting then polishes. Pure (no android/ML Kit
 * types) → JVM-tested.
 *
 * **eBay-compliance boundary lives here:** the listing is built *only* from the collector's own item data
 * (name, Pop number, series/franchise, condition, exclusivity) — never from eBay-sourced market data. eBay's
 * 2025 API terms restrict feeding marketplace data to AI, so the eBay median stays on the display side and
 * never reaches the model. Kept under Rewriting's ~256-token input limit by being a terse descriptor.
 */
object ListingComposer {

    fun compose(item: FunkoPop): String = buildString {
        append("Funko Pop ").append(item.name)
        item.popNumber?.takeIf { it.isNotBlank() }?.let { append(" #").append(it) }
        append(".")

        val series = item.series.filter { it.isNotBlank() }.joinToString(", ")
        if (series.isNotBlank()) append(" From ").append(series).append(".")

        item.exclusiveTo?.takeIf { it.isNotBlank() }?.let { append(" ").append(it).append(" exclusive.") }
        if (item.isNftRedeemable) append(" NFT-redeemable release.")

        append(" Condition: figure ").append(item.itemCondition.ifBlank { "unknown" })
        append(", box ").append(item.packagingCondition.ifBlank { "unknown" }).append(".")
        append(" For sale by a collector.")
    }
}
