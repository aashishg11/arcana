package com.aashishgodambe.arcana.core.ai.benchmark

import com.aashishgodambe.arcana.core.ai.model.InferenceMetadata

/**
 * The raw telemetry from a single inference call in the sweep — one row of Day-1 output, before any
 * aggregation. Day 2 folds a `List<BenchmarkSample>` into p50/p95 per (engine × prompt) cell.
 *
 * [isCold] marks the one honest cold-start sample: the first call for this [engine] in the *process*, when
 * the model is loaded from cold. Every later call is warm. A repeatable second cold measurement needs a
 * process restart, so at most one sample per engine per process is cold.
 *
 * [metadata] is null exactly when the call errored ([error] non-null) — e.g. an `OnlyOnDevice` call on a
 * device where Nano isn't provisioned. Errored samples are excluded from the warm/cold aggregates.
 */
data class BenchmarkSample(
    val engine: BenchmarkEngine,
    val promptId: String,
    val iteration: Int,
    val isCold: Boolean,
    val metadata: InferenceMetadata?,
    val error: String?,
) {
    val isError: Boolean get() = error != null
}
