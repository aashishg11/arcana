package com.aashishgodambe.arcana.core.ai.rag

import com.aashishgodambe.arcana.core.ai.rag.QueryRouter.Route
import com.aashishgodambe.arcana.core.data.repository.CollectibleRepository
import javax.inject.Inject

/**
 * The production [CollectionRetriever]: classify with [QueryRouter], then dispatch. Aggregate/filter/rank →
 * [StructuredRetriever] (SQL); fuzzy → [SemanticRetriever] (vectors); a back-reference → [RetrievalStrategy
 * .FollowUp] so Ask reuses the thread's grounding.
 *
 * **Lexical is the honest fallback.** When the embedder isn't side-loaded (or the index is empty), a
 * semantic question degrades to keyword search rather than failing — Ask keeps working for anyone who
 * clones the repo without the gated model. Structured questions never need the model.
 */
class HybridCollectionRetriever @Inject constructor(
    private val structured: StructuredRetriever,
    private val semantic: SemanticRetriever,
    private val repository: CollectibleRepository,
) : CollectionRetriever {

    override suspend fun retrieve(query: String): Grounding =
        when (val route = QueryRouter.classify(query)) {
            is Route.Semantic -> semantic.retrieve(route.query) ?: lexicalFallback(route.query)
            Route.FollowUp -> Grounding(items = emptyList(), strategy = RetrievalStrategy.FollowUp)
            else -> structured.retrieve(route)
        }

    private suspend fun lexicalFallback(query: String): Grounding =
        Grounding(items = repository.search(query, LEXICAL_LIMIT), strategy = RetrievalStrategy.Lexical)

    private companion object {
        const val LEXICAL_LIMIT = 12   // the pre-RAG grounding size, kept for the fallback path
    }
}
