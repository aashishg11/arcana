package com.aashishgodambe.arcana.feature.capture

import com.aashishgodambe.arcana.core.ai.cascade.BoxLayout
import com.aashishgodambe.arcana.core.ai.cascade.CascadeResult
import com.aashishgodambe.arcana.core.ai.cascade.CascadeState
import com.aashishgodambe.arcana.core.ai.cascade.CascadeTelemetry
import com.aashishgodambe.arcana.core.ai.catalog.CatalogEntry
import com.aashishgodambe.arcana.core.data.repository.FakeCollectibleRepository
import com.aashishgodambe.arcana.core.domain.model.FunkoPop
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Device-free tests for the capture Review's pure logic: [reduceCapture] (the cascade-beat → UI-state
 * reduction, exercised over a fake `CascadeState` flow incl. the failure case) and [capturedItem] (the
 * save mapping), plus a save-flow round-trip through [FakeCollectibleRepository]. No Bitmap, ML, or network.
 */
class CaptureReviewLogicTest {

    private fun layout(number: String?) = BoxLayout(
        franchise = "Popeye", series = "Pop! Digital", popNumber = number, character = "Freddy Funko",
        finish = null, rarityOrExclusive = null, editionSize = null,
    )

    private fun entry(
        name: String = "Freddy Funko",
        number: String? = "32",
        exclusiveTo: String? = null,
        matchedLocalId: Long? = null,
        series: List<String> = listOf("Pop! Digital"),
    ) = CatalogEntry(
        sourceName = "Your collection", externalId = "cap:1", name = name, franchise = "Popeye",
        series = series, number = number, exclusiveTo = exclusiveTo, imageUrl = null, confidence = 0.9f,
        matchedLocalId = matchedLocalId,
    )

    private fun settled(entry: CatalogEntry?, confident: Boolean, owned: Boolean) =
        CascadeState.Settled(
            CascadeResult(entry, confident, owned, CascadeTelemetry(1600L, mapOf("ocr" to 1000L), null)),
        )

    // --- reduceCapture: each beat ---

    @Test
    fun `read sets the pop number and marks read seen`() {
        val s = reduceCapture(CaptureReviewUiState(), CascadeState.Read(layout("32")))
        assertEquals("32", s.popNumber)
        assertTrue(s.readSeen)
    }

    @Test
    fun `the kickoff segmenting before the read does not light the outline`() {
        assertFalse(reduceCapture(CaptureReviewUiState(), CascadeState.Segmenting(null)).subjectReady)
    }

    @Test
    fun `segmenting after the read lights the outline`() {
        val afterRead = reduceCapture(CaptureReviewUiState(), CascadeState.Read(layout("32")))
        assertTrue(reduceCapture(afterRead, CascadeState.Segmenting(null)).subjectReady)
    }

    @Test
    fun `describing sets the late description line`() {
        assertEquals("a masked figure", reduceCapture(CaptureReviewUiState(), CascadeState.Describing("a masked figure")).description)
    }

    @Test
    fun `matching flags the catalog walk`() {
        assertTrue(reduceCapture(CaptureReviewUiState(), CascadeState.Matching).matching)
    }

    @Test
    fun `settled carries the result and ends the running state`() {
        val s = reduceCapture(CaptureReviewUiState(), settled(entry(), confident = true, owned = true))
        assertNotNull(s.settled)
        assertFalse(s.running)
    }

    @Test
    fun `failed sets an honest failure and ends running`() {
        val s = reduceCapture(CaptureReviewUiState(), CascadeState.Failed("ocr", RuntimeException("boom")))
        assertEquals("Couldn't identify (ocr)", s.failure)
        assertFalse(s.running)
    }

    @Test
    fun `a full owned sequence reduces to a settled owned identity`() {
        var s = CaptureReviewUiState()
        listOf(
            CascadeState.Segmenting(null),        // kickoff — ignored
            CascadeState.Read(layout("32")),
            CascadeState.Segmenting(null),        // post-OCR — lights the outline
            CascadeState.Matching,
            settled(entry(matchedLocalId = 5L), confident = true, owned = true),
        ).forEach { s = reduceCapture(s, it) }
        assertEquals("32", s.popNumber)
        assertTrue(s.subjectReady)
        assertTrue(s.settled?.owned == true)
        assertFalse(s.running)
    }

    // --- capturedItem: the save mapping ---

    @Test
    fun `captured item maps identity, list, and value`() {
        val item = capturedItem(entry(name = "Freddy Funko", number = "32"), "Anime", 5750)
        assertEquals("Freddy Funko", item.name)
        assertEquals("Anime", item.listName)
        assertEquals(5750, item.estimatedValueCents)
        assertEquals("32", item.funkoMetadata?.popNumber)
        assertEquals(1, item.quantity)
    }

    @Test
    fun `nft redeemable is inferred from the exclusivity label`() {
        assertTrue(capturedItem(entry(exclusiveTo = "NFT Release"), "Nft funko", 0).funkoMetadata!!.isNftRedeemable)
        assertFalse(capturedItem(entry(exclusiveTo = "Funko Shop"), "Misc", 0).funkoMetadata!!.isNftRedeemable)
    }

    @Test
    fun `a blank list name becomes null`() {
        assertNull(capturedItem(entry(), "   ", 0).listName)
    }

    // --- save flow through the repository ---

    @Test
    fun `saving a captured item stores it and returns an id`() = runBlocking {
        val repo = FakeCollectibleRepository()
        val id = repo.saveCaptured(capturedItem(entry(name = "Aang with Armor", number = "406"), "Anime", 4200))
        assertTrue("expected a positive new id", id > 0)
        val stored = repo.getById(id) as? FunkoPop
        assertEquals("Aang with Armor", stored?.name)
        assertEquals("406", stored?.popNumber)
        assertEquals(4200, stored?.estimatedValueCents)
    }
}
