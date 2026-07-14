package com.aashishgodambe.arcana.core.ai.rag

import com.aashishgodambe.arcana.core.data.repository.FakeCollectibleRepository
import com.aashishgodambe.arcana.core.domain.model.Collectible
import com.aashishgodambe.arcana.core.domain.model.FunkoPop
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.LocalDate

/** JVM tests for the indexer's embed / incremental-skip / unavailable-fallback behaviour. */
class CollectionIndexerTest {

    private fun funko(id: Long, name: String, series: List<String> = emptyList()) = FunkoPop(
        localId = id, name = name, brand = "Funko", imageUrl = null,
        estimatedValueCents = 1000, lastKnownValueCents = null, quantity = 1,
        itemCondition = "Mint", packagingCondition = "Mint", series = series, productionTags = emptyList(),
        dateAdded = LocalDate.of(2023, 1, 1), pricePaidCents = null, storageLocation = null,
        upc = "0$id", popNumber = "$id", exclusiveTo = null, isNftRedeemable = false,
    )

    private fun indexer(items: List<Collectible>, dao: FakeVectorDao, embedder: FakeCollectionEmbedder) =
        CollectionIndexer(FakeCollectibleRepository(items), embedder, CollectionVectorStore(dao))

    @Test
    fun `unavailable embedder is a clean no-op`() = runTest {
        val dao = FakeVectorDao()
        val result = indexer(listOf(funko(1, "Batman")), dao, FakeCollectionEmbedder(available = false)).index()
        assertFalse(result.available)
        assertEquals(0, dao.count())
    }

    @Test
    fun `indexes every item and persists a vector each`() = runTest {
        val dao = FakeVectorDao()
        val items = listOf(funko(1, "Batman"), funko(2, "Deadpool"), funko(3, "Daenerys"))
        val result = indexer(items, dao, FakeCollectionEmbedder()).index()
        assertEquals(3, result.embedded)
        assertEquals(3, dao.count())
        assertEquals(3, result.total)
    }

    @Test
    fun `re-indexing unchanged items skips them all`() = runTest {
        val dao = FakeVectorDao()
        val items = listOf(funko(1, "Batman"), funko(2, "Deadpool"))
        val embedder = FakeCollectionEmbedder()
        indexer(items, dao, embedder).index()
        val second = indexer(items, dao, embedder).index()
        assertEquals(0, second.embedded)
        assertEquals(2, second.skipped)
    }

    @Test
    fun `an item whose document text changed is re-embedded, others skipped`() = runTest {
        val dao = FakeVectorDao()
        val embedder = FakeCollectionEmbedder()
        indexer(listOf(funko(1, "Batman"), funko(2, "Deadpool")), dao, embedder).index()

        // Rename item 2 → its document hash changes; item 1 is untouched.
        val changed = listOf(funko(1, "Batman"), funko(2, "Deadpool 2"))
        val result = indexer(changed, dao, embedder).index()
        assertEquals(1, result.embedded)
        assertEquals(1, result.skipped)
        assertEquals(2, dao.count())
    }

    @Test
    fun `progress is reported for every item`() = runTest {
        val dao = FakeVectorDao()
        val items = (1L..5L).map { funko(it, "Pop $it") }
        val seen = mutableListOf<Int>()
        indexer(items, dao, FakeCollectionEmbedder()).index { seen.add(it.done) }
        assertTrue(seen == listOf(1, 2, 3, 4, 5))
    }
}
