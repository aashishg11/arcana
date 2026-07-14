package com.aashishgodambe.arcana.core.ai.rag

import com.aashishgodambe.arcana.core.ai.rag.QueryRouter.Route
import com.aashishgodambe.arcana.core.domain.model.Collectible
import com.aashishgodambe.arcana.core.domain.model.currentValueCents
import javax.inject.Inject

/**
 * Answers aggregate/filter/rank questions with **precise SQL**, not a top-k guess — the half of hybrid
 * retrieval that semantic search structurally can't do. For a count it computes the *full* matching set and
 * hands the LLM an authoritative [Grounding.facts] line ("You own 97 Pops matching \"Marvel\"…"), so the
 * model states the real number instead of counting rows it only partly sees.
 *
 * Counts follow the HobbyDB convention already used across the app: **entries are unique** (the item
 * count), while **value sums duplicate copies** (Σ value×quantity).
 */
class StructuredRetriever @Inject constructor(
    private val repository: com.aashishgodambe.arcana.core.data.repository.CollectibleRepository,
) {
    suspend fun retrieve(route: Route): Grounding = when (route) {
        is Route.Count -> {
            val base = if (route.subject.isBlank()) repository.allCollectibles() else repository.matching(route.subject)
            grounding(base, if (route.subject.isBlank()) "in your collection" else "matching \"${route.subject}\"")
        }
        is Route.AddedInYear -> grounding(repository.addedInYear(route.year), "added in ${route.year}")
        Route.NftRedeemable -> grounding(repository.nftRedeemable(), "that are NFT-redeemable")
        // Ranking needs no count fact — the top items plus their values ground the answer directly.
        Route.MostValuable -> Grounding(repository.getMostValuable(TOP), strategy = RetrievalStrategy.Structured)
        else -> error("$route is not a structured route")
    }

    private fun grounding(items: List<Collectible>, phrase: String): Grounding {
        val entries = items.size
        val copies = items.sumOf { it.quantity }
        val valueCents = items.sumOf { it.currentValueCents * it.quantity }
        val fact = buildString {
            append("You own ").append(entries).append(if (entries == 1) " Pop " else " Pops ").append(phrase)
            if (copies > entries) append(" ($copies incl. duplicates)")
            if (entries > 0) append(", total value ").append(usd(valueCents))
            append(".")
        }
        return Grounding(items = items.take(SAMPLE), facts = listOf(fact), strategy = RetrievalStrategy.Structured)
    }

    private fun usd(cents: Int): String = "$" + "%,d".format(cents / 100)

    private companion object {
        const val SAMPLE = 8   // example items handed to the LLM alongside the authoritative count
        const val TOP = 3      // most-valuable grounding size
    }
}
