package com.aashishgodambe.arcana.core.data.worker

import android.content.Context
import android.util.Log
import androidx.hilt.work.HiltWorker
import androidx.work.CoroutineWorker
import androidx.work.WorkerParameters
import com.aashishgodambe.arcana.core.ai.rag.CollectionIndexer
import dagger.assisted.Assisted
import dagger.assisted.AssistedInject

/**
 * Builds/refreshes the RAG vector index in the background. Unlike Nano (AICore, foreground-only), the
 * EmbeddingGemma interpreter runs in-process on the CPU, so it's safe in a plain [CoroutineWorker] — no
 * foreground service needed (verified on device). The work is incremental (the indexer skips items whose
 * document text is unchanged), so re-runs after a capture/import are cheap, and a cancelled first-time
 * build resumes where it left off. When the model isn't side-loaded the indexer no-ops and Ask stays on
 * lexical retrieval — still a successful run, not a failure.
 */
@HiltWorker
class EmbeddingIndexWorker @AssistedInject constructor(
    @Assisted appContext: Context,
    @Assisted params: WorkerParameters,
    private val indexer: CollectionIndexer,
) : CoroutineWorker(appContext, params) {

    override suspend fun doWork(): Result = try {
        val result = indexer.index()
        Log.i(TAG, "index: embedded=${result.embedded} skipped=${result.skipped} failed=${result.failed} available=${result.available}")
        Result.success()
    } catch (e: Exception) {
        Log.w(TAG, "index failed", e)
        Result.retry()
    }

    companion object {
        const val NAME = "embedding-index"
        const val TAG = "EmbeddingIndex"
    }
}
