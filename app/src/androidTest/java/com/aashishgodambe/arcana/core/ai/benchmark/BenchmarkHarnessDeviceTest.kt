package com.aashishgodambe.arcana.core.ai.benchmark

import android.util.Log
import androidx.lifecycle.Lifecycle
import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.aashishgodambe.arcana.MainActivity
import com.aashishgodambe.arcana.core.ai.HybridGeminiService
import com.aashishgodambe.arcana.core.ai.model.InferenceLocation
import kotlinx.coroutines.flow.last
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Day-1 methodology check, run on the Pixel 10 Pro XL against the real [HybridGeminiService] — Nano on-device
 * and Gemini in the cloud. Confirms the two things a wrong headline number would hide:
 *  1. Forcing works — `OnlyOnDevice` actually runs on-device and `OnlyCloud` actually runs in the cloud
 *     (verified via the SDK-reported [InferenceLocation]), so the comparison is real, not two cloud runs.
 *  2. The sweep is sequential and BUSY-free — no errored samples across the sweep.
 *
 * Runs inside a foregrounded [MainActivity] via [ActivityScenario] because AICore on-device inference is
 * foreground-only (Week-2 finding); a headless test 606s on every on-device call. This mirrors how the app
 * actually runs the sweep — from the foreground benchmark screen.
 *
 * Heavy and network/model-dependent (minutes with a real iteration count). Run it explicitly, not as part of
 * the routine suite:
 *   JAVA_HOME="…/jbr" ./gradlew :app:connectedDebugAndroidTest \
 *     -Pandroid.testInstrumentationRunnerArguments.class=com.aashishgodambe.arcana.core.ai.benchmark.BenchmarkHarnessDeviceTest
 */
@RunWith(AndroidJUnit4::class)
class BenchmarkHarnessDeviceTest {

    @Test
    fun sweepForcesEachEngineOnDevice_andRunsSequentiallyWithoutBusy() {
        // Foreground an Activity so AICore will serve on-device inference; keep it RESUMED for the sweep.
        ActivityScenario.launch(MainActivity::class.java).use { scenario ->
            scenario.moveToState(Lifecycle.State.RESUMED)
            runBlocking { runAndAssertSweep() }
        }
    }

    private suspend fun runAndAssertSweep() {
        val harness = BenchmarkHarness(HybridGeminiService())

        // Small N: this is a methodology confirmation, not the full statistical sweep.
        val samples = harness.run(iterations = ITERATIONS).last()

        samples.forEach {
            Log.i(
                TAG,
                "engine=${it.engine.label} prompt=${it.promptId} iter=${it.iteration} cold=${it.isCold} " +
                    "loc=${it.metadata?.executedOn} firstToken=${it.metadata?.firstTokenLatencyMs}ms " +
                    "total=${it.metadata?.totalLatencyMs}ms tokens=${it.metadata?.outputTokenCount} err=${it.error}",
            )
        }

        val expectedPerEngine = BenchmarkPrompts.DEFAULT.size * ITERATIONS
        val onDevice = samples.filter { it.engine == BenchmarkEngine.OnDevice }
        val cloud = samples.filter { it.engine == BenchmarkEngine.Cloud }
        assertEquals(expectedPerEngine, onDevice.size)
        assertEquals(expectedPerEngine, cloud.size)

        // On-device is the guarantee the harness controls: no BUSY/606, everything forced on-device,
        // one honest cold sample. (Requires Nano provisioned — run OnDeviceModelProvisionTest first.)
        assertTrue(
            "on-device errors (BUSY/unprovisioned Nano?): ${onDevice.filter { it.isError }.map { it.error }}",
            onDevice.none { it.isError },
        )
        assertTrue(
            "OnlyOnDevice did not run on-device: ${onDevice.map { it.metadata?.executedOn }}",
            onDevice.all { it.metadata?.executedOn == InferenceLocation.OnDevice },
        )
        assertEquals(1, onDevice.count { it.isCold })

        // Cloud is subject to the free-tier quota (gemini-2.5-flash-lite: 20 req/min) — a real external
        // condition, not a harness fault. Assert forcing on the calls that *succeeded*; tolerate & log the
        // rate-limited ones (the aggregator reports them as errorCount honestly).
        val cloudOk = cloud.filterNot { it.isError }
        assertTrue(
            "successful OnlyCloud calls must run in cloud: ${cloudOk.map { it.metadata?.executedOn }}",
            cloudOk.all { it.metadata?.executedOn == InferenceLocation.Cloud },
        )
        val cloudRateLimited = cloud.count { it.isError }
        if (cloudRateLimited > 0) Log.w(TAG, "$cloudRateLimited/${cloud.size} cloud calls hit the free-tier quota")

        // Day-2 pipeline on real device data: aggregate the sweep to p50/p95 per cell and log the table.
        val results = BenchmarkAggregator.aggregate(samples)
        assertEquals(BenchmarkEngine.entries.size * BenchmarkPrompts.DEFAULT.size, results.size)
        results.forEach { r ->
            fun na(v: Long?) = v?.let { "${it}ms" } ?: "n/a"
            Log.i(
                TAG,
                "RESULT ${r.engine.label}/${r.promptId} · warm n=${r.warmSampleCount} · " +
                    "first-token p50=${na(r.firstTokenWarm.p50Ms)} p95=${na(r.firstTokenWarm.p95Ms)} · " +
                    "total p50=${na(r.totalWarm.p50Ms)} p95=${na(r.totalWarm.p95Ms)} · " +
                    "cold total=${na(r.coldTotalMs)} · tokens=${r.outputTokenCount ?: "n/a"} · errors=${r.errorCount}",
            )
        }
        // On-device cells honestly report "n/a" tokens (null), never 0.
        assertTrue(onDevice.isEmpty() || results.filter { it.engine == BenchmarkEngine.OnDevice }.all { it.outputTokenCount == null })
    }

    private companion object {
        const val TAG = "BenchmarkDeviceTest"
        const val ITERATIONS = 3
    }
}
