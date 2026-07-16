package com.aashishgodambe.arcana.core.ai.cascade

import android.graphics.Bitmap
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
import com.aashishgodambe.arcana.core.data.importer.HobbyDbCsvImporter
import com.aashishgodambe.arcana.core.data.importer.model.ImportResult
import com.aashishgodambe.arcana.core.data.repository.CollectibleRepositoryImpl
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Week-11 cascade accuracy suite. Runs the real [CaptureCascade] (real ML Kit segmentation / OCR / barcode,
 * real catalog chain) over the labeled photo fixtures, scoring identification against ground truth.
 *
 * Design choices, per EVAL_METHODOLOGY.md:
 * - **Describe disabled** (a no-op [MultimodalDescriber]) so the batch runs headless — describe is Nano
 *   (foreground-only, ErrorCode 30) and is OFF the identification critical path, so removing it changes
 *   nothing about identity.
 * - **Owned photos run local-only** (no cloud): the whole point is to measure the on-device path — OCR reads
 *   the Pop number, [LocalCollectionCatalogProvider] matches the owned item. A local miss is recorded as an
 *   on-device failure, not papered over by a cloud call.
 * - **Unowned photos escalate to cloud**, but **deduped per distinct item and capped + paced** to respect the
 *   free-tier quota (conserve cloud tokens): one real cloud identify per unowned Pop number.
 *
 * Not reproducible from a clean checkout (needs the private box photos); the committed manifest holds the
 * labels. Photos are gitignored assets.
 */
@RunWith(AndroidJUnit4::class)
class CascadeAccuracyEvalTest {

    private lateinit var db: ArcanaDatabase
    private lateinit var localCascade: CaptureCascade
    private lateinit var cloudCascade: CaptureCascade

    private val noopDescriber = object : MultimodalDescriber {
        override suspend fun describe(bitmap: Bitmap, onPartial: (String) -> Unit): String? = null
    }

    /**
     * No-op segmenter. Segmentation only produces the UI outline (the masked subject) — it never feeds
     * identity, so it's off the identification path being scored. It's also disabled deliberately: ML Kit
     * subject-segmentation breaks the text recognizer **process-wide** (the reason the cascade runs OCR
     * first), which in a 37-photo batch would let one photo's segmentation corrupt the NEXT photo's OCR —
     * a batch-only artifact absent from real single captures. Removing it isolates a clean OCR+catalog read.
     */
    private val noopSegmenter = object : ImageSegmenter {
        override suspend fun segment(bitmap: Bitmap): SegmentationResult = SegmentationResult(null)
    }

    @Before
    fun setUp() = runBlocking {
        val ctx = InstrumentationRegistry.getInstrumentation().targetContext
        db = Room.inMemoryDatabaseBuilder(ctx, ArcanaDatabase::class.java).allowMainThreadQueries().build()
        val repo = CollectibleRepositoryImpl(
            db, db.collectibleDao(), db.funkoMetadataDao(), db.seriesDao(), db.valueSnapshotDao(),
        )
        val csv = InstrumentationRegistry.getInstrumentation().context.assets.open(CSV_FIXTURE)
        val parsed = csv.use { HobbyDbCsvImporter.parse(it) } as ImportResult.Success
        repo.importFrom(parsed.items)

        // One set of ML Kit components, shared by both cascades (OCR runs first inside the cascade, so the
        // recognizer isn't broken by segmentation).
        val seg = noopSegmenter; val ocr = MlKitTextExtractor(); val bar = MlKitBarcodeScanner()
        val local = CatalogProviderChain(listOf(LocalCollectionCatalogProvider(repo)))
        val full = CatalogProviderChain(listOf(LocalCollectionCatalogProvider(repo), CloudMultimodalCatalogProvider()))
        localCascade = CaptureCascade(seg, noopDescriber, ocr, bar, local)
        cloudCascade = CaptureCascade(seg, noopDescriber, ocr, bar, full)
    }

    @After
    fun tearDown() = db.close()

    @Test
    fun scores_cascade_identification_over_the_photo_fixtures() = runBlocking {
        val fixtures = loadFixtures()
        assertTrue("no photo fixtures loaded", fixtures.isNotEmpty())
        val assets = InstrumentationRegistry.getInstrumentation().context.assets

        val cloudCalledItems = mutableSetOf<String>()
        var cloudCalls = 0
        val results = mutableListOf<PhotoResult>()

        for (f in fixtures) {
            val owned = f.owned == "yes"
            val useCloud = !owned && f.popNumber !in cloudCalledItems && cloudCalls < CLOUD_CAP
            if (useCloud) {
                if (cloudCalls > 0) delay(CLOUD_PACE_MS)   // stay under the free-tier per-minute quota
                cloudCalls++; cloudCalledItems += f.popNumber
            }

            val bmp = assets.open(f.photo).use { BitmapFactory.decodeStream(it) }
            val states = try {
                (if (useCloud) cloudCascade else localCascade).identify(bmp).toList()
            } catch (e: Exception) {
                Log.w(TAG, "cascade threw on ${f.photo}", e); emptyList()
            }
            bmp.recycle()

            val failed = states.any { it is CascadeState.Failed }
            val ocrNumber = states.filterIsInstance<CascadeState.Read>().firstOrNull()?.layout?.popNumber
            val entry = states.filterIsInstance<CascadeState.Settled>().firstOrNull()?.result?.entry
            val locus = states.filterIsInstance<CascadeState.Settled>().firstOrNull()?.result?.telemetry?.resolvedOn

            val numberHit = ocrNumber != null && ocrNumber == f.popNumber
            val resolved = entry != null
            val identityHit = resolved && entry!!.number == f.popNumber &&
                (if (owned) entry.matchedLocalId != null else true)
            val expectedLocus = if (owned) InferenceLocation.OnDevice else InferenceLocation.Cloud
            val locusHit = resolved && locus == expectedLocus

            results += PhotoResult(f, owned, useCloud, failed, ocrNumber, numberHit, resolved, identityHit, locusHit)
        }

        report(results, cloudCalls)

        assertTrue("cascade hard-failed on ${results.count { it.failed }} photos", results.none { it.failed })
        assertTrue("cloud calls $cloudCalls exceeded cap $CLOUD_CAP", cloudCalls <= CLOUD_CAP)
    }

    /**
     * Zero-cloud OCR attribution: dump the raw ML Kit lines and the parser's pick per photo, to tell a
     * genuine **OCR miss** (ML Kit never read the number) from a **parser drop** (`BoxLayoutParser` read it
     * but its largest-height heuristic chose a different number). Run standalone; makes no catalog/cloud call.
     */
    @Test
    fun dumps_raw_ocr_for_attribution() = runBlocking {
        val ocr = MlKitTextExtractor()
        val assets = InstrumentationRegistry.getInstrumentation().context.assets
        for (f in loadFixtures()) {
            val bmp = assets.open(f.photo).use { BitmapFactory.decodeStream(it) }
            val lines = ocr.extract(bmp).lines
            val parsed = BoxLayoutParser.parse(lines).popNumber
            bmp.recycle()
            val ocrHasIt = lines.any { it.text.replace(Regex("\\D"), "").contains(f.popNumber) }
            Log.i("OcrDiag", "${f.photo.padEnd(24)} want=${f.popNumber.padEnd(5)} parsed=${(parsed ?: "—").padEnd(6)} ocrRead=${if (ocrHasIt) "Y" else "n"} | ${lines.joinToString("  ") { it.text }}")
        }
    }

    private fun report(rs: List<PhotoResult>, cloudCalls: Int) {
        val owned = rs.filter { it.owned }
        val unownedCloud = rs.filter { it.usedCloud }
        fun rate(n: Int, d: Int) = if (d == 0) "n/a" else "$n/$d (${"%.0f".format(100.0 * n / d)}%)"

        Log.i(TAG, "===== Cascade accuracy (Week-11 eval, N=${rs.size} photos / ${rs.map { it.f.popNumber }.distinct().size} items) =====")
        rs.forEach {
            Log.i(TAG, "  [${mark(it)}] ${it.f.photo.padEnd(24)} ocr=${(it.ocrNumber ?: "—").padEnd(6)} want=${it.f.popNumber.padEnd(5)} ${if (it.owned) "owned" else "UNOWN"} ${it.f.failureMode}")
        }
        Log.i(TAG, "----- aggregates -----")
        Log.i(TAG, "OCR Pop-number (all):        ${rate(rs.count { it.numberHit }, rs.size)}")
        Log.i(TAG, "OCR Pop-number (clean only): ${rate(rs.count { it.f.failureMode == "clean" && it.numberHit }, rs.count { it.f.failureMode == "clean" })}")
        Log.i(TAG, "On-device resolve (owned):   ${rate(owned.count { it.identityHit && it.locusHit }, owned.size)}")
        Log.i(TAG, "Cloud resolve (unowned):     ${rate(unownedCloud.count { it.resolved }, unownedCloud.size)}  ($cloudCalls cloud calls)")
        Log.i(TAG, "Identity correct (all):      ${rate(rs.count { it.identityHit }, rs.size)}")
        // Per-failure-mode OCR breakdown (the anecdote -> rate reframe).
        rs.groupBy { it.f.failureMode }.toSortedMap().forEach { (mode, g) ->
            Log.i(TAG, "  OCR by mode  ${mode.padEnd(9)} ${rate(g.count { it.numberHit }, g.size)}")
        }
    }

    private fun mark(r: PhotoResult) = when {
        r.failed -> "!!"
        r.identityHit && r.locusHit -> "OK"
        r.numberHit -> "n?"   // read the number but didn't resolve the identity
        else -> "XX"
    }

    private data class PhotoResult(
        val f: PhotoFixture, val owned: Boolean, val usedCloud: Boolean, val failed: Boolean,
        val ocrNumber: String?, val numberHit: Boolean, val resolved: Boolean,
        val identityHit: Boolean, val locusHit: Boolean,
    )

    private data class PhotoFixture(
        val photo: String, val item: String, val popNumber: String,
        val franchise: String, val character: String, val owned: String, val failureMode: String,
    )

    private fun loadFixtures(): List<PhotoFixture> {
        val lines = InstrumentationRegistry.getInstrumentation().context.assets
            .open(PHOTO_FIXTURE).bufferedReader().readLines()
            .map { it.trim() }.filter { it.isNotEmpty() && !it.startsWith("#") }
        return lines.drop(1).mapNotNull { line ->
            val c = line.split("\t")
            if (c.size < 9) return@mapNotNull null
            PhotoFixture(c[0].trim(), c[1].trim(), c[2].trim(), c[3].trim(), c[4].trim(), c[6].trim(), c[8].trim())
        }
    }

    private companion object {
        const val TAG = "CascadeEval"
        const val CSV_FIXTURE = "collectibles_2026-07-03.csv"
        const val PHOTO_FIXTURE = "eval/photo_fixtures.tsv"
        const val CLOUD_CAP = 8          // one real cloud identify per distinct unowned item, bounded
        const val CLOUD_PACE_MS = 4000L  // spacing between cloud calls -> under the 20/min free-tier quota
    }
}
