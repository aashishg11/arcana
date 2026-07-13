package com.aashishgodambe.arcana.core.ai.catalog

import com.aashishgodambe.arcana.core.data.repository.CollectibleRepository
import com.aashishgodambe.arcana.core.domain.model.FunkoPop
import javax.inject.Inject

/**
 * First provider in the chain: matches the query against the user's own collection. Free, offline,
 * instant, privacy-perfect — and for a collector re-scanning a shelf it's the *most likely* hit, which is
 * what makes the "you already own this" callout work.
 *
 * Matching is anchored on the Pop number, but the number is NOT unique — it restarts per series — so a
 * bare number match is only medium confidence; **franchise/character** agreement raises it (series does
 * not corroborate: it's near-worthless for disambiguation), and — crucially — when the query *has*
 * franchise/character context that the same-numbered item contradicts, the match is rejected as a number
 * coincidence so the cascade escalates rather than mis-identifying. (E.g. Popeye #32 must not resolve to
 * the owned Popeye #30, and a misread #82 whose franchise is Popeye must not match the owned DC #82.)
 */
class LocalCollectionCatalogProvider @Inject constructor(
    private val repository: CollectibleRepository,
) : CatalogProvider {

    override val sourceName = "Your collection"

    override suspend fun lookup(query: CatalogQuery): CatalogEntry? {
        val owned = repository.allCollectibles().filterIsInstance<FunkoPop>()

        // UPC is a globally-unique key (the barcode path). Match it first, tolerating UPC-A vs EAN-13
        // leading-zero differences.
        query.upc?.takeIf { it.isNotBlank() }?.let { upc ->
            val target = upc.trimStart('0')
            owned.firstOrNull { it.upc.trimStart('0') == target }
                ?.let { return it.toEntry(sourceName, UPC_CONFIDENCE) }
        }

        val number = query.popNumber?.takeIf { it.isNotBlank() } ?: return null
        val sameNumber = owned.filter { it.popNumber == number }
        if (sameNumber.isEmpty()) return null

        val (pop, confidence) = sameNumber
            .map { it to score(it, query) }
            .maxByOrNull { it.second }
            ?: return null

        return if (confidence < MIN_CONFIDENCE) null else pop.toEntry(sourceName, confidence)
    }

    private fun score(pop: FunkoPop, q: CatalogQuery): Float {
        val haystack = (pop.name + " " + pop.series.joinToString(" ")).lowercase()
        val franchiseMatch = !q.franchise.isNullOrBlank() && haystack.contains(q.franchise.lowercase())
        val characterMatch = !q.character.isNullOrBlank() && tokenOverlap(pop.name, q.character)

        val hasContext = !q.franchise.isNullOrBlank() || !q.character.isNullOrBlank()
        // Only franchise/character corroborate. Series is near-worthless for disambiguation — Pop numbers
        // restart per series and half the collection is "Pop! Digital" — so it must NOT rescue a
        // same-number match when the franchise/character context contradicts (the #82→Parallax mis-ID).
        val corroborated = franchiseMatch || characterMatch
        if (hasContext && !corroborated) return NUMBER_COINCIDENCE

        var score = NUMBER_ONLY
        if (franchiseMatch) score += FRANCHISE_BONUS
        if (characterMatch) score += CHARACTER_BONUS
        return score.coerceAtMost(1f)
    }

    /** True if the character query shares a meaningful token with the item name (order-insensitive). */
    private fun tokenOverlap(name: String, character: String): Boolean {
        val nameTokens = tokenize(name)
        return tokenize(character).any { it.length >= 3 && it in nameTokens }
    }

    private fun tokenize(text: String): Set<String> =
        text.lowercase().split(Regex("[^a-z0-9]+")).filter { it.isNotBlank() }.toSet()

    private fun FunkoPop.toEntry(source: String, confidence: Float) = CatalogEntry(
        sourceName = source,
        externalId = "local:$localId",
        name = name,
        franchise = null,                 // the collection carries franchise inside name/series, not a field
        series = series,
        number = popNumber,
        exclusiveTo = exclusiveTo,
        imageUrl = imageUrl,
        confidence = confidence,
        matchedLocalId = localId,
    )

    private companion object {
        const val UPC_CONFIDENCE = 0.95f      // UPC is globally unique — a strong match
        const val NUMBER_ONLY = 0.6f          // exact number, no corroborating context
        const val FRANCHISE_BONUS = 0.25f
        const val CHARACTER_BONUS = 0.2f
        const val NUMBER_COINCIDENCE = 0.4f   // number matches but context contradicts → below threshold
        const val MIN_CONFIDENCE = 0.6f       // number-only qualifies; the chain's threshold decides escalation
    }
}
