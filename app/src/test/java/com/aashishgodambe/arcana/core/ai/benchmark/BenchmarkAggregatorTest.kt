package com.aashishgodambe.arcana.core.ai.benchmark

import com.aashishgodambe.arcana.core.ai.model.InferenceLocation
import com.aashishgodambe.arcana.core.ai.model.InferenceMetadata
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class BenchmarkAggregatorTest {

    @Test
    fun `aggregates warm p50p95, keeps cold aside, and orders by engine then prompt`() {
        val samples = listOf(
            // On-device · short: one cold call + two warm
            sample(BenchmarkEngine.OnDevice, "short", 0, cold = true, ft = 800, total = 3800, tokens = null),
            sample(BenchmarkEngine.OnDevice, "short", 1, cold = false, ft = 400, total = 3400, tokens = null),
            sample(BenchmarkEngine.OnDevice, "short", 2, cold = false, ft = 500, total = 3000, tokens = null),
            // On-device · grounded: warm only (cold belongs to the short cell)
            sample(BenchmarkEngine.OnDevice, "grounded", 0, cold = false, ft = 520, total = 2800, tokens = null),
            sample(BenchmarkEngine.OnDevice, "grounded", 1, cold = false, ft = 460, total = 2700, tokens = null),
            // Cloud · short: cold + warm, tokens populated
            sample(BenchmarkEngine.Cloud, "short", 0, cold = true, ft = 1000, total = 1000, tokens = 24),
            sample(BenchmarkEngine.Cloud, "short", 1, cold = false, ft = 700, total = 700, tokens = 40),
            // Cloud · grounded
            sample(BenchmarkEngine.Cloud, "grounded", 0, cold = false, ft = 600, total = 600, tokens = 17),
            sample(BenchmarkEngine.Cloud, "grounded", 1, cold = false, ft = 610, total = 610, tokens = 19),
        )

        val results = BenchmarkAggregator.aggregate(samples)

        // Stable order: engine column, then configured prompt order (short before grounded).
        assertEquals(
            listOf(
                BenchmarkEngine.OnDevice to "short",
                BenchmarkEngine.OnDevice to "grounded",
                BenchmarkEngine.Cloud to "short",
                BenchmarkEngine.Cloud to "grounded",
            ),
            results.map { it.engine to it.promptId },
        )

        val odShort = results.single { it.engine == BenchmarkEngine.OnDevice && it.promptId == "short" }
        assertEquals("Short question", odShort.promptLabel)
        // warm first-token over [400,500]; cold 800 excluded
        assertEquals(450L, odShort.firstTokenWarm.p50Ms)
        assertEquals(495L, odShort.firstTokenWarm.p95Ms)
        // warm total over [3400,3000]
        assertEquals(3200L, odShort.totalWarm.p50Ms)
        // the one cold call is reported separately
        assertEquals(800L, odShort.coldFirstTokenMs)
        assertEquals(3800L, odShort.coldTotalMs)
        assertEquals(2, odShort.warmSampleCount)
        assertEquals(0, odShort.errorCount)
        // on-device never reports tokens → "n/a"
        assertNull(odShort.outputTokenCount)

        val odGrounded = results.single { it.engine == BenchmarkEngine.OnDevice && it.promptId == "grounded" }
        // no cold in this cell
        assertNull(odGrounded.coldFirstTokenMs)
        assertNull(odGrounded.coldTotalMs)
        assertEquals(490L, odGrounded.firstTokenWarm.p50Ms)

        val cloudShort = results.single { it.engine == BenchmarkEngine.Cloud && it.promptId == "short" }
        assertEquals(1000L, cloudShort.coldFirstTokenMs)
        // single warm sample → p50 == p95 == that value
        assertEquals(700L, cloudShort.totalWarm.p50Ms)
        assertEquals(700L, cloudShort.totalWarm.p95Ms)
        // token count pools all non-error calls (cold 24 + warm 40) → median 32
        assertEquals(32, cloudShort.outputTokenCount)

        val cloudGrounded = results.single { it.engine == BenchmarkEngine.Cloud && it.promptId == "grounded" }
        assertEquals(18, cloudGrounded.outputTokenCount)
    }

    @Test
    fun `errored samples are counted and excluded from percentiles`() {
        val samples = listOf(
            sample(BenchmarkEngine.OnDevice, "short", 0, cold = true, ft = 800, total = 3800, tokens = null),
            errorSample(BenchmarkEngine.OnDevice, "short", 1, "On-device model is not available"),
            sample(BenchmarkEngine.OnDevice, "short", 2, cold = false, ft = 500, total = 3000, tokens = null),
        )

        val result = BenchmarkAggregator.aggregate(samples, prompts = BenchmarkPrompts.DEFAULT).single()

        assertEquals(1, result.errorCount)
        // only the single non-error warm sample feeds the percentiles
        assertEquals(1, result.warmSampleCount)
        assertEquals(500L, result.firstTokenWarm.p50Ms)
        assertEquals(3000L, result.totalWarm.p50Ms)
        // cold still reported
        assertEquals(3800L, result.coldTotalMs)
    }

    @Test
    fun `a cell with only errors yields empty percentiles, not zeros`() {
        val samples = listOf(
            errorSample(BenchmarkEngine.OnDevice, "short", 0, "boom"),
            errorSample(BenchmarkEngine.OnDevice, "short", 1, "boom"),
        )

        val result = BenchmarkAggregator.aggregate(samples).single()

        assertEquals(2, result.errorCount)
        assertEquals(0, result.warmSampleCount)
        assertNull(result.totalWarm.p50Ms)
        assertNull(result.firstTokenWarm.p50Ms)
        assertNull(result.coldTotalMs) // the cold call errored, so no cold latency
        assertNull(result.outputTokenCount)
    }

    private fun sample(
        engine: BenchmarkEngine,
        promptId: String,
        iteration: Int,
        cold: Boolean,
        ft: Long?,
        total: Long,
        tokens: Int?,
    ) = BenchmarkSample(
        engine = engine,
        promptId = promptId,
        iteration = iteration,
        isCold = cold,
        metadata = InferenceMetadata(
            executedOn = if (engine == BenchmarkEngine.Cloud) InferenceLocation.Cloud else InferenceLocation.OnDevice,
            totalLatencyMs = total,
            firstTokenLatencyMs = ft,
            outputTokenCount = tokens,
        ),
        error = null,
    )

    private fun errorSample(engine: BenchmarkEngine, promptId: String, iteration: Int, error: String) =
        BenchmarkSample(engine, promptId, iteration, isCold = iteration == 0, metadata = null, error = error)
}
