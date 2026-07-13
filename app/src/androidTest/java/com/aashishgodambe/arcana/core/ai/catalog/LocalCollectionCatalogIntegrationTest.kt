package com.aashishgodambe.arcana.core.ai.catalog

import androidx.room.Room
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.aashishgodambe.arcana.core.data.database.ArcanaDatabase
import com.aashishgodambe.arcana.core.data.importer.HobbyDbCsvImporter
import com.aashishgodambe.arcana.core.data.importer.model.ImportResult
import com.aashishgodambe.arcana.core.data.repository.CollectibleRepositoryImpl
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Integration test: [LocalCollectionCatalogProvider] over the *real* committed HobbyDB export imported
 * into Room. Proves the Day-3 DoD on real data — a Pop the user owns is identified from the fused hints,
 * and an unowned Pop number does not falsely match a same-numbered/other item (it escalates).
 */
@RunWith(AndroidJUnit4::class)
class LocalCollectionCatalogIntegrationTest {

    private lateinit var db: ArcanaDatabase
    private lateinit var provider: LocalCollectionCatalogProvider

    @Before
    fun setUp() = runBlocking<Unit> {
        val ctx = InstrumentationRegistry.getInstrumentation().targetContext
        db = Room.inMemoryDatabaseBuilder(ctx, ArcanaDatabase::class.java).allowMainThreadQueries().build()
        val repo = CollectibleRepositoryImpl(
            db, db.collectibleDao(), db.funkoMetadataDao(), db.seriesDao(), db.valueSnapshotDao(),
        )
        provider = LocalCollectionCatalogProvider(repo)
        val stream = InstrumentationRegistry.getInstrumentation().context.assets.open(FIXTURE)
        val parsed = stream.use { HobbyDbCsvImporter.parse(it) } as ImportResult.Success
        repo.importFrom(parsed.items)
    }

    @After
    fun tearDown() = db.close()

    @Test
    fun identifies_an_owned_pop_from_the_real_collection() = runBlocking {
        // Fire Nation Aang #52 (HDBID 806409) is in the collection.
        val match = provider.lookup(
            CatalogQuery(popNumber = "52", franchise = "Avatar", character = "Fire Nation Aang"),
        )
        assertNotNull("expected an owned match for Fire Nation Aang #52", match)
        assertTrue("name was ${match!!.name}", match.name.contains("Fire Nation Aang", ignoreCase = true))
        assertNotNull("owned match should carry a localId", match.matchedLocalId)
        assertTrue("confidence was ${match.confidence}", match.confidence >= 0.9f)
    }

    @Test
    fun identifies_the_owned_freddy_funko_32() = runBlocking {
        // The Popeye "Freddy Funko" #32 the user photographed IS owned — HobbyDB catalogues it
        // generically as "Freddy Funko" (series "…Series 1", NFT Redeemable), so number + name resolve it.
        val match = provider.lookup(
            CatalogQuery(popNumber = "32", franchise = "Popeye", character = "Freddy Funko"),
        )
        assertNotNull("photographed Freddy Funko #32 is owned", match)
        assertTrue("name was ${match!!.name}", match.name.contains("Freddy Funko", ignoreCase = true))
        assertNotNull("owned match should carry a localId", match.matchedLocalId)
        assertTrue("confidence was ${match.confidence}", match.confidence >= 0.7f)
    }

    @Test
    fun escalates_when_no_owned_pop_has_that_number() = runBlocking {
        // A number no Pop in the collection carries → no candidates → null → the cascade escalates.
        val match = provider.lookup(
            CatalogQuery(popNumber = "99999", franchise = "Avatar", character = "Aang"),
        )
        assertNull("an absent Pop number must escalate, not match", match)
    }

    private companion object {
        const val FIXTURE = "collectibles_2026-07-03.csv"
    }
}
