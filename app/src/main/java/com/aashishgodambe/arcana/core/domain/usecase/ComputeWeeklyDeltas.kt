package com.aashishgodambe.arcana.core.domain.usecase

import com.aashishgodambe.arcana.core.data.repository.CollectibleRepository
import com.aashishgodambe.arcana.core.domain.model.ListDelta
import com.aashishgodambe.arcana.core.domain.model.WeeklyDeltas
import kotlinx.coroutines.flow.first
import javax.inject.Inject
import kotlin.math.abs

/**
 * Computes the week's per-list value movement from the user's own snapshot series — the input the
 * on-device summary narrates. Diffs each list's value at the two most-recent shared snapshot instants
 * ("this week" vs "last week") and orders lists by how much they moved, biggest mover first.
 *
 * Returns null when there aren't yet two snapshot instants to compare (thin data) — the card then keeps
 * its "tracking just started" state instead of summarizing a single point.
 */
class ComputeWeeklyDeltas @Inject constructor(
    private val repository: CollectibleRepository,
) {
    suspend operator fun invoke(): WeeklyDeltas? {
        val series = repository.listValueSeries()
        val instants = series.values.flatMap { points -> points.map { it.at } }.distinct().sorted()
        if (instants.size < 2) return null
        val current = instants.last()
        val previous = instants[instants.size - 2]

        val lists = series.mapNotNull { (name, points) ->
            val cur = points.firstOrNull { it.at == current }?.totalValueCents ?: return@mapNotNull null
            val prev = points.firstOrNull { it.at == previous }?.totalValueCents ?: return@mapNotNull null
            ListDelta(listName = name, previousCents = prev, currentCents = cur)
        }.sortedByDescending { abs(it.deltaCents) }

        if (lists.isEmpty()) return null

        // The overall total is the portfolio-wide week delta (all items, matching the Portfolio headline)
        // — not the sum of per-list deltas, which omits any item without a list name. Falls back to the
        // list sum only when the aggregate series is unavailable.
        val portfolio = repository.observePortfolioSeries().first()
        val total = if (portfolio.size >= 2) {
            portfolio.last().totalValueCents - portfolio[portfolio.size - 2].totalValueCents
        } else {
            lists.sumOf { it.deltaCents }
        }
        return WeeklyDeltas(totalDeltaCents = total, lists = lists)
    }
}
