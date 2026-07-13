package com.aashishgodambe.arcana.core.ai.cascade

import com.aashishgodambe.arcana.core.ai.catalog.CatalogEntry

/**
 * Box-wins reconciliation of a settled identity against the box's positional read. The physical box is
 * authoritative over the catalog/collection record for what's printed on it — so a re-scan of a Digital
 * pop shows **"Pop! Digital"** even when the stored (imported) record still says "Pop! Vinyl". This is the
 * same box-over-retail rule the cascade already applies to conflicting metadata, now applied to the
 * displayed identity so a re-scan self-corrects stale data.
 *
 * Pure (no android/ML Kit types) → JVM-tested. Currently reconciles the **series** line (the top-left
 * "Pop! …" bubble); finish/edition can follow the same pattern.
 */
object CascadeReconciler {

    fun reconcile(entry: CatalogEntry, layout: BoxLayout): CatalogEntry {
        val boxSeries = layout.series ?: return entry
        // Already authoritative — nothing to do.
        if (entry.series.firstOrNull()?.equals(boxSeries, ignoreCase = true) == true) return entry
        // Box-wins: the box's series becomes the primary; keep the catalog's others behind it.
        val series = listOf(boxSeries) + entry.series.filterNot { it.equals(boxSeries, ignoreCase = true) }
        return entry.copy(series = series)
    }
}
