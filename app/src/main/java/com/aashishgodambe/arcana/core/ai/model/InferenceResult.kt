package com.aashishgodambe.arcana.core.ai.model

/**
 * One inference call emits a stream of these: zero or more [Streaming] events with the growing text,
 * terminated by exactly one [Success] (carrying benchmark [InferenceMetadata]) or one [Error].
 */
sealed interface InferenceResult {
    /** The accumulated partial text so far (full prefix, not just the latest token). */
    data class Streaming(val partialText: String) : InferenceResult

    data class Success(val fullText: String, val metadata: InferenceMetadata) : InferenceResult

    /** [fallbackAvailable] is true when a cloud retry is possible but wasn't taken (e.g. offline-only). */
    data class Error(val cause: Throwable, val fallbackAvailable: Boolean) : InferenceResult
}
