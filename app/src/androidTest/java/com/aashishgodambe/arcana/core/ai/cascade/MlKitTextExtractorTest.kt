package com.aashishgodambe.arcana.core.ai.cascade

import android.graphics.BitmapFactory
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Device integration test: [MlKitTextExtractor] + [PopNumberParser] on the real Aang #406 fixture must
 * recover "406". ML Kit vision is offline and deterministic (no network, no quota, bundled model), so
 * this is a stable integration check. The pure ranking logic is covered device-free in
 * [PopNumberParserTest]; this proves the ML Kit wiring + geometry mapping actually feed it correctly.
 */
@RunWith(AndroidJUnit4::class)
class MlKitTextExtractorTest {

    @Test
    fun reads_pop_number_from_real_box() = runBlocking {
        val ctx = InstrumentationRegistry.getInstrumentation().context
        val bitmap = ctx.assets.open("funko_box.png").use { BitmapFactory.decodeStream(it) }

        val extraction = MlKitTextExtractor().extract(bitmap)

        assertTrue("expected OCR to read some text", extraction.fullText.isNotBlank())
        assertEquals("406", PopNumberParser.parse(extraction.lines).best)

        // End-to-end positional layout: ML Kit geometry -> BoxLayoutParser fields.
        val layout = BoxLayoutParser.parse(extraction.lines)
        assertEquals("AVATAR", layout.franchise)
        assertTrue("character was ${layout.character}", layout.character?.contains("AANG") == true)
        assertEquals("406", layout.popNumber)
    }
}
