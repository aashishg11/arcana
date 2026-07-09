package com.aashishgodambe.arcana.core.ai.benchmark

import android.util.Log
import com.aashishgodambe.arcana.core.ai.GeminiService
import com.aashishgodambe.arcana.core.ai.model.InferenceMetadata
import com.aashishgodambe.arcana.core.ai.model.InferenceResult
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Runs the latency sweep by driving the real [GeminiService] seam — the same interface that powers the app
 * powers the benchmark. Each [BenchmarkEngine] pins inference to on-device or cloud via its [RoutingHint], so
 * the comparison is genuine and not two cloud runs.
 *
 * **Sequential by construction.** AICore serves one inference at a time (BUSY on overlap). Every call is
 * fully awaited before the next begins — the sweep is a sequence, never a fan-out — so the harness never
 * races the [com.aashishgodambe.arcana.core.ai.HybridGeminiService] `Mutex`.
 *
 * **Cold is once per process.** [Singleton] so [attemptedEngines] persists across runs: the first call for an
 * engine after process start is the one honest cold sample; a second "cold" needs a real process restart, so
 * later runs in the same process are all warm.
 */
@Singleton
class BenchmarkHarness @Inject constructor(
    private val gemini: GeminiService,
) {

    /** Engines that have already had their cold call in this process — see class doc. */
    private val attemptedEngines = mutableSetOf<BenchmarkEngine>()

    /**
     * Streams the sweep. Each emission is the [List] of samples collected so far, so a caller can render
     * progressively; the final emission is the complete set. [onProgress] fires once before every call for
     * per-cell UI feedback.
     *
     * For each (engine × prompt) cell it runs [iterations] calls back-to-back. Cancelling the collector
     * cancels the sweep between calls.
     *
     * [cloudPaceMs] inserts a delay *before* each cloud call to stay under the free-tier quota (20 req/min for
     * `gemini-2.5-flash-lite`). The delay sits between calls, not inside one, so it never distorts a measured
     * per-call latency — but it does keep the SDK's retry/backoff from inflating "successful" cloud numbers.
     *
     * [cloudIterations] (default: same as [iterations]) lets the cloud column run *fewer* iterations than
     * on-device — cloud latency is low-variance, so a small N gives a fine p50, and every cloud call spends a
     * scarce free-tier budget. Keep it small.
     */
    fun run(
        prompts: List<BenchmarkPrompt> = BenchmarkPrompts.DEFAULT,
        engines: List<BenchmarkEngine> = BenchmarkEngine.entries,
        iterations: Int = DEFAULT_ITERATIONS,
        cloudIterations: Int? = null,
        cloudPaceMs: Long = 0L,
        onProgress: (BenchmarkProgress) -> Unit = {},
    ): Flow<List<BenchmarkSample>> = flow {
        require(iterations >= 1) { "iterations must be >= 1" }
        require(cloudIterations == null || cloudIterations >= 1) { "cloudIterations must be >= 1" }
        fun itersFor(engine: BenchmarkEngine) =
            if (engine == BenchmarkEngine.Cloud) cloudIterations ?: iterations else iterations

        val samples = mutableListOf<BenchmarkSample>()
        val total = engines.sumOf { prompts.size * itersFor(it) }
        var completed = 0

        // Engine-outer so all of an engine's calls are contiguous: its single cold sample lands first, and we
        // don't thrash between the on-device and cloud models.
        for (engine in engines) {
            val iters = itersFor(engine)
            for (prompt in prompts) {
                repeat(iters) { iteration ->
                    currentCoroutineContext().ensureActive()
                    onProgress(BenchmarkProgress(completed, total, engine, prompt))

                    if (engine == BenchmarkEngine.Cloud && cloudPaceMs > 0) delay(cloudPaceMs)
                    val cold = engine !in attemptedEngines
                    attemptedEngines += engine
                    val (metadata, error) = runOnce(prompt.text, engine)

                    samples += BenchmarkSample(
                        engine = engine,
                        promptId = prompt.id,
                        iteration = iteration,
                        isCold = cold,
                        metadata = metadata,
                        error = error,
                    )
                    completed++
                    emit(samples.toList())
                }
            }
        }
        Log.i(TAG, "sweep done · ${samples.size} samples · ${samples.count { it.isError }} errored")
    }

    /** Drives one inference to its terminal result, discarding the streamed text — we only want telemetry. */
    private suspend fun runOnce(prompt: String, engine: BenchmarkEngine): Pair<InferenceMetadata?, String?> {
        var metadata: InferenceMetadata? = null
        var error: String? = null
        gemini.generateText(prompt, engine.hint).collect { result ->
            when (result) {
                is InferenceResult.Streaming -> Unit
                is InferenceResult.Success -> metadata = result.metadata
                is InferenceResult.Error -> error = result.cause.message ?: "Inference failed"
            }
        }
        if (error != null) Log.w(TAG, "call errored · engine=${engine.label} · $error")
        return metadata to error
    }

    private companion object {
        const val TAG = "BenchmarkHarness"
        /** ~20 warm samples/cell — indicative, not production-grade statistics; labeled as such in-app. */
        const val DEFAULT_ITERATIONS = 20
    }
}
