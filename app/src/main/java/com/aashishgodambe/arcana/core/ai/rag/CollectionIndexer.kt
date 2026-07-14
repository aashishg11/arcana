package com.aashishgodambe.arcana.core.ai.rag

import com.aashishgodambe.arcana.core.data.repository.CollectibleRepository
import javax.inject.Inject

/**
 * Builds and maintains the RAG vector index: renders each collectible to its [CollectionDocument] text,
 * embeds it with [CollectionEmbedder], and persists it to the [CollectionVectorStore].
 *
 * **Incremental by document hash.** A one-time full embed of 504 items is a real cost (~355 ms each on the
 * Pixel), so the indexer skips any item whose document text hash already matches the stored one — a save
 * or re-import then only re-embeds what actually changed. When the embedder isn't side-loaded it's a clean
 * no-op ([Result.available] = false) and Ask falls back to lexical retrieval.
 */
class CollectionIndexer @Inject constructor(
    private val repository: CollectibleRepository,
    private val embedder: CollectionEmbedder,
    private val store: CollectionVectorStore,
) {
    data class Progress(val done: Int, val total: Int)

    data class Result(val embedded: Int, val skipped: Int, val failed: Int, val available: Boolean) {
        val total: Int get() = embedded + skipped + failed
    }

    suspend fun index(onProgress: (Progress) -> Unit = {}): Result {
        if (!embedder.isModelAvailable()) return Result(0, 0, 0, available = false)

        val items = repository.allCollectibles()
        val existing = store.indexedHashes()
        var embedded = 0
        var skipped = 0
        var failed = 0

        items.forEachIndexed { i, collectible ->
            val doc = CollectionDocument.of(collectible)
            val hash = doc.hashCode()
            when {
                existing[collectible.localId] == hash -> skipped++
                else -> {
                    val vector = embedder.embedDocument(doc)
                    if (vector != null) {
                        store.upsert(collectible.localId, vector, hash)
                        embedded++
                    } else {
                        failed++
                    }
                }
            }
            onProgress(Progress(i + 1, items.size))
        }
        return Result(embedded, skipped, failed, available = true)
    }
}
