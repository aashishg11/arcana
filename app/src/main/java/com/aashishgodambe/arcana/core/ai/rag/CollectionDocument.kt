package com.aashishgodambe.arcana.core.ai.rag

import com.aashishgodambe.arcana.core.domain.model.Collectible
import com.aashishgodambe.arcana.core.domain.model.FunkoPop

/**
 * Turns a collectible into the **text that gets embedded** — the choice the plan flags as mattering more
 * than the model ("document shape > model choice"). A bare name embeds far worse than a rich descriptor,
 * so the document folds in the fields a semantic query is likely to reach for: name, series/franchise, and
 * the NFT flag. Day-2 will A/B a couple of shapes; this is the first cut.
 *
 * Also owns EmbeddingGemma's **task prefixes**. The model is trained to embed retrieval queries and
 * documents under distinct prompts; using them is not optional for good retrieval. Kept pure (no
 * android/LiteRT types) so both the encoder and the JVM tests render identical text.
 */
object CollectionDocument {

    /** Prefix EmbeddingGemma expects for a retrieval *query* (the user's question). */
    fun queryPrompt(text: String): String = "task: search result | query: ${text.trim()}"

    /** Prefix EmbeddingGemma expects for a retrieval *document* (a collection item). */
    fun documentPrompt(text: String): String = "title: none | text: ${text.trim()}"

    /**
     * The descriptor embedded for a collectible. Exhaustive `when` over the sealed type so a new category
     * breaks compilation here rather than silently embedding an empty string.
     */
    fun of(collectible: Collectible): String = when (collectible) {
        is FunkoPop -> funkoText(collectible)
    }

    private fun funkoText(pop: FunkoPop): String {
        val series = pop.series.filter { it.isNotBlank() }.joinToString(", ")
        return buildString {
            append(pop.name)
            if (series.isNotBlank()) append(". Series: ").append(series)
            if (pop.isNftRedeemable) append(". NFT redeemable")
            pop.exclusiveTo?.takeIf { it.isNotBlank() }?.let { append(". Exclusive: ").append(it) }
        }
    }
}
