package com.aashishgodambe.arcana.core.ai.pricing.di

import com.aashishgodambe.arcana.core.ai.pricing.MockPriceProvider
import com.aashishgodambe.arcana.core.ai.pricing.PriceProvider
import com.aashishgodambe.arcana.core.ai.pricing.PriceProviderChain
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent
import javax.inject.Singleton

/**
 * Wires the price seam. The provider *order* is configured here, not hardcoded in the chain — the real
 * `EbayBrowsePriceProvider` (Week 4) slots into this list ahead of / behind the mock without touching
 * callers. v1 binds the mock only.
 */
@Module
@InstallIn(SingletonComponent::class)
object PricingModule {

    @Provides
    @Singleton
    fun providePriceProviders(mock: MockPriceProvider): List<PriceProvider> = listOf(mock)

    @Provides
    @Singleton
    fun providePriceProviderChain(providers: List<PriceProvider>): PriceProviderChain =
        PriceProviderChain(providers)
}
