package com.aashishgodambe.arcana.core.ai.benchmark

import com.aashishgodambe.arcana.core.ai.model.RoutingHint

/**
 * One column of the benchmark sweep — the engine an inference is forced onto for a direct comparison.
 *
 * Each engine maps to the [RoutingHint] that pins inference to it, so the harness drives the exact same
 * [com.aashishgodambe.arcana.core.ai.GeminiService] seam the app uses — no benchmark-only inference path.
 * The axis is deliberately a list, not a pair: when Week 7 adds an ExecuTorch/Gemma service, it slots in
 * as another engine (another column) with no harness or screen change.
 */
enum class BenchmarkEngine(val hint: RoutingHint, val label: String) {
    OnDevice(RoutingHint.OnlyOnDevice, "On-device"),
    Cloud(RoutingHint.OnlyCloud, "Cloud"),
}
