package com.aashishgodambe.arcana.core.ai.writing

import com.aashishgodambe.arcana.core.ai.model.InferenceResult
import com.aashishgodambe.arcana.core.domain.model.FunkoPop
import kotlinx.coroutines.flow.Flow

/**
 * Drafts sale-listing copy for a collectible on-device (`genai-writing-assistance`). Callers depend on this
 * seam and the [InferenceResult] stream — never on ML Kit directly — so the same UI renders it as the Ask
 * and summary surfaces do. A fake backs tests/previews.
 */
interface ListingWriter {
    fun draft(item: FunkoPop): Flow<InferenceResult>
}
