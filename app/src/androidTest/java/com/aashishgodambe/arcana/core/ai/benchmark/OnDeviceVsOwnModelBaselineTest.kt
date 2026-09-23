package com.aashishgodambe.arcana.core.ai.benchmark

import android.content.Context
import android.util.Log
import androidx.lifecycle.Lifecycle
import androidx.test.core.app.ActivityScenario
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.aashishgodambe.arcana.MainActivity
import com.aashishgodambe.arcana.core.ai.DelegatingGeminiService
import com.aashishgodambe.arcana.core.ai.HybridGeminiService
import com.aashishgodambe.arcana.core.ai.LiteRtGeminiService
import com.aashishgodambe.arcana.core.ai.model.AskEngine
import com.aashishgodambe.arcana.core.ai.model.InferenceLocation
import kotlinx.coroutines.flow.last
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

/**
 * **CPU baseline sweep** for a new device — currently the Snapdragon 8 Elite Gen 5 (SM8850) Galaxy S26.
 *
 * Deliberately measures **only** the two on-device columns, no cloud:
 *  - [BenchmarkEngine.OnDevice] → Gemini Nano via AICore (the open question on Samsung silicon), and
 *  - [BenchmarkEngine.OwnModel] → the self-quantized Gemma 3 1B INT4 on **CPU** (LiteRT), the number to
 *    compare against the Pixel's Week-6 baseline of 27.4 tok/s / 1077 MB.
 *
 * Cloud is excluded on purpose: this is a hardware baseline, and cloud spends the scarce free-tier budget.
 *
 * Unlike [BenchmarkHarnessDeviceTest] (which drives [HybridGeminiService] directly and so never exercises
 * the own-model lane), this drives the real [DelegatingGeminiService] seam so `OnlyOwnModel` actually reaches
 * [LiteRtGeminiService]. Requires the q4 `.litertlm` side-loaded first; run via `am instrument` (NOT
 * `connectedDebugAndroidTest`, which reinstalls and wipes the side-loaded model).
 *
 * Soft-asserts only — a baseline observes, it doesn't gate. Nano may 606 on this device (logged, not failed);
 * the LiteRT column is the one we assert actually produced tokens. Read the tok/s off the `BASELINE` logcat.
 *
 *   JAVA_HOME="…/jbr" ./gradlew :app:installDebug :app:installDebugAndroidTest
 *   # …side-load the model…
 *   adb -s <serial> shell am instrument -w -e class \
 *     com.aashishgodambe.arcana.core.ai.benchmark.OnDeviceVsOwnModelBaselineTest \
 *     com.aashishgodambe.arcana.test/androidx.test.runner.AndroidJUnitRunner
 */
@RunWith(AndroidJUnit4::class)
class OnDeviceVsOwnModelBaselineTest {

    @Test
    fun cpuAndNanoBaseline() {
        // Foreground an Activity — AICore on-device inference (Nano) is foreground-only (Week-2 finding).
        ActivityScenario.launch(MainActivity::class.java).use { scenario ->
            scenario.moveToState(Lifecycle.State.RESUMED)
            runBlocking { runBaseline() }
        }
    }

    private suspend fun runBaseline() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val liteRt = LiteRtGeminiService(context)
        Log.i(TAG, "own-model available=${liteRt.isModelAvailable()} at ${liteRt.modelFile().absolutePath}")

        // Drive the real routing seam: OnlyOnDevice → Nano, OnlyOwnModel → LiteRT (CPU). selectedEngine is
        // never consulted here (explicit hints bypass it), but the constructor requires it.
        val service = DelegatingGeminiService(
            hybrid = HybridGeminiService(),
            ownModel = liteRt,
            selectedEngine = { AskEngine.Nano },
        )
        val harness = BenchmarkHarness(service)

        val engines = listOf(BenchmarkEngine.OnDevice, BenchmarkEngine.OwnModel)
        val samples = harness.run(engines = engines, iterations = ITERATIONS).last()

        samples.forEach {
            Log.i(
                TAG,
                "engine=${it.engine.label} prompt=${it.promptId} iter=${it.iteration} cold=${it.isCold} " +
                    "loc=${it.metadata?.executedOn} firstToken=${it.metadata?.firstTokenLatencyMs}ms " +
                    "total=${it.metadata?.totalLatencyMs}ms tokens=${it.metadata?.outputTokenCount} err=${it.error}",
            )
        }

        // Aggregate to p50/p95 per cell and log a table; compute tok/s for the own-model (CPU) column.
        val results = BenchmarkAggregator.aggregate(samples)
        results.forEach { r ->
            fun na(v: Long?) = v?.let { "${it}ms" } ?: "n/a"
            val tps = ownModelTokensPerSec(r)
            Log.i(
                TAG,
                "BASELINE ${r.engine.label}/${r.promptId} · warm n=${r.warmSampleCount} · " +
                    "first-token p50=${na(r.firstTokenWarm.p50Ms)} p95=${na(r.firstTokenWarm.p95Ms)} · " +
                    "total p50=${na(r.totalWarm.p50Ms)} p95=${na(r.totalWarm.p95Ms)} · " +
                    "cold total=${na(r.coldTotalMs)} · tokens=${r.outputTokenCount ?: "n/a"} · " +
                    "tok/s=${tps ?: "n/a"} · errors=${r.errorCount}",
            )
        }

        // Report Nano's fate without gating on it (may be unprovisioned/606 on Samsung silicon).
        val onDevice = samples.filter { it.engine == BenchmarkEngine.OnDevice }
        val nanoOk = onDevice.filter { !it.isError && it.metadata?.executedOn == InferenceLocation.OnDevice }
        Log.i(TAG, "NANO: ${nanoOk.size}/${onDevice.size} calls ran on-device; errors=${onDevice.count { it.isError }}")
        if (onDevice.any { it.isError }) {
            Log.w(TAG, "NANO errors (unprovisioned on this device?): ${onDevice.filter { it.isError }.map { it.error }.distinct()}")
        }

        // The LiteRT (CPU) column is the one this baseline is really for — assert it actually produced tokens.
        val ownModel = samples.filter { it.engine == BenchmarkEngine.OwnModel }
        val ownOk = ownModel.filter { !it.isError && (it.metadata?.outputTokenCount ?: 0) > 0 }
        assertTrue(
            "own-model (LiteRT CPU) produced no tokens — model side-loaded? errors=${ownModel.filter { it.isError }.map { it.error }.distinct()}",
            ownOk.isNotEmpty(),
        )
    }

    /** Warm tok/s for the own-model cell: aggregate output tokens over warm p50 total latency. */
    private fun ownModelTokensPerSec(r: BenchmarkResult): String? {
        if (r.engine != BenchmarkEngine.OwnModel) return null
        val tokens = r.outputTokenCount ?: return null
        val p50 = r.totalWarm.p50Ms ?: return null
        if (p50 <= 0) return null
        return "%.1f".format(tokens * 1000.0 / p50)
    }

    private companion object {
        const val TAG = "S26Baseline"
        // Enough warm samples for a stable p50 on two short prompts, without a long sweep.
        const val ITERATIONS = 8
    }
}
