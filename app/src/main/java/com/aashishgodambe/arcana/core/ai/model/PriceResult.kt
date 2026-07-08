package com.aashishgodambe.arcana.core.ai.model

import com.aashishgodambe.arcana.core.data.database.entity.ValueSource
import java.time.Instant
import kotlin.time.Duration

/**
 * Outcome of a [com.aashishgodambe.arcana.core.ai.pricing.PriceProvider.fetchPrice] call. A provider
 * either resolves a value (with the market context it was derived from), reports no data, or reports
 * that it is rate-limited — the chain skips a rate-limited provider and tries the next.
 */
sealed interface PriceResult {
    data class Success(
        val valueCents: Int,
        val source: ValueSource,
        val confidence: PriceConfidence,
        val marketContext: MarketContext?,
        val fetchedAt: Instant,
    ) : PriceResult

    /** Supported category, but no match / no listings found. */
    data class Unavailable(val reason: String) : PriceResult

    /** Provider is over its rate limit; the chain should skip to the next provider. */
    data class RateLimited(val retryAfter: Duration) : PriceResult
}

enum class PriceConfidence { High, Medium, Low }
