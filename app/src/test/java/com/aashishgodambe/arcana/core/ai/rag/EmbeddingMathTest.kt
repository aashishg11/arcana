package com.aashishgodambe.arcana.core.ai.rag

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.sqrt

/** JVM tests for the pure RAG vector logic — MRL truncation, normalisation, cosine, and ranking. */
class EmbeddingMathTest {

    private fun norm(v: FloatArray): Double = sqrt(v.fold(0.0) { acc, f -> acc + f.toDouble() * f })

    @Test
    fun `truncate keeps the leading dims and re-normalises to unit length`() {
        val truncated = EmbeddingMath.truncate(floatArrayOf(3f, 4f, 100f, 100f), dim = 2)
        assertEquals(2, truncated.size)
        assertEquals(1.0, norm(truncated), 1e-6)
        // Direction of the leading two dims is preserved: 3,4 → 0.6,0.8.
        assertEquals(0.6f, truncated[0], 1e-6f)
        assertEquals(0.8f, truncated[1], 1e-6f)
    }

    @Test
    fun `truncate to a dim at or beyond length normalises without dropping anything`() {
        val v = floatArrayOf(3f, 4f)
        assertEquals(2, EmbeddingMath.truncate(v, dim = 5).size)
        assertEquals(1.0, norm(EmbeddingMath.truncate(v, dim = 5)), 1e-6)
    }

    @Test
    fun `l2Normalize leaves a zero vector unchanged instead of dividing by zero`() {
        val zero = floatArrayOf(0f, 0f, 0f)
        assertTrue(EmbeddingMath.l2Normalize(zero).all { it == 0f })
    }

    @Test
    fun `cosine is 1 for identical, 0 for orthogonal, -1 for opposite`() {
        assertEquals(1f, EmbeddingMath.cosine(floatArrayOf(1f, 2f), floatArrayOf(1f, 2f)), 1e-6f)
        assertEquals(0f, EmbeddingMath.cosine(floatArrayOf(1f, 0f), floatArrayOf(0f, 1f)), 1e-6f)
        assertEquals(-1f, EmbeddingMath.cosine(floatArrayOf(1f, 1f), floatArrayOf(-1f, -1f)), 1e-6f)
    }

    @Test
    fun `cosine normalises internally so unnormalised inputs still match by direction`() {
        // Same direction, very different magnitude → still 1.
        assertEquals(1f, EmbeddingMath.cosine(floatArrayOf(2f, 0f), floatArrayOf(9f, 0f)), 1e-6f)
    }

    @Test
    fun `topK returns the k most similar, most similar first`() {
        val query = floatArrayOf(1f, 0f)
        val corpus = listOf(
            3L to floatArrayOf(0f, 1f),    // orthogonal — least similar
            1L to floatArrayOf(1f, 0f),    // identical — most similar
            2L to floatArrayOf(1f, 0.2f),  // close
        )
        val ranked = EmbeddingMath.topK(query, corpus, k = 2)
        assertEquals(listOf(1L, 2L), ranked.map { it.id })
        assertTrue("scores descend", ranked[0].score >= ranked[1].score)
    }
}
