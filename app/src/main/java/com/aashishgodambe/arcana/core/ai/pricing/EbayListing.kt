package com.aashishgodambe.arcana.core.ai.pricing

/**
 * One active eBay Browse listing, normalized to a pure JVM type (no android/HTTP/JSON types) so the
 * pricing math and query building stay unit-testable off-device.
 *
 * Deliberately **transient**: these back the live `MarketSection` on render and are never written to the
 * database. Only the derived aggregate ([EbayPriceMath.medianCents]) is persisted, in keeping with the
 * eBay Marketplace-Account-Deletion exemption — Arcana stores no eBay user/seller data, just a price.
 */
data class EbayListing(
    val title: String,
    val priceCents: Int,
    val currency: String,
    val condition: String?,
    val sellerFeedbackPct: Float?,
    val itemWebUrl: String?,
    /** Fixed shipping in cents (0 = free); null when eBay only calculates it from the buyer's location. */
    val shippingCents: Int?,
)
