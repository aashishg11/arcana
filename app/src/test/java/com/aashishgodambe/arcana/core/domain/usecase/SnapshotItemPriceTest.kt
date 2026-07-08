package com.aashishgodambe.arcana.core.domain.usecase

import com.aashishgodambe.arcana.core.ai.model.PriceConfidence
import com.aashishgodambe.arcana.core.ai.model.PriceResult
import com.aashishgodambe.arcana.core.ai.pricing.FakePriceProvider
import com.aashishgodambe.arcana.core.ai.pricing.PriceProviderChain
import com.aashishgodambe.arcana.core.data.database.entity.SnapshotTrigger
import com.aashishgodambe.arcana.core.data.database.entity.ValueSource
import com.aashishgodambe.arcana.core.data.repository.FakeCollectibleRepository
import com.aashishgodambe.arcana.testFunko
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.Instant
import java.time.ZoneId

class SnapshotItemPriceTest {

    private val zone = ZoneId.of("UTC")
    private val today = Instant.parse("2026-07-08T12:00:00Z")
    private val earlierToday = Instant.parse("2026-07-08T02:00:00Z")
    private val yesterday = Instant.parse("2026-07-07T12:00:00Z")

    private fun useCase(repo: FakeCollectibleRepository, freshCents: Int): SnapshotItemPrice {
        val provider = FakePriceProvider(
            PriceResult.Success(
                valueCents = freshCents,
                source = ValueSource.EbayBrowse,
                confidence = PriceConfidence.High,
                marketContext = null,
                fetchedAt = today,
            ),
        )
        return SnapshotItemPrice(PriceProviderChain(listOf(provider)), repo)
    }

    @Test
    fun `writes a snapshot when there is no prior history`() = runTest {
        val repo = FakeCollectibleRepository(listOf(testFunko(1)))

        val result = useCase(repo, freshCents = 10_000).invoke(1, zone)

        assertTrue(result is SnapshotItemPrice.Result.Snapshotted)
        assertEquals(1, repo.snapshotsFor(1).size)
    }

    @Test
    fun `debounces a same-day within-5 percent re-snapshot`() = runTest {
        val repo = FakeCollectibleRepository(listOf(testFunko(1)))
        repo.recordSnapshot(1, 10_000, ValueSource.EbayBrowse, SnapshotTrigger.WeeklySync, earlierToday)

        val result = useCase(repo, freshCents = 10_200).invoke(1, zone) // +2%, same day

        assertTrue(result is SnapshotItemPrice.Result.AlreadyUpToDate)
        assertEquals("no duplicate row", 1, repo.snapshotsFor(1).size)
    }

    @Test
    fun `still writes when same day but the price moved more than 5 percent`() = runTest {
        val repo = FakeCollectibleRepository(listOf(testFunko(1)))
        repo.recordSnapshot(1, 10_000, ValueSource.EbayBrowse, SnapshotTrigger.WeeklySync, earlierToday)

        val result = useCase(repo, freshCents = 12_000).invoke(1, zone) // +20%, same day

        assertTrue(result is SnapshotItemPrice.Result.Snapshotted)
        assertEquals(2, repo.snapshotsFor(1).size)
    }

    @Test
    fun `still writes when within 5 percent but on a different day`() = runTest {
        val repo = FakeCollectibleRepository(listOf(testFunko(1)))
        repo.recordSnapshot(1, 10_000, ValueSource.EbayBrowse, SnapshotTrigger.WeeklySync, yesterday)

        val result = useCase(repo, freshCents = 10_200).invoke(1, zone) // +2%, prior day

        assertTrue(result is SnapshotItemPrice.Result.Snapshotted)
        assertEquals(2, repo.snapshotsFor(1).size)
    }
}
