package com.aashishgodambe.arcana.core.ai.benchmark

/** p50/p95 for one latency dimension. Both null when the cell had no usable samples. */
data class LatencyPercentiles(val p50Ms: Long?, val p95Ms: Long?) {
    companion object {
        val EMPTY = LatencyPercentiles(null, null)
    }
}

/**
 * The aggregated benchmark for one (engine × prompt) cell — what the Day-3 screen renders. Warm percentiles
 * exclude the cold call so a 2–5 s cold-start warm-up doesn't pollute steady-state; the single cold call is
 * reported separately in [coldFirstTokenMs]/[coldTotalMs] (both null when this cell held no cold sample —
 * cold is once per engine, so only one prompt's cell carries it).
 *
 * [outputTokenCount] is null → the UI shows **"n/a"**, never 0: on-device inference never reports token
 * counts (Week-2 finding); only cloud populates them. [errorCount] surfaces failed calls (e.g. on-device 606
 * when Nano is unprovisioned) honestly instead of hiding them.
 */
data class BenchmarkResult(
    val engine: BenchmarkEngine,
    val promptId: String,
    val promptLabel: String,
    val firstTokenWarm: LatencyPercentiles,
    val totalWarm: LatencyPercentiles,
    val coldFirstTokenMs: Long?,
    val coldTotalMs: Long?,
    val warmSampleCount: Int,
    val errorCount: Int,
    val outputTokenCount: Int?,
)
