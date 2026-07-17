package com.aashishgodambe.arcana.core.domain.usecase

import com.aashishgodambe.arcana.core.ai.model.PriceResult
import com.aashishgodambe.arcana.core.ai.pricing.PriceProviderChain
import com.aashishgodambe.arcana.core.data.database.entity.SnapshotTrigger
import com.aashishgodambe.arcana.core.data.repository.CollectibleRepository
import kotlinx.coroutines.delay
import java.time.Instant
import javax.inject.Inject
import kotlin.coroutines.cancellation.CancellationException

/**
 * Fetches a fresh value for every item via the [PriceProviderChain] and writes a snapshot per success.
 * Backs both the weekly worker (`trigger = WeeklySync`) and the on-demand "Sync now" button
 * (`trigger = UserRefresh`). Per-item best-effort: one item's failure is swallowed so a single bad fetch
 * can't abort the whole run. Returns the number of items snapshotted.
 */
class SyncAllPrices @Inject constructor(
    private val priceChain: PriceProviderChain,
    private val repository: CollectibleRepository,
) {
    suspend operator fun invoke(trigger: SnapshotTrigger = SnapshotTrigger.WeeklySync): Int {
        // One timestamp for the whole sync so all items land on a single portfolio-series instant (one
        // aggregate point), not one instant per item.
        val syncedAt = Instant.now()
        var synced = 0
        val items = repository.allCollectibles()
        items.forEachIndexed { index, item ->
            try {
                val price = priceChain.fetchPrice(item)
                if (price is PriceResult.Success) {
                    repository.recordSnapshot(
                        localId = item.localId,
                        valueCents = price.valueCents,
                        source = price.source,
                        trigger = trigger,
                        at = syncedAt,
                    )
                    synced++
                }
            } catch (c: CancellationException) {
                throw c                     // never swallow cooperative cancellation
            } catch (_: Exception) {
                // per-item best-effort — a single failure doesn't abort the sync
            }
            // Pace the price source: a tight loop bursts past eBay Browse's per-second throttle, and
            // throttled items fall through to the mock. A small gap keeps the whole sweep on real prices.
            if (index < items.lastIndex) delay(PACING_MS)
        }
        return synced
    }

    private companion object {
        /** Gap between per-item price fetches, to stay under eBay Browse's burst throttle. */
        const val PACING_MS = 350L
    }
}
