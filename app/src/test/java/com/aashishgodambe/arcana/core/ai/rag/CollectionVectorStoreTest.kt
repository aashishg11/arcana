package com.aashishgodambe.arcana.core.ai.rag

import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Test

/** JVM tests for the vector store's persistence + brute-force top-k (with a fake DAO). */
class CollectionVectorStoreTest {

    private fun store() = CollectionVectorStore(FakeVectorDao())

    /** A 768-dim one-hot vector (a distinct direction per index) to make cosine ranking unambiguous. */
    private fun oneHot(at: Int): FloatArray = FloatArray(768).also { it[at] = 1f }

    @Test
    fun `topK ranks by cosine and returns the nearest collectible first`() = runTest {
        val store = store()
        store.upsert(collectibleId = 10, vector = oneHot(0), docHash = 1)
        store.upsert(collectibleId = 20, vector = oneHot(1), docHash = 1)
        store.upsert(collectibleId = 30, vector = oneHot(2), docHash = 1)

        val ranked = store.topK(oneHot(1), k = 2)
        assertEquals(listOf(20L, 10L).size, ranked.size)
        assertEquals(20L, ranked.first().id)     // exact match to id 20's direction
        assertEquals(1f, ranked.first().score, 1e-5f)
    }

    @Test
    fun `upsert replaces by collectible id and count reflects it`() = runTest {
        val store = store()
        store.upsert(1, oneHot(0), docHash = 1)
        store.upsert(1, oneHot(5), docHash = 2)   // same id → replace
        store.upsert(2, oneHot(1), docHash = 1)
        assertEquals(2, store.count())
        assertEquals(mapOf(1L to 2, 2L to 1), store.indexedHashes())
    }

    @Test
    fun `topK truncates to the requested MRL dimension without error`() = runTest {
        val store = store()
        store.upsert(1, oneHot(0), docHash = 1)
        store.upsert(2, oneHot(300), docHash = 1)   // direction only visible at full dim
        // At dim 128 both truncate to the leading 128 dims; id 1 (index 0) stays a match, id 2 becomes zero.
        val ranked = store.topK(oneHot(0), k = 2, dim = 128)
        assertEquals(1L, ranked.first().id)
    }
}
