package com.aashishgodambe.arcana.core.ai.pricing

import com.aashishgodambe.arcana.core.ai.model.PriceConfidence
import com.aashishgodambe.arcana.core.ai.model.PriceResult
import com.aashishgodambe.arcana.core.data.database.entity.CollectibleCategory
import com.aashishgodambe.arcana.core.data.database.entity.ValueSource
import com.aashishgodambe.arcana.testFunko
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.Instant
import kotlin.time.Duration.Companion.minutes

class PriceProviderChainTest {

    private fun success(cents: Int) = PriceResult.Success(
        valueCents = cents,
        source = ValueSource.EbayBrowse,
        confidence = PriceConfidence.High,
        marketContext = null,
        fetchedAt = Instant.EPOCH,
    )

    @Test
    fun `routes to the first supporting provider and short-circuits`() = runTest {
        val first = FakePriceProvider(success(1000))
        val second = FakePriceProvider(success(2000))
        val chain = PriceProviderChain(listOf(first, second))

        val result = chain.fetchPrice(testFunko(1))

        assertEquals(1000, (result as PriceResult.Success).valueCents)
        assertEquals("second provider must not be consulted after a hit", 0, second.callCount)
    }

    @Test
    fun `skips a provider that does not support the category`() = runTest {
        val cardsOnly = FakePriceProvider(success(999), supportedCategories = setOf(CollectibleCategory.Pokemon))
        val funko = FakePriceProvider(success(1500))
        val chain = PriceProviderChain(listOf(cardsOnly, funko))

        val result = chain.fetchPrice(testFunko(1))

        assertEquals(0, cardsOnly.callCount)
        assertEquals(1500, (result as PriceResult.Success).valueCents)
    }

    @Test
    fun `skips a rate-limited provider and tries the next`() = runTest {
        val limited = FakePriceProvider(PriceResult.RateLimited(5.minutes))
        val ok = FakePriceProvider(success(1200))
        val chain = PriceProviderChain(listOf(limited, ok))

        val result = chain.fetchPrice(testFunko(1))

        assertEquals(1, limited.callCount)
        assertEquals(1200, (result as PriceResult.Success).valueCents)
    }

    @Test
    fun `reports unavailable when no provider supports the category`() = runTest {
        val cardsOnly = FakePriceProvider(success(999), supportedCategories = setOf(CollectibleCategory.Pokemon))
        val chain = PriceProviderChain(listOf(cardsOnly))

        assertTrue(chain.fetchPrice(testFunko(1)) is PriceResult.Unavailable)
    }
}
