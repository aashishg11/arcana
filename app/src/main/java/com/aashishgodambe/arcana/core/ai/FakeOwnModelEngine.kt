package com.aashishgodambe.arcana.core.ai

import com.aashishgodambe.arcana.core.ai.model.InferenceLocation
import com.aashishgodambe.arcana.core.ai.model.InferenceResult
import com.aashishgodambe.arcana.core.ai.model.RoutingHint
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow

/**
 * Device-free [OwnModelEngine] for tests and previews — the LiteRT/MediaPipe runtime can't run on the
 * JVM, so the picker, the delegating router, and any own-model UI are exercised through this instead.
 *
 * When [available] it streams like [FakeGeminiService] but tags the answer [InferenceLocation.OnDeviceOwnModel];
 * when unavailable it emits the same clean [InferenceResult.Error] the real service does for an absent model,
 * so presence-gating can be verified without a side-loaded file.
 */
class FakeOwnModelEngine(
    private val available: Boolean = true,
    private val cannedResponse: String = "Your Gemma says: Daenerys with Egg is your most valuable at $590.",
    private val delegate: GeminiService = FakeGeminiService(
        cannedResponse = cannedResponse,
        executedOn = InferenceLocation.OnDeviceOwnModel,
    ),
) : OwnModelEngine {

    override fun isModelAvailable(): Boolean = available

    override fun generateText(prompt: String, routingHint: RoutingHint): Flow<InferenceResult> =
        if (available) {
            delegate.generateText(prompt, routingHint)
        } else {
            flow {
                emit(
                    InferenceResult.Error(
                        LiteRtGeminiService.ModelUnavailable("/fake/models/gemma3-1b-it-int4.litertlm"),
                        fallbackAvailable = false,
                    ),
                )
            }
        }
}
