package com.aashishgodambe.arcana.core.ai.pricing

import com.aashishgodambe.arcana.core.domain.model.Collectible
import com.aashishgodambe.arcana.core.domain.model.FunkoPop
import kotlin.random.Random

/**
 * Deterministic mock price model — the single source of truth for the mock's drift, shared by
 * [MockPriceProvider] (current value + listings) and the Day-2 backdated seeder (weekly history), so the
 * seeded curve climbs coherently into the value the provider reports "now".
 *
 * Seeded by [Collectible.localId], so every value is stable across calls and across process restarts —
 * no `Math.random`, no wall-clock. NFT-redeemable Pops are given a stronger upward trend and higher
 * volatility: they're the volatile segment that makes the weekly "what moved" summary have something to
 * say (matches the wireframe's "NFT-redeemable Pops drove the move").
 */
object MockPriceModel {

    /** Weeks of synthetic history the model spans; the newest point is the current value. */
    const val WEEKS = 12

    private const val SALT = 0x5F3759DFL

    /**
     * Weekly values, oldest → newest, for the last [weeks] weeks (clamped to [WEEKS]). The newest is the
     * current value. Always walks the full [WEEKS] internally and slices, so the current value is stable
     * regardless of how many weeks a caller requests.
     */
    fun weeklySeriesCents(collectible: Collectible, weeks: Int = WEEKS): List<Int> {
        val full = fullSeries(collectible)
        return full.takeLast(weeks.coerceIn(1, WEEKS))
    }

    /** The current (newest) mock value in cents — what the provider reports for "now". */
    fun currentValueCents(collectible: Collectible): Int = fullSeries(collectible).last()

    private fun fullSeries(collectible: Collectible): List<Int> {
        val baseline = collectible.estimatedValueCents.coerceAtLeast(1)
        val rng = Random(collectible.localId xor SALT)
        val nft = (collectible as? FunkoPop)?.isNftRedeemable == true

        val weeklyTrend = if (nft) rng.nextDouble(0.004, 0.020) else rng.nextDouble(-0.004, 0.010)
        val volatility = if (nft) rng.nextDouble(0.02, 0.06) else rng.nextDouble(0.008, 0.03)

        // Start below the trend line so the series climbs into "now"; import baseline anchors the scale.
        var v = baseline * rng.nextDouble(0.82, 0.98)
        val out = ArrayList<Int>(WEEKS)
        repeat(WEEKS) {
            val noise = rng.nextDouble(-volatility, volatility)
            v *= (1.0 + weeklyTrend + noise)
            out += v.toInt().coerceAtLeast(1)
        }
        return out
    }
}
