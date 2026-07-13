package com.aashishgodambe.arcana.core.ai.cascade

import android.graphics.BitmapFactory
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Device integration test: [MlKitImageSegmenter] returns a subject bitmap for the real Aang #406 box.
 * The subject-segmentation model downloads on first use, so this needs network on a fresh install and
 * the first run may be slow. Verifies the ML Kit wiring produces a usable foreground bitmap; the mask's
 * visual quality on varied shelf photos is a Day-2 fixture-set concern, not this smoke check.
 */
@RunWith(AndroidJUnit4::class)
class MlKitImageSegmenterTest {

    @Test
    fun returns_subject_bitmap_for_real_box() = runBlocking {
        val ctx = InstrumentationRegistry.getInstrumentation().context
        val bitmap = ctx.assets.open("funko_box.png").use { BitmapFactory.decodeStream(it) }

        val result = MlKitImageSegmenter().segment(bitmap)

        assertNotNull("expected a foreground/subject bitmap", result.subjectBitmap)
        assertTrue("subject bitmap should be non-empty", result.subjectBitmap!!.width > 0)
    }
}
