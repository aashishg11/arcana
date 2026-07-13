package com.aashishgodambe.arcana.core.ai.model

import java.time.Instant

/**
 * The market data a price value was derived from. Returned alongside the primary value so the UI can
 * present "what is this worth?" honestly — exposing the underlying listings rather than a bare number.
 *
 * eBay Browse (the v1 real source) can see *active* listings only; sold data is gated behind the paid
 * Marketplace Insights API. So [medianSoldPriceCents] / [recentSales] are null / empty for Browse-backed
 * results, and the UI must not pretend a "median sold" line exists when it doesn't.
 */
data class MarketContext(
    val medianSoldPriceCents: Int?,          // null when the source can't see sold prices (eBay Browse)
    val medianActivePriceCents: Int?,
    val recentSales: List<SoldListing>,      // empty when sold data is unavailable
    val activeListings: List<ActiveListing>,
    val sampleSize: Int,
)

/** A live listing offered for sale. The "Buy on eBay"/"View" affordances link out to [ebayUrl]. */
data class ActiveListing(
    val title: String,
    val priceCents: Int,
    val sellerRating: Float?,
    val ebayUrl: String,
    /** Fixed shipping in cents (0 = free); null when eBay only calculates it from the buyer's location. */
    val shippingCents: Int? = null,
) {
    /** Item price plus known shipping — the sort/display basis; unknown (calculated) shipping counts as 0. */
    val totalCents: Int get() = priceCents + (shippingCents ?: 0)
}

/** A completed sale. Populated only by sold-data-capable sources (PriceCharting); never by Browse. */
data class SoldListing(
    val priceCents: Int,
    val soldAt: Instant,
    val condition: String?,
)
