package com.aashishgodambe.arcana.core.ai.pricing.di

import com.aashishgodambe.arcana.core.ai.pricing.EbayBrowsePriceProvider
import com.aashishgodambe.arcana.core.ai.pricing.MockPriceProvider
import com.aashishgodambe.arcana.core.ai.pricing.PriceProviderChain
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent
import javax.inject.Singleton

/**
 * Wires the price seam. The provider *order* is configured here, not hardcoded in the chain: real
 * eBay Browse first, the mock behind it as an offline/unconfigured fallback. eBay returning
 * Unavailable/RateLimited falls through to the mock, so value tracking never goes dark. (The list is built
 * inline rather than injected to avoid Kotlin's `List<out PriceProvider>` wildcard mismatch with Dagger.)
 */
@Module
@InstallIn(SingletonComponent::class)
object PricingModule {

    @Provides
    @Singleton
    fun providePriceProviderChain(
        ebay: EbayBrowsePriceProvider,
        mock: MockPriceProvider,
    ): PriceProviderChain =
        PriceProviderChain(listOf(ebay, mock))
}
