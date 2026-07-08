package com.aashishgodambe.arcana.core.ai.pricing

import com.aashishgodambe.arcana.core.ai.model.PriceResult
import com.aashishgodambe.arcana.core.data.database.entity.CollectibleCategory
import com.aashishgodambe.arcana.core.domain.model.Collectible
import com.aashishgodambe.arcana.core.domain.model.FunkoPop

/**
 * Category-routed ordered chain of [PriceProvider]s. For a given collectible it consults, in order,
 * only providers whose [PriceProvider.supportedCategories] include the item's category, short-circuits
 * on the first [PriceResult.Success], and skips a [PriceResult.RateLimited] provider to try the next.
 *
 * Mirrors the `CatalogProviderChain` shape in DESIGN.md: order is a Hilt config decision, not hardcoded —
 * swappable for benchmarks or a premium tier that reshuffles by subscription state.
 */
class PriceProviderChain(
    private val providers: List<PriceProvider>,
) {
    suspend fun fetchPrice(collectible: Collectible): PriceResult {
        val category = collectible.category
        var lastMiss: PriceResult = PriceResult.Unavailable("No price provider for $category")
        for (provider in providers) {
            if (category !in provider.supportedCategories) continue
            when (val result = provider.fetchPrice(collectible)) {
                is PriceResult.Success -> return result
                is PriceResult.RateLimited -> continue          // skip; try the next provider
                is PriceResult.Unavailable -> lastMiss = result  // remember, keep looking
            }
        }
        return lastMiss
    }
}

/**
 * The routing key. Derived from the sealed domain type rather than a stored field, so adding
 * FigPin/PokemonCard breaks this `when` at compile time and forces a routing decision.
 */
private val Collectible.category: CollectibleCategory
    get() = when (this) {
        is FunkoPop -> CollectibleCategory.Funko
    }
