package com.aashishgodambe.arcana.core.ai

import app.cash.turbine.test
import com.aashishgodambe.arcana.core.ai.model.InferenceLocation
import com.aashishgodambe.arcana.core.ai.model.InferenceResult
import com.aashishgodambe.arcana.core.ai.model.RoutingHint
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Locks the own-model contract the picker and delegating router depend on: when the model is present it
 * streams and tags the answer [InferenceLocation.OnDeviceOwnModel] with honest metadata; when absent it
 * reports unavailable and fails cleanly (no crash) so the picker can grey the row.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class FakeOwnModelEngineTest {

    @Test
    fun `available engine streams then completes as own-model with honest metadata`() = runTest {
        val engine = FakeOwnModelEngine(available = true, cannedResponse = "Daenerys with Egg at \$590.")

        assertTrue(engine.isModelAvailable())

        engine.generateText("what's my most valuable?", RoutingHint.Auto).test {
            var sawStreaming = false
            var success: InferenceResult.Success? = null
            while (true) {
                when (val r = awaitItem()) {
                    is InferenceResult.Streaming -> sawStreaming = true
                    is InferenceResult.Success -> { success = r; break }
                    is InferenceResult.Error -> error("unexpected error: ${r.cause}")
                }
            }
            awaitComplete()

            assertTrue("expected at least one streaming chunk", sawStreaming)
            assertEquals("Daenerys with Egg at \$590.", success!!.fullText)
            assertEquals(InferenceLocation.OnDeviceOwnModel, success.metadata.executedOn)
            // token count is populated honestly by the fake (word count), not left null
            assertNotNull(success.metadata.outputTokenCount)
        }
    }

    @Test
    fun `unavailable engine reports not-available and emits a clean error`() = runTest {
        val engine = FakeOwnModelEngine(available = false)

        assertFalse(engine.isModelAvailable())

        engine.generateText("anything", RoutingHint.Auto).test {
            val error = awaitItem() as InferenceResult.Error
            assertTrue(error.cause is LiteRtGeminiService.ModelUnavailable)
            assertFalse(error.fallbackAvailable)
            awaitComplete()
        }
    }
}
