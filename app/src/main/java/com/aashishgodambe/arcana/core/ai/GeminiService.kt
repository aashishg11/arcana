package com.aashishgodambe.arcana.core.ai

import com.aashishgodambe.arcana.core.ai.model.InferenceResult
import com.aashishgodambe.arcana.core.ai.model.RoutingHint
import kotlinx.coroutines.flow.Flow

/**
 * The single boundary every feature crosses to reach an LLM. Callers depend on this interface and
 * the [InferenceResult] sealed type — never on Firebase, ML Kit, or LiteRT types directly. Swapping
 * the backend (Week 7 ExecuTorch/Gemma) is a DI binding change, not a call-site rewrite.
 */
interface GeminiService {
    fun generateText(
        prompt: String,
        routingHint: RoutingHint = RoutingHint.Auto,
    ): Flow<InferenceResult>
}
