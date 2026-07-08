package com.aashishgodambe.arcana.core.ai

import com.aashishgodambe.arcana.core.ai.model.InferenceLocation
import com.aashishgodambe.arcana.core.ai.model.InferenceMetadata
import com.aashishgodambe.arcana.core.ai.model.InferenceResult
import com.aashishgodambe.arcana.core.ai.model.RoutingHint
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow

/**
 * Deterministic [GeminiService] for unit tests and Compose previews — no device, no network. Streams
 * [cannedResponse] word-by-word with configurable delays (skipped virtually under `runTest`), then a
 * [InferenceResult.Success] with synthetic benchmark metadata. Set [error] to emit an
 * [InferenceResult.Error] instead.
 */
class FakeGeminiService(
    private val cannedResponse: String = "Your most valuable item is Daenerys with Egg at $590.",
    private val executedOn: InferenceLocation = InferenceLocation.OnDevice,
    private val firstTokenDelayMs: Long = 40L,
    private val perWordDelayMs: Long = 15L,
    private val error: Throwable? = null,
) : GeminiService {

    override fun generateText(prompt: String, routingHint: RoutingHint): Flow<InferenceResult> = flow {
        error?.let {
            emit(InferenceResult.Error(it, fallbackAvailable = false))
            return@flow
        }
        delay(firstTokenDelayMs)
        val words = cannedResponse.split(" ")
        val sb = StringBuilder()
        words.forEachIndexed { index, word ->
            if (index > 0) sb.append(' ')
            sb.append(word)
            emit(InferenceResult.Streaming(sb.toString()))
            delay(perWordDelayMs)
        }
        emit(
            InferenceResult.Success(
                fullText = cannedResponse,
                metadata = InferenceMetadata(
                    executedOn = executedOn,
                    totalLatencyMs = firstTokenDelayMs + words.size * perWordDelayMs,
                    firstTokenLatencyMs = firstTokenDelayMs,
                    outputTokenCount = words.size,
                ),
            ),
        )
    }
}
