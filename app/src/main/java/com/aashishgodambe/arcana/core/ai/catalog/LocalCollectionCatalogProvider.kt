package com.aashishgodambe.arcana.core.ai.catalog

import com.aashishgodambe.arcana.core.data.repository.CollectibleRepository
import com.aashishgodambe.arcana.core.domain.model.FunkoPop
import javax.inject.Inject

/**
 * First provider in the chain: matches the query against the user's own collection. Free, offline,
 * instant, privacy-perfect — and for a collector re-scanning a shelf it's the *most likely* hit, which is
 * what makes the "you already own this" callout work.
 *
 * Matching is anchored on the Pop number (the strong key) but Pop numbers restart per series, so a bare
 * number match is only medium confidence; franchise/character/series agreement raises it, and — crucially
 * — when the query *has* franchise/character context that the same-numbered item contradicts, the match
 * is rejected as a number coincidence so the cascade escalates rather than mis-identifying. (E.g. Popeye
 * #32 must not resolve to the owned Popeye #30, and a #52 in another franchise must not match Avatar #52.)
 */
class LocalCollectionCatalogProvider @Inject constructor(
    private val repository: CollectibleRepository,
) : CatalogProvider {

    override val sourceName = "Your collection"

    override suspend fun lookup(query: CatalogQuery): CatalogEntry? {
        val number = query.popNumber?.takeIf { it.isNotBlank() } ?: return null
        val sameNumber = repository.allCollectibles()
            .filterIsInstance<FunkoPop>()
            .filter { it.popNumber == number }
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
        val seriesMatch = !q.series.isNullOrBlank() && pop.series.any { it.contains(q.series, ignoreCase = true) }

        val hasContext = !q.franchise.isNullOrBlank() || !q.character.isNullOrBlank()
        val corroborated = franchiseMatch || characterMatch || seriesMatch
        // Same number but the provided context contradicts it → a number coincidence, not a match.
        if (hasContext && !corroborated) return NUMBER_COINCIDENCE

        var score = NUMBER_ONLY
        if (franchiseMatch) score += FRANCHISE_BONUS
        if (characterMatch) score += CHARACTER_BONUS
        if (seriesMatch) score += SERIES_BONUS
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
        const val NUMBER_ONLY = 0.6f          // exact number, no corroborating context
        const val FRANCHISE_BONUS = 0.25f
        const val CHARACTER_BONUS = 0.2f
        const val SERIES_BONUS = 0.1f
        const val NUMBER_COINCIDENCE = 0.4f   // number matches but context contradicts → below threshold
        const val MIN_CONFIDENCE = 0.6f       // number-only qualifies; the chain's threshold decides escalation
    }
}
