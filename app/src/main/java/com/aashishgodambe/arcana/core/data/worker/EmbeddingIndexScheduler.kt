package com.aashishgodambe.arcana.core.data.worker

import android.content.Context
import androidx.work.ExistingWorkPolicy
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkManager
import dagger.hilt.android.qualifiers.ApplicationContext
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Kicks off the RAG index [EmbeddingIndexWorker] — the single place that owns the request. Called on app
 * launch (build/refresh on first run) and after a capture-save or import so the index stays current.
 *
 * `REPLACE` so a fresh run always starts *after* the DB write that triggered it (a `KEEP`'d run may have
 * already read the collection before the new row landed and would miss it). Cheap regardless: the indexer
 * is incremental, so a replaced run re-does only what changed, and a first-time build resumes via the
 * per-item document hash. No constraints — it's local CPU work, no network.
 */
@Singleton
class EmbeddingIndexScheduler @Inject constructor(
    @ApplicationContext private val context: Context,
) {
    fun enqueue() {
        WorkManager.getInstance(context).enqueueUniqueWork(
            EmbeddingIndexWorker.NAME,
            ExistingWorkPolicy.REPLACE,
            OneTimeWorkRequestBuilder<EmbeddingIndexWorker>().build(),
        )
    }
}
