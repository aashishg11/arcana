package com.aashishgodambe.arcana.core.ai.summary

import com.aashishgodambe.arcana.core.ai.model.InferenceResult
import com.aashishgodambe.arcana.core.ai.summary.di.GeminiEngine
import com.aashishgodambe.arcana.core.ai.summary.di.MlKitEngine
import com.aashishgodambe.arcana.core.domain.model.WeeklyDeltas
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import javax.inject.Inject

/**
 * Tries the showcase ML Kit GenAI Summarization engine ([MlKitEngine] — the 8th `ai-samples` capability)
 * first; if it can't run — feature 2004 not yet provisioned, or any error — transparently falls back to the
 * Gemini Nano engine ([GeminiEngine]). Ships the new capability whenever it's available and degrades
 * gracefully to the other on-device engine otherwise.
 *
 * ML Kit failures surface up front (a status check), before any tokens stream, so the fallback swaps in
 * cleanly. Depends on the [CollectionSummarizer] seam for both engines (qualifier-injected), so the fallback
 * logic is unit-testable with fakes.
 */
class FallbackCollectionSummarizer @Inject constructor(
    @MlKitEngine private val primary: CollectionSummarizer,
    @GeminiEngine private val fallback: CollectionSummarizer,
) : CollectionSummarizer {

    override fun summarize(deltas: WeeklyDeltas): Flow<InferenceResult> = flow {
        var primaryFailed = false
        primary.summarize(deltas).collect { result ->
            if (result is InferenceResult.Error) primaryFailed = true else emit(result)
        }
        if (primaryFailed) {
            fallback.summarize(deltas).collect { emit(it) }
        }
    }
}
