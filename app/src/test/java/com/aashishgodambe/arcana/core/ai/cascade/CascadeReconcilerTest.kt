package com.aashishgodambe.arcana.core.ai.cascade

import com.aashishgodambe.arcana.core.ai.catalog.CatalogEntry
import org.junit.Assert.assertEquals
import org.junit.Test

/** JVM tests for [CascadeReconciler] — box-over-retail on the series line. */
class CascadeReconcilerTest {

    private fun entry(series: List<String>) = CatalogEntry(
        sourceName = "Your collection", externalId = "local:1", name = "Freddy Funko",
        franchise = null, series = series, number = "32", exclusiveTo = null, imageUrl = null,
        confidence = 0.9f,
    )

    private fun layout(series: String?) = BoxLayout(
        franchise = null, series = series, popNumber = "32", character = null, finish = null,
        rarityOrExclusive = null, editionSize = null,
    )

    @Test
    fun `box series wins over a stale catalog record`() {
        val r = CascadeReconciler.reconcile(entry(listOf("Pop! Vinyl")), layout("Pop! Digital"))
        assertEquals("Pop! Digital", r.series.first())
        assertEquals(listOf("Pop! Digital", "Pop! Vinyl"), r.series)  // catalog value kept behind it
    }

    @Test
    fun `catalog series stands when the box read no series`() {
        val r = CascadeReconciler.reconcile(entry(listOf("Pop! Vinyl")), layout(null))
        assertEquals(listOf("Pop! Vinyl"), r.series)
    }

    @Test
    fun `no duplication when the box series is already primary`() {
        val r = CascadeReconciler.reconcile(entry(listOf("Pop! Digital")), layout("Pop! Digital"))
        assertEquals(listOf("Pop! Digital"), r.series)
    }
}
