package com.aashishgodambe.arcana.core.ai.pricing

/**
 * Pure pricing logic for the eBay Browse provider — building the search query and reducing a page of
 * active listings to a single median value. No android/HTTP/JSON types, so it's fully JVM-unit-tested;
 * the impure HTTP + JSON parse live in [EbayBrowseClient].
 */
object EbayPriceMath {

    /**
     * The Browse `q` for a Funko: brand + line + name + Pop number, whitespace-collapsed. The Pop number
     * is a strong disambiguator (Freddy Funko recurs across releases), so it's included when known.
     */
    fun funkoQuery(name: String, popNumber: String?): String =
        listOf("Funko", "Pop", name, popNumber.orEmpty())
            .joinToString(" ") { it.trim() }
            .replace(Regex("\\s+"), " ")
            .trim()

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
