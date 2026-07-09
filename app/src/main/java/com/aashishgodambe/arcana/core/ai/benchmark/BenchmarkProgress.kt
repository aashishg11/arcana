package com.aashishgodambe.arcana.core.ai.benchmark

/**
 * Live progress for the (minute-plus) sweep, emitted before each call so the Day-3 screen can show real
 * per-cell progress instead of a frozen spinner. [completed]/[total] count individual inference calls across
 * the whole sweep; [engine]/[prompt] name the cell about to run.
 */
data class BenchmarkProgress(
    val completed: Int,
    val total: Int,
    val engine: BenchmarkEngine,
    val prompt: BenchmarkPrompt,
)
