package com.aashishgodambe.arcana.core.ai.model

/**
 * Benchmark facts captured on every inference — first-class data from Day 1, not a Week-10 addition.
 * [firstTokenLatencyMs] is captured separately from [totalLatencyMs] because Nano's first call is a
 * 2–5s cold-start warm-up that would otherwise pollute steady-state numbers.
 */
data class InferenceMetadata(
    val executedOn: InferenceLocation,
    val totalLatencyMs: Long,
    val firstTokenLatencyMs: Long?,
    val outputTokenCount: Int?,
)
