package com.aashishgodambe.arcana.core.ai.rag

/**
 * The hybrid-retrieval router — the week's central design. Semantic vector search matches *meaning* but
 * structurally **cannot count**: top-k over 504 vectors can't answer "how many Marvel do I own?" (it would
 * return 5 of them, not 97). So questions are classified and dispatched: aggregate/filter/rank →
 * [StructuredRetriever] (a precise SQL answer); fuzzy/semantic → [SemanticRetriever] (vectors).
 *
 * Deliberately **rules-based and inspectable** — keyword and regex patterns, not an LLM call. A classifier
 * you can read, unit-test, and defend beats an opaque one, and it costs no inference. Order matters: the
 * most specific intents are checked first, semantic is the catch-all.
 */
object QueryRouter {

    sealed interface Route {
        /** "how many Marvel", "how much are my Star Wars worth" — count/value over a keyword subject ("" = whole collection). */
        data class Count(val subject: String) : Route

        /** "what did I add in 2023" — filter by acquisition year. */
        data class AddedInYear(val year: Int) : Route

        /** "which are NFT-redeemable" — the boolean catalog flag (not a name keyword). */
        data object NftRedeemable : Route

        /** "what's my most valuable item" — order by value. */
        data object MostValuable : Route

        /** "tell me more", "what are they worth" — a back-reference; reuse the current thread's grounding. */
        data object FollowUp : Route

        /** Everything else — fuzzy meaning search over the vector index. */
        data class Semantic(val query: String) : Route
    }

    fun classify(query: String): Route {
        val q = query.trim()
        val lower = q.lowercase()
        return when {
            isFollowUp(lower) -> Route.FollowUp
            MOST_VALUABLE.containsMatchIn(lower) -> Route.MostValuable
            NFT.containsMatchIn(lower) -> Route.NftRedeemable
            yearAdded(lower) != null -> Route.AddedInYear(yearAdded(lower)!!)
            countSubject(lower) != null -> Route.Count(countSubject(lower)!!)
            else -> Route.Semantic(q)
        }
    }

    /** A back-reference to the current thread (pronoun or a short "more" phrase) with no fresh subject. */
    private fun isFollowUp(lower: String): Boolean {
        val words = lower.split(Regex("\\s+")).filter { it.isNotBlank() }
        if (words.size > 6) return false
        if (FOLLOWUP_PHRASE.matches(lower)) return true
        return BACK_REFERENCE.containsMatchIn(lower)
    }

    /** A 4-digit year, but only when the question is temporal (added/acquired/from…), not a name like "Blade Runner 2049". */
    private fun yearAdded(lower: String): Int? {
        if (!TEMPORAL.containsMatchIn(lower)) return null
        return YEAR.find(lower)?.value?.toIntOrNull()
    }

    /** The keyword subject of a count question, cleaned of noise ("do I own", "pops"), or null if not a count. */
    private fun countSubject(lower: String): String? {
        val trigger = COUNT_TRIGGER.find(lower) ?: return null
        return lower.substring(trigger.range.last + 1)
            .replace(COUNT_NOISE, " ")
            .replace(Regex("[?.!,]"), " ")
            .replace(Regex("\\s+"), " ")
            .trim()
    }

    private val MOST_VALUABLE =
        Regex("most valuable|worth (the )?most|most expensive|priciest|highest[- ]?(value|price|worth)|top (value|worth)")
    private val NFT = Regex("\\bnft\\b|redeemable|redemption")
    private val YEAR = Regex("\\b(20\\d\\d)\\b")
    private val TEMPORAL = Regex("\\b(added|acquired|got|bought|import(ed)?|from|since|during|in|recently|new|year)\\b")
    private val COUNT_TRIGGER = Regex("^(how many|number of|count of|count my|count the|how much (are|is|do))")
    private val COUNT_NOISE =
        Regex("\\b(do i own|do i have|are there|is there|in my collection|worth|left|own|have|my|the|pops?|funkos?|figures?)\\b")
    private val FOLLOWUP_PHRASE = Regex("(tell me more|^more$|^why\\??$|how so|go on|and\\?|what else|any others?)")
    private val BACK_REFERENCE = Regex("\\b(them|those|these|they)\\b")
}
