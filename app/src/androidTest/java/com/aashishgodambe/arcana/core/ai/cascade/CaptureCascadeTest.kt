package com.aashishgodambe.arcana.core.ai.cascade

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.aashishgodambe.arcana.core.ai.catalog.CatalogEntry
import com.aashishgodambe.arcana.core.ai.catalog.CatalogProvider
import com.aashishgodambe.arcana.core.ai.catalog.CatalogProviderChain
import com.aashishgodambe.arcana.core.ai.catalog.CatalogQuery
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Orchestrator test with a **fake for every seam** (segmenter, describer, text extractor, catalog chain).
 * A real Bitmap comes from the committed fixture — everything else is deterministic and offline — so this
 * asserts the per-stage [CascadeState] Flow the Week-9 UI renders, end to end, without ML or network.
 */
@RunWith(AndroidJUnit4::class)
class CaptureCascadeTest {

    private fun line(text: String, top: Int, bottom: Int) = RecognizedLine(text, BoundingBox(0, top, 100, bottom))

    // Enough of the real Aang OCR for BoxLayoutParser to recover franchise=AVATAR, number=406, character.
    private val aangLines = listOf(
        line("406", 92, 184),
        line("AVATAR", 114, 160),
        line("Digital", 211, 233),
        line("AANG ARMOR METALLIC", 933, 1005),
        line("VINYL FIGURE / FIGURINE EN VINYLE", 996, 1021),
    )

    private class FakeSegmenter(private val subject: Bitmap?) : ImageSegmenter {
        override suspend fun segment(bitmap: Bitmap) = SegmentationResult(subject)
    }

    private class FakeDescriber(private val read: LlmBoxRead?, private val partials: List<String>) : MultimodalDescriber {
        override suspend fun describe(bitmap: Bitmap, onPartial: (String) -> Unit): LlmBoxRead? {
            partials.forEach(onPartial)
            return read
        }
    }

    private class FakeTextExtractor(private val extraction: TextExtraction) : TextExtractor {
        override suspend fun extract(bitmap: Bitmap) = extraction
    }

    private class FakeBarcodeScanner(private val value: String? = null) : BarcodeScanner {
        override suspend fun scan(bitmap: Bitmap) = value
    }

    private fun ownedChain() = CatalogProviderChain(
        listOf(
            object : CatalogProvider {
                override val sourceName = "Your collection"
                override suspend fun lookup(query: CatalogQuery): CatalogEntry? =
                    if (query.popNumber == "406") {
                        CatalogEntry(
                            sourceName = sourceName, externalId = "local:52", name = "Aang with Armor",
                            franchise = null, series = listOf("Pop! Digital"), number = "406",
                            exclusiveTo = null, imageUrl = null, confidence = 0.95f, matchedLocalId = 52L,
                        )
                    } else null
            },
        ),
    )

    @Test
    fun emits_each_stage_and_settles_on_an_owned_identity() = runBlocking {
        val ctx = InstrumentationRegistry.getInstrumentation().context
        val bmp = ctx.assets.open("funko_box.png").use { BitmapFactory.decodeStream(it) }

        val cascade = CaptureCascade(
            segmenter = FakeSegmenter(subject = bmp), // masked subject for the UI outline state
            describer = FakeDescriber(LlmBoxRead(character = "Aang", franchise = "Avatar"), listOf("a masked", "a masked figure")),
            textExtractor = FakeTextExtractor(TextExtraction("406 AVATAR", aangLines)),
            barcodeScanner = FakeBarcodeScanner(),
            catalogChain = ownedChain(),
        )

        val states = cascade.identify(bmp).toList()

        // Segmentation: an initial in-progress state, then one carrying the (fallback) subject bitmap.
        assertTrue("first state segmenting", states.first() is CascadeState.Segmenting)
        assertNotNull(
            "a Segmenting state should carry the subject",
            states.filterIsInstance<CascadeState.Segmenting>().last().subject,
        )
        // Streaming description surfaced.
        assertTrue("expected a Describing state", states.any { it is CascadeState.Describing })
        // OCR read carries the Pop number for the callout.
        assertEquals("406", states.filterIsInstance<CascadeState.Read>().single().layout.popNumber)
        // Chain-walking feedback.
        assertTrue("expected Matching", states.any { it is CascadeState.Matching })
        // Settled on the owned identity, with telemetry.
        val settled = states.filterIsInstance<CascadeState.Settled>().single().result
        assertEquals(52L, settled.entry?.matchedLocalId)
        assertTrue("should be confident", settled.confident)
        assertTrue("should be owned", settled.owned)
        assertTrue(
            "telemetry should cover every stage",
            settled.telemetry.perStageMs.keys.containsAll(listOf("segment", "describe", "ocr", "catalog")),
        )
    }
}
