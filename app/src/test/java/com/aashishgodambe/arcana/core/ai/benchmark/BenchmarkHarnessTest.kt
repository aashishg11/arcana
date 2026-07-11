package com.aashishgodambe.arcana.core.ai.benchmark

import com.aashishgodambe.arcana.core.ai.GeminiService
import com.aashishgodambe.arcana.core.ai.model.InferenceLocation
import com.aashishgodambe.arcana.core.ai.model.InferenceMetadata
import com.aashishgodambe.arcana.core.ai.model.InferenceResult
import com.aashishgodambe.arcana.core.ai.model.RoutingHint
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.last
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class BenchmarkHarnessTest {

    private val prompts = listOf(
        BenchmarkPrompt("a", "A", "prompt a"),
        BenchmarkPrompt("b", "B", "prompt b"),
    )

    // These tests exercise harness mechanics (iteration/cold/pacing/progress), not the engine roster — pin
    // them to a fixed two-engine set so adding engines (e.g. the Week-7 own-model) doesn't perturb counts.
    private val twoEngines = listOf(BenchmarkEngine.OnDevice, BenchmarkEngine.Cloud)

    @Test
    fun `sweep forces each engine's hint, runs sequentially, and groups per cell`() = runTest {
        val gemini = RecordingGeminiService()
        val harness = BenchmarkHarness(gemini)

        val samples = harness.run(prompts = prompts, engines = twoEngines, iterations = 3).last()

        // 2 engines × 2 prompts × 3 iterations
        assertEquals(12, samples.size)

        // Each engine ran forced onto its own hint — the on-device-vs-cloud comparison is real.
        assertEquals(
            List(6) { RoutingHint.OnlyOnDevice } + List(6) { RoutingHint.OnlyCloud },
            gemini.hints,
        )
        samples.forEach { s ->
            val expected =
                if (s.engine == BenchmarkEngine.Cloud) InferenceLocation.Cloud else InferenceLocation.OnDevice
            assertEquals(expected, s.metadata?.executedOn)
        }

        // Never more than one inference in flight — no BUSY-inducing fan-out.
        assertEquals(1, gemini.maxConcurrent)

        // Every (engine × prompt) cell holds exactly `iterations` samples.
        val cells = samples.groupBy { it.engine to it.promptId }
        assertEquals(4, cells.size)
        cells.values.forEach { assertEquals(3, it.size) }
    }

    @Test
    fun `cloud runs fewer iterations than on-device when cloudIterations is set`() = runTest {
        val gemini = RecordingGeminiService()
        val harness = BenchmarkHarness(gemini)

        val samples = harness.run(prompts = prompts, engines = twoEngines, iterations = 5, cloudIterations = 2).last()

        // 2 prompts × 5 on-device, 2 prompts × 2 cloud — cloud sips the scarce free-tier budget.
        assertEquals(10, samples.count { it.engine == BenchmarkEngine.OnDevice })
        assertEquals(4, samples.count { it.engine == BenchmarkEngine.Cloud })
        assertEquals(
            List(10) { RoutingHint.OnlyOnDevice } + List(4) { RoutingHint.OnlyCloud },
            gemini.hints,
        )
    }

    @Test
    fun `exactly one cold sample per engine, and it is that engine's first call`() = runTest {
        val gemini = RecordingGeminiService()
        val harness = BenchmarkHarness(gemini)

        val samples = harness.run(prompts = prompts, engines = twoEngines, iterations = 3).last()

        val cold = samples.filter { it.isCold }
        assertEquals(2, cold.size)
        assertEquals(setOf(BenchmarkEngine.OnDevice, BenchmarkEngine.Cloud), cold.map { it.engine }.toSet())
        // The cold sample is the engine's very first call: first prompt, iteration 0.
        cold.forEach {
            assertEquals(0, it.iteration)
            assertEquals(prompts.first().id, it.promptId)
        }
    }

    @Test
    fun `cold is once per process — a second run in the same harness is all warm`() = runTest {
        val gemini = RecordingGeminiService()
        val harness = BenchmarkHarness(gemini)

        harness.run(prompts = prompts, engines = twoEngines, iterations = 2).last()
        val second = harness.run(prompts = prompts, engines = twoEngines, iterations = 2).last()

        assertTrue(second.none { it.isCold })
    }

    @Test
    fun `an engine failure is recorded as an errored sample, not metadata`() = runTest {
        val gemini = RecordingGeminiService(failCloud = true)
        val harness = BenchmarkHarness(gemini)

        val samples = harness.run(prompts = prompts, engines = twoEngines, iterations = 2).last()

        val cloud = samples.filter { it.engine == BenchmarkEngine.Cloud }
        cloud.forEach {
            assertTrue(it.isError)
            assertEquals("forced cloud failure", it.error)
            assertEquals(null, it.metadata)
        }
        // On-device cell is unaffected.
        assertTrue(samples.filter { it.engine == BenchmarkEngine.OnDevice }.none { it.isError })
    }

    @Test
    fun `progress fires once per call with a running completed count`() = runTest {
        val gemini = RecordingGeminiService()
        val harness = BenchmarkHarness(gemini)
        val progress = mutableListOf<BenchmarkProgress>()

        harness.run(prompts = prompts, engines = twoEngines, iterations = 2, onProgress = { progress += it }).last()

        // 2 engines × 2 prompts × 2 iterations
        assertEquals(8, progress.size)
        assertEquals(List(8) { it }, progress.map { it.completed })
        progress.forEach { assertEquals(8, it.total) }
        assertFalse(progress.first().engine == BenchmarkEngine.Cloud) // engine-outer: on-device cells first
    }

    /**
     * Records the hint of every call and the peak concurrency, and returns metadata whose location matches
     * the forced hint — so a test can prove the harness forces each engine correctly and never overlaps calls.
     */
    private class RecordingGeminiService(private val failCloud: Boolean = false) : GeminiService {
        val hints = mutableListOf<RoutingHint>()
        var maxConcurrent = 0
            private set
        private var active = 0

        override fun generateText(prompt: String, routingHint: RoutingHint): Flow<InferenceResult> = flow {
            active++
            maxConcurrent = maxOf(maxConcurrent, active)
            hints += routingHint
            try {
                if (failCloud && routingHint == RoutingHint.OnlyCloud) {
                    emit(InferenceResult.Error(IllegalStateException("forced cloud failure"), fallbackAvailable = false))
                    return@flow
                }
                val location =
                    if (routingHint == RoutingHint.OnlyCloud) InferenceLocation.Cloud else InferenceLocation.OnDevice
                emit(InferenceResult.Streaming("hi"))
                emit(
                    InferenceResult.Success(
                        fullText = "hi",
                        metadata = InferenceMetadata(location, totalLatencyMs = 10, firstTokenLatencyMs = 5, outputTokenCount = 1),
                    ),
                )
            } finally {
                active--
            }
        }
    }
}
