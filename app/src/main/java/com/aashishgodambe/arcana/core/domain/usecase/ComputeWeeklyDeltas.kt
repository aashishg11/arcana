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
        // Anchor "this week" vs "last week" to the two most recent *batch* instants from the aggregate
        // portfolio series (which already excludes single-item snapshots) — so a lone "Snapshot today's
        // price" can't be mistaken for a weekly sample. The overall total is this portfolio-wide delta
        // (all items, matching the headline), not the sum of per-list deltas.
        val portfolio = repository.observePortfolioSeries().first()
        if (portfolio.size < 2) return null
        val current = portfolio.last().at
        val previous = portfolio[portfolio.size - 2].at
        val total = portfolio.last().totalValueCents - portfolio[portfolio.size - 2].totalValueCents

        val series = repository.listValueSeries()
        val lists = series.mapNotNull { (name, points) ->
            val cur = points.firstOrNull { it.at == current }?.totalValueCents ?: return@mapNotNull null
            val prev = points.firstOrNull { it.at == previous }?.totalValueCents ?: return@mapNotNull null
            ListDelta(listName = name, previousCents = prev, currentCents = cur)
        }.sortedByDescending { abs(it.deltaCents) }

        if (lists.isEmpty()) return null
        return WeeklyDeltas(totalDeltaCents = total, lists = lists)
    }
}
