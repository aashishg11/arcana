package com.aashishgodambe.arcana.core.ai.pricing

import com.aashishgodambe.arcana.core.ai.model.PriceResult
import com.aashishgodambe.arcana.core.data.database.entity.CollectibleCategory
import com.aashishgodambe.arcana.core.domain.model.Collectible

/**
 * Configurable [PriceProvider] fake — returns a fixed [result] for supported categories and records how
 * many times it was consulted, so chain-routing and use-case tests run device- and network-free.
 */
class FakePriceProvider(
    private val result: PriceResult,
    override val supportedCategories: Set<CollectibleCategory> = setOf(CollectibleCategory.Funko),
    override val sourceName: String = "Fake",
) : PriceProvider {
    var callCount = 0
        private set

    override suspend fun fetchPrice(collectible: Collectible): PriceResult {
        callCount++
        return result
    }
}
