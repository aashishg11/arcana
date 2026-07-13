package com.aashishgodambe.arcana.core.ai.pricing

/**
 * Pure pricing logic for the eBay Browse provider — building the search query and reducing a page of
 * active listings to a single median value. No android/HTTP/JSON types, so it's fully JVM-unit-tested;
 * the impure HTTP + JSON parse live in [EbayBrowseClient].
 */
object EbayPriceMath {

    /**
     * The Browse `q` for a Funko: brand + **distinguishing product line** + name + Pop number,
     * whitespace-collapsed. The line matters enormously: without it "Captain America 01" returns a mixed
     * bag of every Captain America pop (median lands on cheap unrelated ones, ~$35), while adding
     * "Die-Cast" isolates the actual pop (~$77). The Pop number is per-series (not unique) so it's only a
     * soft signal; the line is what narrows the market.
     */
    fun funkoQuery(name: String, popNumber: String?, series: List<String> = emptyList()): String =
        listOf("Funko", "Pop", distinguishingLine(series).orEmpty(), name, popNumber.orEmpty())
            .joinToString(" ") { it.trim() }
            .replace(Regex("\\s+"), " ")
            .trim()

    /**
     * The product line that actually narrows the eBay market — "Die-Cast", "Digital", etc. Plain
     * "Pop! Vinyl" is the default line and adds only noise, so it's omitted. Derived from the series list
     * (HobbyDB stores the line inside it).
     */
    private fun distinguishingLine(series: List<String>): String? {
        val joined = series.joinToString(" ").lowercase()
        return when {
            Regex("die[- ]?cast").containsMatchIn(joined) -> "Die-Cast"
            "digital" in joined -> "Digital"
            else -> null
        }
    }

    /**
     * Median price in cents across [listings], counting only the target [currency] and positive prices.
     * Median (not mean) because eBay pages carry outliers — mislisted lots, graded/signed premiums — that
     * would skew an average. Returns null when nothing priceable remains.
     */
    fun medianCents(listings: List<EbayListing>, currency: String = "USD"): Int? {
        val prices = listings
            .filter { it.currency.equals(currency, ignoreCase = true) && it.priceCents > 0 }
            .map { it.priceCents }
            .sorted()
        if (prices.isEmpty()) return null
        val mid = prices.size / 2
        return if (prices.size % 2 == 1) prices[mid] else (prices[mid - 1] + prices[mid]) / 2
    }
}
