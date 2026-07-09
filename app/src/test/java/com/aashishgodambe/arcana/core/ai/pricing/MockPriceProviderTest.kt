package com.aashishgodambe.arcana.core.ai.pricing

import com.aashishgodambe.arcana.core.ai.model.PriceResult
import com.aashishgodambe.arcana.core.domain.model.FunkoPop
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.LocalDate

class MockPriceProviderTest {

    private val provider = MockPriceProvider()

    private val item = FunkoPop(
        localId = 42, name = "Daenerys with Egg", brand = "Funko", imageUrl = null,
        estimatedValueCents = 69_000, lastKnownValueCents = null, quantity = 1,
        itemCondition = "Mint", packagingCondition = "Mint", series = emptyList(),
        productionTags = emptyList(), dateAdded = LocalDate.of(2023, 1, 1),
        pricePaidCents = null, storageLocation = null,
        upc = "0", popNumber = null, exclusiveTo = null, isNftRedeemable = true,
    )

    @Test
    fun `per-sync jitter makes consecutive fetches differ, so Sync now yields non-zero deltas`() = runTest {
        val values = (1..12).map { (provider.fetchPrice(item) as PriceResult.Success).valueCents }

        // Non-seeded jitter → not a dead-flat single value across syncs.
        assertTrue("expected varied values across syncs, got $values", values.distinct().size > 1)

        // …but still anchored near the model's current value (jitter is small: roughly -2%..+3%).
        val anchor = MockPriceModel.currentValueCents(item)
        assertTrue(
            "jittered values out of expected band around $anchor: $values",
            values.all { it in (anchor * 0.90).toInt()..(anchor * 1.10).toInt() },
        )
    }
}
