package com.aashishgodambe.arcana.core.ai.catalog

/**
 * A source that can turn the cascade's fused hints into a collectible identity. One of the app's
 * pluggable seams: the cascade depends on this interface, and impls (local collection, UPC lookup, eBay,
 * cloud multimodal) are ordered into a [CatalogProviderChain].
 *
 * This evolves DESIGN.md's original UPC/name/image triad into a single structured [CatalogQuery]: the
 * cascade already produces structured fields (Pop number, franchise, character) from OCR + the on-device
 * LLM, so fusing them into one query is stronger than any single lookup axis, and the barcode's UPC is
 * just another query field. Returns null for "supported but no match".
 */
interface CatalogProvider {
    /** Human-readable source, surfaced as the identification badge ("Your collection", "eBay", …). */
    val sourceName: String

    suspend fun lookup(query: CatalogQuery): CatalogEntry?
}

/**
 * The fused identification hints the cascade feeds the chain. [popNumber] is the strong (near-unique
 * *within a series*) key; the rest corroborate and disambiguate, since Pop numbers restart per series so
 * a number alone is ambiguous. Any field may be null. Built by the cascade orchestrator from the OCR
 * [com.aashishgodambe.arcana.core.ai.cascade.BoxLayout] and the LLM's read; kept free of those types so
 * the catalog layer stays decoupled from the vision layer.
 */
data class CatalogQuery(
    val popNumber: String?,
    val franchise: String?,
    val character: String?,
    val series: String? = null,
    val finish: String? = null,
    val upc: String? = null,
    /** The on-device LLM's free-text description, for a weak tiebreak when structure is thin. */
    val descriptionHints: String? = null,
)

/**
 * A resolved identity from some [CatalogProvider]. Mirrors DESIGN.md's CatalogEntry, plus [matchedLocalId]
 * so the capture UI's "you already own this" callout and deep-link work when the resolver is the local
 * collection. [confidence] is 0..1; the chain uses it to decide short-circuit vs escalate.
 */
data class CatalogEntry(
    val sourceName: String,
    val externalId: String,
    val name: String,
    val franchise: String?,
    val series: List<String>,
    val number: String?,
    val exclusiveTo: String?,
    val imageUrl: String?,
    val confidence: Float,
    /** Non-null when this identity is an item the user already owns (from the local collection). */
    val matchedLocalId: Long? = null,
)
