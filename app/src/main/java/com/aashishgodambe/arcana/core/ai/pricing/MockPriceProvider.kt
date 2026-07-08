package com.aashishgodambe.arcana.core.ai.pricing

import com.aashishgodambe.arcana.core.ai.model.ActiveListing
import com.aashishgodambe.arcana.core.ai.model.MarketContext
import com.aashishgodambe.arcana.core.ai.model.PriceConfidence
import com.aashishgodambe.arcana.core.ai.model.PriceResult
import com.aashishgodambe.arcana.core.data.database.entity.CollectibleCategory
import com.aashishgodambe.arcana.core.data.database.entity.ValueSource
import com.aashishgodambe.arcana.core.domain.model.Collectible
import java.time.Instant
import kotlin.random.Random
import javax.inject.Inject

/**
 * Stand-in [PriceProvider] for Week 3 — no network, no paid API. Returns a plausible **median active**
 * value ([MockPriceModel]) plus a handful of synthetic active listings, so the inert value UI lights up
 * and the weekly summary has real deltas to describe.
 *
 * It impersonates the eventual `EbayBrowsePriceProvider` behind the same seam, so snapshots it produces
 * are labeled [ValueSource.EbayBrowse]: swapping in the real Browse impl needs no data migration, and —
 * like Browse — it can see active listings only, never sold (median-sold / recentSales stay null/empty).
 */
class MockPriceProvider @Inject constructor() : PriceProvider {

    override val sourceName: String = "Mock (eBay Browse stand-in)"

    // v1 ships Funko only; the seam is category-routed so a card/sneaker provider slots in later.
    override val supportedCategories: Set<CollectibleCategory> = setOf(CollectibleCategory.Funko)

    override suspend fun fetchPrice(collectible: Collectible): PriceResult {
        val medianActive = MockPriceModel.currentValueCents(collectible)
        val listings = syntheticListings(collectible, medianActive)
        return PriceResult.Success(
            valueCents = medianActive,
            source = ValueSource.EbayBrowse,
            confidence = PriceConfidence.High,
            marketContext = MarketContext(
                medianSoldPriceCents = null,            // Browse (and this stand-in) can't see sold
                medianActivePriceCents = medianActive,
                recentSales = emptyList(),
                activeListings = listings,
                sampleSize = listings.size,
            ),
            fetchedAt = Instant.now(),
        )
    }

    /** A few deterministic listings scattered around the median, priciest first — mimics a Browse page. */
    private fun syntheticListings(collectible: Collectible, medianCents: Int): List<ActiveListing> {
        val rng = Random(collectible.localId * 31 + 7)
        val url = EbaySearch.url(collectible)
        return LISTING_TEMPLATES.map { template ->
            val price = (medianCents * rng.nextDouble(0.88, 1.18)).toInt().coerceAtLeast(1)
            ActiveListing(
                title = template.titleFor(collectible.name),
                priceCents = price,
                sellerRating = rng.nextDouble(97.0, 100.0).toFloat(),
                ebayUrl = url,
            )
        }.sortedByDescending { it.priceCents }
    }

    private data class ListingTemplate(val suffix: String) {
        fun titleFor(name: String) = "$name $suffix"
    }

    private companion object {
        val LISTING_TEMPLATES = listOf(
            ListingTemplate("— mint, w/ protector"),
            ListingTemplate("Pop Vinyl — near mint"),
            ListingTemplate("— loose, no box"),
        )
    }
}
