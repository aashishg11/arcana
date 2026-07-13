package com.aashishgodambe.arcana.core.ai.cascade

import android.graphics.Bitmap
import com.aashishgodambe.arcana.core.ai.catalog.CatalogQuery

/**
 * The on-device LLM's (Gemini Nano Prompt API) read of a box. Gate A established that its field *labels*
 * are unreliable — it swaps character/franchise — so fusion treats these as **fallback + corroboration**,
 * never overriding the positional OCR [BoxLayout], which is the authoritative structuring. [rawText] is
 * Nano's full reply, kept as a weak description tiebreak.
 */
data class LlmBoxRead(
    val character: String? = null,
    val franchise: String? = null,
    val number: String? = null,
    val series: String? = null,
    val rawText: String? = null,
)

/**
 * Fuses the cascade's two independent reads of a box into one [CatalogQuery]: the deterministic OCR
 * [BoxLayout] (trusted for structure) and the [LlmBoxRead] (fills gaps + supplies a free-text tiebreak).
 * The Pop number is the strong key; because OCR and Nano fail differently (OCR misreads glyphs under
 * glare, Nano mislabels), each backstops the other — OCR's number is preferred, Nano's fills a gap.
 */
object CascadeHintFusion {

    fun toQuery(
        layout: BoxLayout,
        llm: LlmBoxRead? = null,
        upc: String? = null,
        image: Bitmap? = null,
    ): CatalogQuery =
        CatalogQuery(
            popNumber = layout.popNumber ?: llm?.number?.trim()?.ifBlank { null },
            franchise = layout.franchise ?: llm?.franchise?.trim()?.ifBlank { null },
            character = layout.character ?: llm?.character?.trim()?.ifBlank { null },
            series = layout.series ?: llm?.series?.trim()?.ifBlank { null },
            finish = layout.finish,
            upc = upc,
            descriptionHints = llm?.rawText?.trim()?.ifBlank { null },
            image = image,
        )
}
