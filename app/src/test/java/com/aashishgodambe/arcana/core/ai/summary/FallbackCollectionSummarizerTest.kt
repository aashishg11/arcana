package com.aashishgodambe.arcana.core.ai.summary

import com.aashishgodambe.arcana.core.ai.model.InferenceLocation
import com.aashishgodambe.arcana.core.ai.model.InferenceMetadata
import com.aashishgodambe.arcana.core.ai.model.InferenceResult
import com.aashishgodambe.arcana.core.domain.model.WeeklyDeltas
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class FallbackCollectionSummarizerTest {

    private val deltas = WeeklyDeltas(totalDeltaCents = 24_000, lists = emptyList())

    @Test
    fun `uses ML Kit result and never touches the fallback when the primary succeeds`() = runTest {
        val primary = FakeSummarizer(listOf(success("ML Kit summary")))
        val fallback = FakeSummarizer(listOf(success("Gemini summary")))

        val results = FallbackCollectionSummarizer(primary, fallback).summarize(deltas).toList()

        assertEquals(listOf("ML Kit summary"), results.filterIsInstance<InferenceResult.Success>().map { it.fullText })
        assertTrue(primary.called)
        assertFalse("fallback must not run when primary succeeds", fallback.called)
    }

    @Test
    fun `falls back to Gemini when the ML Kit primary errors, swallowing the error`() = runTest {
        val primary = FakeSummarizer(listOf(InferenceResult.Error(IllegalStateException("feature 2004 not ready"), fallbackAvailable = true)))
        val fallback = FakeSummarizer(listOf(success("Gemini summary")))

        val results = FallbackCollectionSummarizer(primary, fallback).summarize(deltas).toList()

        // The primary's error is not surfaced; the fallback's result is emitted instead.
        assertTrue(results.none { it is InferenceResult.Error })
        assertEquals(listOf("Gemini summary"), results.filterIsInstance<InferenceResult.Success>().map { it.fullText })
        assertTrue(fallback.called)
    }

    private fun success(text: String) = InferenceResult.Success(
        fullText = text,
        metadata = InferenceMetadata(InferenceLocation.OnDevice, totalLatencyMs = 10, firstTokenLatencyMs = 5, outputTokenCount = null),
    )

    /** Records whether it was collected and replays [results] when summarize() is collected. */
    private class FakeSummarizer(private val results: List<InferenceResult>) : CollectionSummarizer {
        var called = false
            private set

        override fun summarize(deltas: WeeklyDeltas): Flow<InferenceResult> = flow {
            called = true
            results.forEach { emit(it) }
        }
    }
}
