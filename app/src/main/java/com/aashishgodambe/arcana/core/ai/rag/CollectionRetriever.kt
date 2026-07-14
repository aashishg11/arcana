package com.aashishgodambe.arcana.core.ai.rag

import com.aashishgodambe.arcana.core.domain.model.Collectible

/**
 * The grounding seam Ask crosses to fetch context for a question. The production impl
 * ([HybridCollectionRetriever]) routes structured questions to SQL and semantic ones to vectors; a fake
 * backs ViewModel tests. Introducing this interface is what keeps `AskViewModel` a thin renderer — it asks
 * for grounding and builds a prompt, unaware of *how* the grounding was found.
 */
interface CollectionRetriever {
    suspend fun retrieve(query: String): Grounding
}

/**
 * What retrieval produced for one question:
 * - [items] — collectibles to show as chips and ground the answer.
 * - [facts] — **authoritative** computed statements (counts, totals) the model must state exactly. This is
 *   how structured retrieval fixes the counting problem: the count is computed in SQL and handed to the LLM
 *   as fact, rather than asking it to count rows it can't see all of.
 * - [strategy] — which path answered, surfaced for the inspectable "how was this answered?" affordance.
 */
data class Grounding(
    val items: List<Collectible>,
    val facts: List<String> = emptyList(),
    val strategy: RetrievalStrategy,
)

enum class RetrievalStrategy { Structured, Semantic, Lexical, FollowUp }
