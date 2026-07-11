package com.aashishgodambe.arcana.core.ai

import com.aashishgodambe.arcana.core.ai.model.AskEngine
import com.aashishgodambe.arcana.core.ai.model.InferenceLocation
import com.aashishgodambe.arcana.core.ai.model.InferenceMetadata
import com.aashishgodambe.arcana.core.ai.model.InferenceResult
import com.aashishgodambe.arcana.core.ai.model.RoutingHint
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * The router is the whole "swappable backends behind one interface" story made live, so its truth table
 * matters: the [RoutingHint.Auto] path must honor the picker, and explicit hints must pin their engine
 * regardless of the picker (that's how the benchmark forces each column).
 */
class DelegatingGeminiServiceTest {

    /** Records the hint it was invoked with so a test can assert *which lane* the router chose. */
    private class Recording(private val where: InferenceLocation) : OwnModelEngine {
        var lastHint: RoutingHint? = null
        var available = true
        override fun isModelAvailable() = available
        override fun generateText(prompt: String, routingHint: RoutingHint): Flow<InferenceResult> = flow {
            lastHint = routingHint
            emit(InferenceResult.Success("ok", InferenceMetadata(where, 1, 1, 1)))
        }
    }

    private suspend fun drive(service: DelegatingGeminiService, hint: RoutingHint) {
        service.generateText("q", hint).collect { /* trigger the cold flow */ }
    }

    @Test
    fun `Auto follows the picker`() = runTest {
        val hybrid = Recording(InferenceLocation.OnDevice)
        val own = Recording(InferenceLocation.OnDeviceOwnModel)
        var selection = AskEngine.Nano
        val router = DelegatingGeminiService(hybrid, own) { selection }

        drive(router, RoutingHint.Auto)
        assertEquals(RoutingHint.PreferOnDevice, hybrid.lastHint) // Nano → hybrid prefer-on-device
        assertNull(own.lastHint)

        selection = AskEngine.OwnModel
        drive(router, RoutingHint.Auto)
        assertEquals(RoutingHint.OnlyOwnModel, own.lastHint) // Your Gemma → own model

        selection = AskEngine.Cloud
        drive(router, RoutingHint.Auto)
        assertEquals(RoutingHint.OnlyCloud, hybrid.lastHint) // Cloud → hybrid cloud
    }

    @Test
    fun `own-model selected but unavailable falls back to Nano`() = runTest {
        val hybrid = Recording(InferenceLocation.OnDevice)
        val own = Recording(InferenceLocation.OnDeviceOwnModel).apply { available = false }
        val router = DelegatingGeminiService(hybrid, own) { AskEngine.OwnModel }

        drive(router, RoutingHint.Auto)

        assertEquals(RoutingHint.PreferOnDevice, hybrid.lastHint)
        assertNull(own.lastHint) // never asked to generate
    }

    @Test
    fun `explicit hints pin their engine regardless of the picker`() = runTest {
        val hybrid = Recording(InferenceLocation.OnDevice)
        val own = Recording(InferenceLocation.OnDeviceOwnModel)
        // Picker says Nano, but explicit hints must override it — this is how the benchmark forces columns.
        val router = DelegatingGeminiService(hybrid, own) { AskEngine.Nano }

        drive(router, RoutingHint.OnlyOwnModel)
        assertEquals(RoutingHint.OnlyOwnModel, own.lastHint)

        drive(router, RoutingHint.OnlyCloud)
        assertEquals(RoutingHint.OnlyCloud, hybrid.lastHint)

        drive(router, RoutingHint.OnlyOnDevice)
        assertEquals(RoutingHint.OnlyOnDevice, hybrid.lastHint)
    }
}
