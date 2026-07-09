package com.aashishgodambe.arcana.core.ai.benchmark

import kotlin.math.ceil
import kotlin.math.floor
import kotlin.math.roundToLong

/**
 * Linear-interpolation percentile — the type-7 method (Excel `PERCENTILE.INC`, NumPy default), so p50 is the
 * conventional median (mean of the two middle values on even N). [fraction] is in `[0, 1]`. Returns null for
 * empty input. Result is rounded to whole milliseconds.
 *
 * Over the benchmark's small N (~20 warm samples) a p95 is *indicative, not rigorous* — the rigor is in
 * measuring and labeling it honestly, not in the sample size. Callers surface that caveat in the UI.
 */
internal fun percentile(values: List<Long>, fraction: Double): Long? {
    require(fraction in 0.0..1.0) { "fraction must be in [0,1], was $fraction" }
    if (values.isEmpty()) return null
    val sorted = values.sorted()
    if (sorted.size == 1) return sorted.first()
    val rank = fraction * (sorted.size - 1) // 0-based rank
    val lo = floor(rank).toInt()
    val hi = ceil(rank).toInt()
    if (lo == hi) return sorted[lo]
    val weight = rank - lo
    return (sorted[lo] + (sorted[hi] - sorted[lo]) * weight).roundToLong()
}
