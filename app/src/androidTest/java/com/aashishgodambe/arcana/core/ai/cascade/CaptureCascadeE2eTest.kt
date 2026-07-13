package com.aashishgodambe.arcana.core.ai.cascade

import android.graphics.BitmapFactory
import android.util.Log
import androidx.room.Room
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.aashishgodambe.arcana.core.ai.catalog.CatalogProviderChain
import com.aashishgodambe.arcana.core.ai.catalog.CloudMultimodalCatalogProvider
import com.aashishgodambe.arcana.core.ai.catalog.LocalCollectionCatalogProvider
import com.aashishgodambe.arcana.core.ai.model.InferenceLocation
import com.aashishgodambe.arcana.core.data.database.ArcanaDatabase
import com.aashishgodambe.arcana.core.data.repository.CollectibleRepositoryImpl
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Full end-to-end: the real [CaptureCascade] — real ML Kit segmentation, real Nano describer, real OCR,
 * and the real catalog chain (empty local collection → cloud) — run on the real Aang #406 box. Aang is
 * unowned, so the cascade must read the number on-device and settle via the **cloud** escalation. Nano's
 * describe stage is best-effort (it returns null when backgrounded/refused), which is exactly why the
 * cascade still settles. Makes one real cloud call.
 */
@RunWith(AndroidJUnit4::class)
class CaptureCascadeE2eTest {

    private lateinit var db: ArcanaDatabase
    private lateinit var cascade: CaptureCascade

    @Before
    fun setUp() {
        val ctx = InstrumentationRegistry.getInstrumentation().targetContext
        db = Room.inMemoryDatabaseBuilder(ctx, ArcanaDatabase::class.java).allowMainThreadQueries().build()
        val repo = CollectibleRepositoryImpl(
            db, db.collectibleDao(), db.funkoMetadataDao(), db.seriesDao(), db.valueSnapshotDao(),
        )
        cascade = CaptureCascade(
            segmenter = MlKitImageSegmenter(),
            describer = NanoMultimodalDescriber(),
            textExtractor = MlKitTextExtractor(),
            barcodeScanner = MlKitBarcodeScanner(),
            catalogChain = CatalogProviderChain(
                listOf(LocalCollectionCatalogProvider(repo), CloudMultimodalCatalogProvider()),
            ),
        )
    }

    @After
    fun tearDown() = db.close()

    @Test
    fun runs_end_to_end_on_the_real_box_and_settles_via_cloud() = runBlocking<Unit> {
        val bmp = InstrumentationRegistry.getInstrumentation().context.assets
            .open("funko_box.png").use { BitmapFactory.decodeStream(it) }

        val states = cascade.identify(bmp).toList()

        assertTrue("no stage should hard-fail", states.none { it is CascadeState.Failed })
        assertEquals("OCR reads the Pop number", "406",
            states.filterIsInstance<CascadeState.Read>().single().layout.popNumber)

        val settled = states.filterIsInstance<CascadeState.Settled>().single().result
        assertNotNull("unowned Aang should still resolve (via cloud)", settled.entry)
        assertEquals("resolved via cloud escalation", InferenceLocation.Cloud, settled.entry!!.executedOn)
        assertTrue("name mentions Aang", settled.entry.name.contains("Aang", ignoreCase = true))
        assertTrue("per-stage telemetry captured", settled.telemetry.perStageMs.containsKey("catalog"))
        Log.i(
            "CascadeE2e",
            "settled '${settled.entry.name}' via ${settled.entry.executedOn} in " +
                "${settled.telemetry.totalMs}ms; stages=${settled.telemetry.perStageMs}",
        )
    }
}
