package com.aashishgodambe.arcana.core.ai.benchmark

/**
 * Folds the harness's raw `List<BenchmarkSample>` into one [BenchmarkResult] per (engine × prompt) cell:
 * p50/p95 first-token and total over the *warm* samples, the single cold call kept aside, error counts, and a
 * representative output-token count (null for on-device). Pure and device-free — unit-tested without hardware.
 */
object BenchmarkAggregator {

    fun aggregate(
        samples: List<BenchmarkSample>,
        prompts: List<BenchmarkPrompt> = BenchmarkPrompts.DEFAULT,
    ): List<BenchmarkResult> {
        val labelOf = prompts.associate { it.id to it.label }
        val promptOrder = prompts.withIndex().associate { (i, p) -> p.id to i }

        return samples
            .groupBy { it.engine to it.promptId }
            .map { (cell, cellSamples) ->
                val (engine, promptId) = cell
                val nonError = cellSamples.filter { !it.isError }
                val warm = nonError.filter { !it.isCold }
                val cold = nonError.firstOrNull { it.isCold }

                val firstTokens = warm.mapNotNull { it.metadata?.firstTokenLatencyMs }
                val totals = warm.mapNotNull { it.metadata?.totalLatencyMs }
                // Tokens aren't cold-sensitive, so pool all non-error calls; null (all on-device) → "n/a".
                val tokenCounts = nonError.mapNotNull { it.metadata?.outputTokenCount }

                BenchmarkResult(
                    engine = engine,
                    promptId = promptId,
                    promptLabel = labelOf[promptId] ?: promptId,
                    firstTokenWarm = LatencyPercentiles(percentile(firstTokens, 0.50), percentile(firstTokens, 0.95)),
                    totalWarm = LatencyPercentiles(percentile(totals, 0.50), percentile(totals, 0.95)),
                    coldFirstTokenMs = cold?.metadata?.firstTokenLatencyMs,
                    coldTotalMs = cold?.metadata?.totalLatencyMs,
                    warmSampleCount = warm.size,
                    errorCount = cellSamples.count { it.isError },
                    outputTokenCount = percentile(tokenCounts.map(Int::toLong), 0.50)?.toInt(),
                )
            }
            // Stable display order: engine column, then the configured prompt order.
            .sortedWith(compareBy({ it.engine.ordinal }, { promptOrder[it.promptId] ?: Int.MAX_VALUE }))
    }
}
