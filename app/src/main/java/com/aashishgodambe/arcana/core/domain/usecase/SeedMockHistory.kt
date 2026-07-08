package com.aashishgodambe.arcana.core.domain.usecase

import com.aashishgodambe.arcana.core.ai.pricing.MockPriceModel
import com.aashishgodambe.arcana.core.data.database.entity.SnapshotTrigger
import com.aashishgodambe.arcana.core.data.database.entity.ValueSource
import com.aashishgodambe.arcana.core.data.repository.CollectibleRepository
import com.aashishgodambe.arcana.core.domain.model.ValueSnapshot
import java.time.Duration
import java.time.Instant
import javax.inject.Inject

/**
 * Idempotent Week-3 scaffold: back-fills ~12 weeks of mock weekly snapshots per item so the charts show
 * a *curve* and the weekly summary has real *deltas* on first open — not a flat line on an empty axis.
 *
 * All items share the same weekly instants, so the aggregate portfolio series groups cleanly into one
 * point per week. Runs at most once (guarded by [CollectibleRepository.isHistorySeeded]); safe to call on
 * every Portfolio open. This is the vehicle the mock stands in for until a real `PriceProvider` is wired.
 */
class SeedMockHistory @Inject constructor(
    private val repository: CollectibleRepository,
) {
    suspend operator fun invoke() {
        if (repository.isHistorySeeded()) return
        val items = repository.allCollectibles()
        if (items.isEmpty()) return

        val weeks = MockPriceModel.WEEKS
        val now = Instant.now()
        // Oldest → newest; the newest instant is "now" and carries the current mock value.
        val instants = (0 until weeks).map { i -> now.minus(Duration.ofDays(7L * (weeks - 1 - i))) }

        val histories = items.associate { item ->
            val values = MockPriceModel.weeklySeriesCents(item, weeks)
            item.localId to values.mapIndexed { i, cents ->
                ValueSnapshot(
                    valueCents = cents,
                    at = instants[i],
                    source = ValueSource.EbayBrowse,   // mock stands in for the Browse source
                    trigger = SnapshotTrigger.WeeklySync,
                )
            }
        }
        repository.replaceHistories(histories)
    }
}
