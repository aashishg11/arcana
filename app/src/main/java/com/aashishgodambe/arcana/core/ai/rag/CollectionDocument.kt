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
     * The descriptor embedded for a collectible — the shape chosen by the Day-2 on-device benchmark
     * ([CollectionRagE2eTest]). Exhaustive `when` over the sealed type so a new category breaks compilation
     * here rather than silently embedding an empty string.
     */
    fun of(collectible: Collectible): String = Shape.Natural.render(collectible)

    /**
     * Candidate document shapes, compared on retrieval quality (the plan's "document shape > model
     * choice"). The measured finding: labelled scaffolding ("Series: …") *dilutes* the pooled embedding
     * when the name already implies the franchise, so a **natural** phrasing wins. Kept as an enum so the
     * comparison is reproducible and the winner is a one-line change.
     */
    enum class Shape {
        /** Just the name — the baseline. Strong when the character name already carries the franchise. */
        BareName {
            override fun render(c: Collectible): String = c.name
        },

        /** Name + labelled series/flags — reads naturally to a human, but the labels add token noise. */
        Labelled {
            override fun render(c: Collectible): String = buildString {
                append(c.name)
                seriesOf(c)?.let { append(". Series: ").append(it) }
                if (c is FunkoPop && c.isNftRedeemable) append(". NFT redeemable")
            }
        },

        /** Name woven into a natural sentence with its franchise — no label scaffolding. */
        Natural {
            override fun render(c: Collectible): String = buildString {
                append(c.name)
                seriesOf(c)?.let { append(" from ").append(it) }
                if (c is FunkoPop && c.isNftRedeemable) append(", an NFT-redeemable Funko Pop")
                else append(", a Funko Pop")
            }
        };

        abstract fun render(c: Collectible): String

        protected fun seriesOf(c: Collectible): String? =
            c.series.filter { it.isNotBlank() }.joinToString(", ").takeIf { it.isNotBlank() }
    }
}
