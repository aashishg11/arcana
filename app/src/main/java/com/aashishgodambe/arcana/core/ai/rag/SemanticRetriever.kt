package com.aashishgodambe.arcana.core.ai.rag

import com.aashishgodambe.arcana.core.data.repository.CollectibleRepository
import javax.inject.Inject

/**
 * Answers fuzzy/meaning questions by vector search: embed the query, cosine top-k over the index, resolve
 * ids back to collectibles (in *similarity* order). Returns null when the embedder isn't side-loaded or the
 * index is empty, so the caller can fall back to lexical retrieval.
 */
class SemanticRetriever @Inject constructor(
    private val embedder: CollectionEmbedder,
    private val store: CollectionVectorStore,
    private val repository: CollectibleRepository,
) {
    suspend fun retrieve(query: String, k: Int = SEMANTIC_K): Grounding? {
        val queryVector = embedder.embedQuery(query) ?: return null
        val ranked = store.topK(queryVector, k)
        if (ranked.isEmpty()) return null
        val byId = repository.allCollectibles().associateBy { it.localId }
        val items = ranked.mapNotNull { byId[it.id] }   // preserve similarity order, not value order
        if (items.isEmpty()) return null
        return Grounding(items = items, strategy = RetrievalStrategy.Semantic)
    }

    private companion object {
        const val SEMANTIC_K = 8
    }
}
