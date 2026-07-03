package com.aashishgodambe.arcana.core.data.importer

import androidx.room.Room
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.aashishgodambe.arcana.core.data.database.ArcanaDatabase
import com.aashishgodambe.arcana.core.data.importer.model.ImportResult
import com.aashishgodambe.arcana.core.data.repository.CollectibleRepositoryImpl
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Integration test against the real committed HobbyDB export (seed-data/, exposed as an
 * androidTest asset). Asserts the importer + repository turn the real messy CSV into correct rows.
 *
 * Expected counts track the committed fixture; update them if the CSV is re-exported.
 */
@RunWith(AndroidJUnit4::class)
class HobbyDbImportIntegrationTest {

    private lateinit var db: ArcanaDatabase
    private lateinit var repo: CollectibleRepositoryImpl

    @Before
    fun setUp() {
        val ctx = InstrumentationRegistry.getInstrumentation().targetContext
        db = Room.inMemoryDatabaseBuilder(ctx, ArcanaDatabase::class.java)
            .allowMainThreadQueries()
            .build()
        repo = CollectibleRepositoryImpl(
            db, db.collectibleDao(), db.funkoMetadataDao(), db.seriesDao(), db.valueSnapshotDao(),
        )
    }

    @After
    fun tearDown() = db.close()

    @Test
    fun importsRealHobbyDbCsv_withEveryQuirkHandled() = runBlocking {
        val stream = InstrumentationRegistry.getInstrumentation().context.assets.open(FIXTURE)
        val result = stream.use { HobbyDbCsvImporter.parse(it) }

        assertTrue("parse should succeed", result is ImportResult.Success)
        result as ImportResult.Success

        // Parse-level
        assertEquals(EXPECTED_RECORDS, result.itemsParsed)
        assertEquals(0, result.itemsSkipped)
        assertEquals(EXPECTED_NFT, result.items.count { it.funkoMetadata?.isNftRedeemable == true })

        // Persist
        val inserted = repo.importFrom(result.items)
        assertEquals(EXPECTED_RECORDS, inserted)

        val all = db.collectibleDao().getAll().first()
        assertEquals(EXPECTED_RECORDS, all.size)

        // NFT redeemable rows
        assertEquals(EXPECTED_NFT, db.funkoMetadataDao().countNftRedeemable())

        // Leading-zero UPC preserved exactly
        assertNotNull("leading-zero UPC should be preserved", db.funkoMetadataDao().getByUpc("0889698542579"))

        // No =HYPERLINK survives anywhere
        assertTrue(
            "no imageUrl should still contain =HYPERLINK",
            all.none { it.imageUrl?.contains("=HYPERLINK", ignoreCase = true) == true },
        )

        // Series junction: a known 4-series item resolves to exactly 4 rows
        val daenerys = all.first { it.name == "Daenerys Targaryen With Egg" }
        val withSeries = db.collectibleDao().getWithSeries(daenerys.localId)!!
        assertEquals(4, withSeries.series.size)

        // Exactly one import-time value snapshot per collectible
        assertEquals(1, db.valueSnapshotDao().getForCollectible(daenerys.localId).size)
    }

    companion object {
        private const val FIXTURE = "collectibles_2026-07-03.csv"
        private const val EXPECTED_RECORDS = 504
        private const val EXPECTED_NFT = 141
    }
}
