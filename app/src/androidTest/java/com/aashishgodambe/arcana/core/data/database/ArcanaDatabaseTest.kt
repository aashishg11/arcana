package com.aashishgodambe.arcana.core.data.database

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.aashishgodambe.arcana.core.data.database.dao.CollectibleDao
import com.aashishgodambe.arcana.core.data.database.dao.SeriesDao
import com.aashishgodambe.arcana.core.data.database.entity.CollectibleCategory
import com.aashishgodambe.arcana.core.data.database.entity.CollectibleEntity
import com.aashishgodambe.arcana.core.data.database.entity.CollectibleOrigin
import com.aashishgodambe.arcana.core.data.database.entity.CollectibleSeriesCrossRef
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import java.time.LocalDate

@RunWith(AndroidJUnit4::class)
class ArcanaDatabaseTest {

    private lateinit var db: ArcanaDatabase
    private lateinit var collectibleDao: CollectibleDao
    private lateinit var seriesDao: SeriesDao

    @Before
    fun setUp() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        db = Room.inMemoryDatabaseBuilder(context, ArcanaDatabase::class.java)
            .allowMainThreadQueries()
            .build()
        collectibleDao = db.collectibleDao()
        seriesDao = db.seriesDao()
    }

    @After
    fun tearDown() = db.close()

    @Test
    fun insertCollectibleWithTwoSeries_readsBackJoined() = runBlocking {
        val id = collectibleDao.insert(sampleCollectible(name = "Daenerys with Egg", valueCents = 69_000))
        val gotId = seriesDao.getOrInsert("Game of Thrones")
        val digitalId = seriesDao.getOrInsert("Pop! Digital")
        seriesDao.insertCrossRef(CollectibleSeriesCrossRef(id, gotId))
        seriesDao.insertCrossRef(CollectibleSeriesCrossRef(id, digitalId))

        val withSeries = collectibleDao.getWithSeries(id)

        assertNotNull(withSeries)
        assertEquals("Daenerys with Egg", withSeries!!.collectible.name)
        assertEquals(
            setOf("Game of Thrones", "Pop! Digital"),
            withSeries.series.map { it.name }.toSet(),
        )
    }

    @Test
    fun getOrInsert_isCanonical_sameNameReturnsSameId() = runBlocking {
        val first = seriesDao.getOrInsert("Marvel")
        val second = seriesDao.getOrInsert("Marvel")
        assertEquals(first, second)
    }

    @Test
    fun getMostValuable_ordersByValueDescAndRespectsLimit() = runBlocking {
        collectibleDao.insert(sampleCollectible(name = "cheap", valueCents = 5_000))
        collectibleDao.insert(sampleCollectible(name = "expensive", valueCents = 69_000))
        collectibleDao.insert(sampleCollectible(name = "mid", valueCents = 45_000))

        val top = collectibleDao.getMostValuable(limit = 2)

        assertEquals(listOf("expensive", "mid"), top.map { it.name })
    }

    private fun sampleCollectible(name: String, valueCents: Int) = CollectibleEntity(
        category = CollectibleCategory.Funko,
        origin = CollectibleOrigin.HobbyDbImport,
        sourceId = null,
        sourceName = "HobbyDB",
        listName = "Test List",
        name = name,
        brand = "Funko",
        imageUrl = null,
        itemCondition = "Mint",
        packagingCondition = "Mint",
        quantity = 1,
        estimatedValueCents = valueCents,
        lastKnownValueCents = null,
        lastKnownValueSource = null,
        lastKnownValueAt = null,
        pricePaidCents = null,
        acquiredFrom = null,
        datePurchased = null,
        dateAdded = LocalDate.of(2026, 6, 19),
        storageLocation = null,
        privateNotes = null,
    )
}
