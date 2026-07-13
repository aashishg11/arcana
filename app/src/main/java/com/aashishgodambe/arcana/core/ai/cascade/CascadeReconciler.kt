package com.aashishgodambe.arcana.core.ai.cascade

import com.aashishgodambe.arcana.core.ai.catalog.CatalogEntry

/**
 * Box-wins reconciliation of a settled identity against the box's positional read. The physical box is
 * authoritative over the catalog/collection record for what's printed on it — so a re-scan of a Digital
 * pop shows **"Pop! Digital"** even when the stored (imported) record still says "Pop! Vinyl". This is the
 * same box-over-retail rule the cascade already applies to conflicting metadata, now applied to the
 * displayed identity so a re-scan self-corrects stale data.
 *
 * Pure (no android/ML Kit types) → JVM-tested. Reconciles the **series** line (the top-left "Pop! …"
 * bubble) and the **franchise** banner; finish/edition can follow the same pattern.
 */
object CascadeReconciler {

    fun reconcile(entry: CatalogEntry, layout: BoxLayout): CatalogEntry {
        // Box-wins the series: the box's product-line read is authoritative over a stale catalog record.
        val boxSeries = layout.series
        val series = when {
            boxSeries == null -> entry.series
            entry.series.firstOrNull()?.equals(boxSeries, ignoreCase = true) == true -> entry.series
            else -> listOf(boxSeries) + entry.series.filterNot { it.equals(boxSeries, ignoreCase = true) }
        }
        // Box-wins the franchise — the key disambiguator for generic names ("Freddy Funko *as Popeye*"),
        // which the collection often stores blank. Needed downstream to price and search the right pop.
        val franchise = layout.franchise?.ifBlank { null } ?: entry.franchise
        return entry.copy(series = series, franchise = franchise)
    }
}
