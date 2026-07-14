package com.aashishgodambe.arcana.core.ai.rag

import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** Sanity checks for the test double itself — that it gates on availability and gives token overlap a real cosine. */
class FakeCollectionEmbedderTest {

    @Test
    fun `returns null when the model is marked unavailable`() = runTest {
        val embedder = FakeCollectionEmbedder(available = false)
        assertNull(embedder.embedQuery("dragons"))
        assertNull(embedder.embedDocument("Daenerys with dragon egg"))
    }

    @Test
    fun `shared tokens embed closer than disjoint ones`() = runTest {
        val embedder = FakeCollectionEmbedder()
        val query = embedder.embedQuery("dragon targaryen")!!
        val related = embedder.embedDocument("Daenerys Targaryen with dragon egg")!!
        val unrelated = embedder.embedDocument("Batman the Dark Knight")!!
        assertTrue(
            "shared-token cosine ${EmbeddingMath.cosine(query, related)} should beat " +
                "disjoint ${EmbeddingMath.cosine(query, unrelated)}",
            EmbeddingMath.cosine(query, related) > EmbeddingMath.cosine(query, unrelated),
        )
    }

    @Test
    fun `overrides pin an exact vector for a controlled semantic relationship`() = runTest {
        // "dragons" and "Daenerys" share no tokens, so token overlap alone can't relate them — pin vectors.
        val embedder = FakeCollectionEmbedder(
            nativeDimension = 4,
            overrides = mapOf(
                "dragons" to floatArrayOf(1f, 1f, 0f, 0f),
                "Daenerys Stormborn" to floatArrayOf(1f, 0.9f, 0f, 0f),
                "office stapler" to floatArrayOf(0f, 0f, 1f, 1f),
            ),
        )
        val q = embedder.embedQuery("dragons")!!
        val daenerys = embedder.embedDocument("Daenerys Stormborn")!!
        val stapler = embedder.embedDocument("office stapler")!!
        assertTrue(EmbeddingMath.cosine(q, daenerys) > EmbeddingMath.cosine(q, stapler))
    }
}
