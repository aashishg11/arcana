package com.aashishgodambe.arcana.core.domain.usecase

import com.aashishgodambe.arcana.core.data.repository.FakeCollectibleRepository
import com.aashishgodambe.arcana.core.domain.model.PortfolioPoint
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import java.time.Instant

class ComputeWeeklyDeltasTest {

    private val lastWeek = Instant.parse("2026-07-01T00:00:00Z")
    private val thisWeek = Instant.parse("2026-07-08T00:00:00Z")

    @Test
    fun `diffs the two most recent instants and orders by biggest mover`() = runTest {
        val repo = FakeCollectibleRepository().apply {
            listSeries = mapOf(
                "Nft funko" to listOf(PortfolioPoint(lastWeek, 1_958_400), PortfolioPoint(thisWeek, 2_003_500)),
                "Marvel" to listOf(PortfolioPoint(lastWeek, 237_300), PortfolioPoint(thisWeek, 221_000)),
            )
            // Portfolio-wide series includes list-less items, so its delta (24_000) differs from the
            // sum of per-list deltas (28_800).
            portfolioSeries = listOf(PortfolioPoint(lastWeek, 2_200_000), PortfolioPoint(thisWeek, 2_224_000))
        }

        val deltas = ComputeWeeklyDeltas(repo).invoke()!!

        // Nft funko moved +$451, Marvel -$163 → Nft funko leads by magnitude.
        assertEquals("Nft funko", deltas.lists.first().listName)
        assertEquals(45_100, deltas.lists.first().deltaCents)
        assertEquals(-16_300, deltas.lists[1].deltaCents)
        // Total is the portfolio-wide delta (matches the headline), NOT the sum of list deltas.
        assertEquals(24_000, deltas.totalDeltaCents)
    }

    @Test
    fun `returns null with fewer than two instants to compare`() = runTest {
        val repo = FakeCollectibleRepository().apply {
            listSeries = mapOf("Nft funko" to listOf(PortfolioPoint(thisWeek, 2_003_500)))
        }
        assertNull(ComputeWeeklyDeltas(repo).invoke())
    }
}
