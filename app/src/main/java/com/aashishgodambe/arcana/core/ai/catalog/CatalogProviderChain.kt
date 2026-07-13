package com.aashishgodambe.arcana.core.ai.catalog

import android.util.Log

/**
 * Runs [CatalogProvider]s in configured order and **short-circuits on the first confident hit** — so a
 * local-collection match costs no network, and cloud escalation only happens when cheaper providers
 * can't identify the item confidently. Order is a DI decision (local → UPC → eBay → cloud), not hardcoded.
 *
 * Returns the first entry at or above [confidenceThreshold]; failing that, the highest-confidence entry
 * any provider produced (which the capture UI surfaces as a low-confidence result with a "scan barcode?"
 * nudge); or null if nothing matched at all. A provider that throws is logged and skipped, never aborting
 * the chain.
 */
class CatalogProviderChain(
    private val providers: List<CatalogProvider>,
) {
    suspend fun identify(
        query: CatalogQuery,
        confidenceThreshold: Float = DEFAULT_THRESHOLD,
    ): CatalogEntry? {
        var best: CatalogEntry? = null
        for (provider in providers) {
            val entry = runCatching { provider.lookup(query) }
                .onFailure { Log.w(TAG, "catalog provider '${provider.sourceName}' failed; skipping", it) }
                .getOrNull() ?: continue
            if (entry.confidence >= confidenceThreshold) return entry
            if (best == null || entry.confidence > best.confidence) best = entry
        }
        return best
    }

    private companion object {
        const val TAG = "CatalogChain"
        const val DEFAULT_THRESHOLD = 0.7f
    }
}
