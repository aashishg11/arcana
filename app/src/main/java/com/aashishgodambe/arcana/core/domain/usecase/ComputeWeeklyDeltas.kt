package com.aashishgodambe.arcana.core.domain.usecase

import com.aashishgodambe.arcana.core.data.repository.CollectibleRepository
import com.aashishgodambe.arcana.core.domain.model.ListDelta
import com.aashishgodambe.arcana.core.domain.model.WeeklyDeltas
import kotlinx.coroutines.flow.first
import java.time.Duration
import javax.inject.Inject
import kotlin.math.abs

/**
 * Computes the last ~7 days of per-list value movement from the user's own snapshot series — the input the
 * on-device summary narrates. Uses the **same 7-day window as the headline delta** (latest batch instant vs
 * the newest instant at least a week older), so the summary and the "$X this week" header always agree, and
 * a burst of same-day syncs can't be mistaken for weekly movement. Orders lists by how much they moved.
 *
 * Returns null when there's no instant a week old yet (thin data) — the card keeps its "tracking just
 * started" state instead of summarizing over too short a window.
 */
class ComputeWeeklyDeltas @Inject constructor(
    private val repository: CollectibleRepository,
) {
    suspend operator fun invoke(): WeeklyDeltas? {
        // Anchor "this week" to the same 7-day window as the headline delta: the latest batch instant vs the
        // newest instant at least a week older. The overall total is this portfolio-wide delta (all items,
        // matching the headline), not the sum of per-list deltas.
        val portfolio = repository.observePortfolioSeries().first()
        if (portfolio.isEmpty()) return null
        val latest = portfolio.last()
        val reference = portfolio.lastOrNull { it.at <= latest.at.minus(Duration.ofDays(7)) } ?: return null
        val current = latest.at
        val previous = reference.at
        val total = latest.totalValueCents - reference.totalValueCents

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
