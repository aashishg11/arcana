package com.aashishgodambe.arcana.core.ai.pricing

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/** JVM tests for the pure eBay pricing logic — query building and median reduction. */
class EbayPriceMathTest {

    private fun listing(priceCents: Int, currency: String = "USD") =
        EbayListing(
            title = "Funko Pop",
            priceCents = priceCents,
            currency = currency,
            condition = "New",
            sellerFeedbackPct = 99.0f,
            itemWebUrl = null,
        )

    @Test
    fun `query includes brand line name and number`() {
        assertEquals("Funko Pop Freddy Funko 32", EbayPriceMath.funkoQuery("Freddy Funko", "32"))
    }

    @Test
    fun `query omits a missing number and collapses whitespace`() {
        assertEquals("Funko Pop Aang", EbayPriceMath.funkoQuery("  Aang  ", null))
    }

    @Test
    fun `median of odd count is the middle`() {
        val m = EbayPriceMath.medianCents(listOf(listing(1000), listing(3000), listing(2000)))
        assertEquals(2000, m)
    }

    @Test
    fun `median of even count averages the two middles`() {
        val m = EbayPriceMath.medianCents(listOf(listing(1000), listing(2000), listing(3000), listing(6000)))
        assertEquals(2500, m)
    }

    @Test
    fun `median ignores other currencies and non-positive prices`() {
        val m = EbayPriceMath.medianCents(
            listOf(listing(2000), listing(99999, currency = "GBP"), listing(0), listing(4000)),
        )
        assertEquals(3000, m) // only the two USD positives (2000, 4000) remain → average
    }

    @Test
    fun `median of no priceable listings is null`() {
        assertNull(EbayPriceMath.medianCents(listOf(listing(0), listing(500, currency = "EUR"))))
        assertNull(EbayPriceMath.medianCents(emptyList()))
    }
}
