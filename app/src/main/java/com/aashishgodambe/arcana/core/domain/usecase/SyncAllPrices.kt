package com.aashishgodambe.arcana.core.domain.usecase

import com.aashishgodambe.arcana.core.ai.model.PriceResult
import com.aashishgodambe.arcana.core.ai.pricing.PriceProviderChain
import com.aashishgodambe.arcana.core.data.database.entity.SnapshotTrigger
import com.aashishgodambe.arcana.core.data.repository.CollectibleRepository
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
        var synced = 0
        for (item in repository.allCollectibles()) {
            try {
                val price = priceChain.fetchPrice(item)
                if (price is PriceResult.Success) {
                    repository.recordSnapshot(
                        localId = item.localId,
                        valueCents = price.valueCents,
                        source = price.source,
                        trigger = trigger,
                        at = price.fetchedAt,
                    )
                    synced++
                }
            } catch (c: CancellationException) {
                throw c                     // never swallow cooperative cancellation
            } catch (_: Exception) {
                // per-item best-effort — a single failure doesn't abort the sync
            }
        }
        return synced
    }
}
