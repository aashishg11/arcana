package com.aashishgodambe.arcana.core.ai.pricing

import com.aashishgodambe.arcana.core.ai.model.PriceResult
import com.aashishgodambe.arcana.core.data.database.entity.CollectibleCategory
import com.aashishgodambe.arcana.core.domain.model.Collectible

/**
 * Pluggable source of a collectible's current value plus the market context it came from — one of the
 * five interface seams (see DESIGN.md). Features depend on this, never on eBay/HTTP/PriceCharting types;
 * swapping a backend is a Hilt binding change.
 *
 * Category-routed from day one: a provider declares which [supportedCategories] it can price, and
 * [PriceProviderChain] only consults a provider for collectibles it supports. v1 ships Funko only, but
 * the routing is honored so a card/sneaker provider slots in without touching the chain.
 */
interface PriceProvider {
    val sourceName: String
    val supportedCategories: Set<CollectibleCategory>
    suspend fun fetchPrice(collectible: Collectible): PriceResult
}
