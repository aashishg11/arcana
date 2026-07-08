package com.aashishgodambe.arcana.core.domain.usecase

import com.aashishgodambe.arcana.core.ai.model.PriceResult
import com.aashishgodambe.arcana.core.ai.pricing.PriceProviderChain
import com.aashishgodambe.arcana.core.data.database.entity.SnapshotTrigger
import com.aashishgodambe.arcana.core.data.repository.CollectibleRepository
import java.time.Instant
import java.time.ZoneId
import javax.inject.Inject
import kotlin.math.abs

/**
 * "Snapshot today's price" — fetches a fresh value via the [PriceProviderChain] and commits it to the
 * item's tracked history (`trigger = UserRefresh`), refreshing the cached current value.
 *
 * Debounced: if the newest existing snapshot is from the **same calendar day** *and* **within 5%** of the
 * fresh value, no row is written and the caller reports "already up to date". Both conditions are
 * required — a same-day but materially different price still commits, as does a >1-day-old equal price.
 */
class SnapshotItemPrice @Inject constructor(
    private val priceChain: PriceProviderChain,
    private val repository: CollectibleRepository,
) {
    sealed interface Result {
        data class Snapshotted(val valueCents: Int) : Result
        data class AlreadyUpToDate(val valueCents: Int) : Result
        data class Unavailable(val reason: String) : Result
    }

    suspend operator fun invoke(localId: Long, zone: ZoneId = ZoneId.systemDefault()): Result {
        val item = repository.getById(localId) ?: return Result.Unavailable("Item not found")
        return when (val price = priceChain.fetchPrice(item)) {
            is PriceResult.Success -> {
                val latest = repository.latestSnapshot(localId)
                val debounced = latest != null &&
                    isSameDay(latest.at, price.fetchedAt, zone) &&
                    withinPercent(latest.valueCents, price.valueCents, DEBOUNCE_FRACTION)
                if (debounced) {
                    Result.AlreadyUpToDate(latest!!.valueCents)
                } else {
                    repository.recordSnapshot(
                        localId = localId,
                        valueCents = price.valueCents,
                        source = price.source,
                        trigger = SnapshotTrigger.UserRefresh,
                        at = price.fetchedAt,
                    )
                    Result.Snapshotted(price.valueCents)
                }
            }
            is PriceResult.Unavailable -> Result.Unavailable(price.reason)
            is PriceResult.RateLimited -> Result.Unavailable("Price source is rate limited")
        }
    }

    companion object {
        const val DEBOUNCE_FRACTION = 0.05
    }
}

internal fun isSameDay(a: Instant, b: Instant, zone: ZoneId): Boolean =
    a.atZone(zone).toLocalDate() == b.atZone(zone).toLocalDate()

/** True when [candidate] is within [fraction] of [reference] (relative), e.g. 0.05 = ±5%. */
internal fun withinPercent(reference: Int, candidate: Int, fraction: Double): Boolean {
    if (reference == 0) return candidate == 0
    return abs(candidate - reference).toDouble() / reference <= fraction
}
