package com.aashishgodambe.arcana.core.ai.summary

import com.aashishgodambe.arcana.core.ai.model.InferenceResult
import com.aashishgodambe.arcana.core.domain.model.WeeklyDeltas
import kotlinx.coroutines.flow.Flow

/**
 * Turns the week's own-collection [WeeklyDeltas] into a plain-language "what moved" summary, on-device.
 * One of the AI seams: features depend on this, never on the engine. v1 binds a [GeminiService]-backed
 * impl (proven on-device from Week 2); an ML Kit GenAI Summarization impl can slot in behind the same
 * interface as a Hilt binding swap once a device spike confirms it takes delta-derived input.
 *
 * Emits [InferenceResult] so the card streams token-by-token and shows the on-device/cloud badge, exactly
 * like "Ask Arcana".
 */
interface CollectionSummarizer {
    fun summarize(deltas: WeeklyDeltas): Flow<InferenceResult>
}
