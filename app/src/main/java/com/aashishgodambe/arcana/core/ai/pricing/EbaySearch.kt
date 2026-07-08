package com.aashishgodambe.arcana.core.ai.pricing

import com.aashishgodambe.arcana.core.domain.model.Collectible
import com.aashishgodambe.arcana.core.domain.model.FunkoPop
import java.net.URLEncoder

/**
 * Constructs an untracked eBay *search* URL from an item's identity — no eBay API, no campaign id, works
 * for every category. Backs both the mock listings' "View" links and the provider-independent
 * "Buy on eBay" affordance. A clean spot to append an EPN `campid` later if an attorney clears it.
 */
object EbaySearch {
    fun url(collectible: Collectible): String = url(searchQuery(collectible))

    fun url(query: String): String =
        "https://www.ebay.com/sch/i.html?_nkw=" + URLEncoder.encode(query, "UTF-8")

    /** Name plus the Funko Pop number when known — enough to land on the right search results. */
    fun searchQuery(collectible: Collectible): String = when (collectible) {
        is FunkoPop -> listOfNotNull("Funko Pop", collectible.name, collectible.popNumber?.let { "#$it" })
            .joinToString(" ")
    }
}
