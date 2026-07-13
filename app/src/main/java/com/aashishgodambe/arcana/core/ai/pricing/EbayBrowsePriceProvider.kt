package com.aashishgodambe.arcana.core.ai.pricing

import android.util.Log
import com.aashishgodambe.arcana.core.ai.model.ActiveListing
import com.aashishgodambe.arcana.core.ai.model.MarketContext
import com.aashishgodambe.arcana.core.ai.model.PriceConfidence
import com.aashishgodambe.arcana.core.ai.model.PriceResult
import com.aashishgodambe.arcana.core.data.database.entity.CollectibleCategory
import com.aashishgodambe.arcana.core.data.database.entity.ValueSource
import com.aashishgodambe.arcana.core.domain.model.Collectible
import com.aashishgodambe.arcana.core.domain.model.FunkoPop
import java.time.Instant
import javax.inject.Inject
import kotlin.time.Duration.Companion.seconds

/**
 * The real [PriceProvider] backing value tracking: median **active** listing price from eBay Browse. It's
 * the concrete impl the [MockPriceProvider] has stood in for — same seam, same [ValueSource.EbayBrowse]
 * label, so swapping it ahead of the mock needs no data migration.
 *
 * It persists nothing itself; it returns a value + a [MarketContext] of transient listings for the live
 * `MarketSection`. Only the caller's snapshot writes the aggregate price — never eBay user/seller data
 * (the basis for the Marketplace-Account-Deletion exemption). Browse can't see sold prices, so
 * `medianSold`/`recentSales` stay null/empty and the UI won't fake a "median sold" line.
 *
 * Degrades cleanly: unconfigured (fresh clone), no listings, or an HTTP error all return non-[Success],
 * and [PriceProviderChain] falls through to the mock.
 */
class EbayBrowsePriceProvider @Inject constructor(
    private val client: EbayBrowseClient,
) : PriceProvider {

    override val sourceName: String = "eBay Browse"

    override val supportedCategories: Set<CollectibleCategory> = setOf(CollectibleCategory.Funko)

    override suspend fun fetchPrice(collectible: Collectible): PriceResult {
        if (!client.isConfigured) return PriceResult.Unavailable("eBay not configured")
        val pop = collectible as? FunkoPop ?: return PriceResult.Unavailable("unsupported category")

        val query = EbayPriceMath.funkoQuery(pop.name, pop.popNumber, pop.series)
        val listings = try {
            client.searchActiveListings(query)
        } catch (e: EbayRateLimitException) {
            return PriceResult.RateLimited(60.seconds)
        } catch (e: Exception) {
            Log.w(TAG, "eBay price fetch failed for '${pop.name}'", e)
            return PriceResult.Unavailable(e.message ?: "eBay error")
        }

        val median = EbayPriceMath.medianCents(listings)
            ?: return PriceResult.Unavailable("no active listings for '$query'")

        Log.i(TAG, "priced '${pop.name}' #${pop.popNumber} → median ${median}c from ${listings.size} listings")
        return PriceResult.Success(
            valueCents = median,
            source = ValueSource.EbayBrowse,
            // A thin page is a weaker signal; a full one is a solid median.
            confidence = if (listings.size >= 5) PriceConfidence.High else PriceConfidence.Medium,
            marketContext = MarketContext(
                medianSoldPriceCents = null,               // Browse can't see sold
                medianActivePriceCents = median,
                recentSales = emptyList(),
                activeListings = listings
                    .map { it.toActiveListing(collectible) }
                    .sortedByDescending { it.priceCents },
                sampleSize = listings.size,
            ),
            fetchedAt = Instant.now(),
        )
    }

    private fun EbayListing.toActiveListing(collectible: Collectible) = ActiveListing(
        title = title,
        priceCents = priceCents,
        sellerRating = sellerFeedbackPct,
        ebayUrl = itemWebUrl ?: EbaySearch.url(collectible),   // fall back to the search page
    )

    private companion object {
        const val TAG = "EbayBrowse"
    }
}
