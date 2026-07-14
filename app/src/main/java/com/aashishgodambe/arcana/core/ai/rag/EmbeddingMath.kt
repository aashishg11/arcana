package com.aashishgodambe.arcana.core.ai.rag

import kotlin.math.sqrt

/**
 * Pure vector logic for the on-device RAG retriever — no android/LiteRT types, so it's fully JVM-tested.
 * The impure part (running EmbeddingGemma on the LiteRT interpreter) lives in [EmbeddingGemmaEncoder];
 * everything that turns raw embeddings into a ranking is here.
 *
 * **Matryoshka (MRL).** EmbeddingGemma emits a 768-float vector whose dimensions are *nested*: the first
 * N carry a usable lower-dimensional embedding on their own. So a smaller dimension is a client-side
 * **slice of the leading N floats, re-normalised** — not a different model or a re-run. That makes the
 * 768/256/128 speed-vs-quality knob ([truncate]) ours to turn, independent of the encoder.
 *
 * **Cosine over normalised vectors.** Every stored/queried vector is L2-normalised, so cosine similarity
 * reduces to a dot product — sub-millisecond brute force over the whole 504-item corpus (the plan's
 * "don't reach for a vector DB" call).
 */
object EmbeddingMath {

    /**
     * The MRL dimension the retriever ships at (Decision C, measured on the Pixel Day 1). EmbeddingGemma
     * emits 768; truncating to 256 left the top-1 retrieval **identical** at 768/256/128 on the sample
     * corpus (e.g. "dragons" → Drogon over Batman held at every dimension), so 256 is the pick: 3× smaller
     * storage than 768 with clear headroom over 128 for the full 504-item corpus. Revisit if a fuller
     * benchmark shows 128 is safe.
     */
    const val SHIPPING_DIMENSION = 256


    /**
     * MRL truncation: keep the leading [dim] floats and re-normalise. [dim] ≥ the vector's length returns
     * an L2-normalised copy at full length (no truncation). A non-positive [dim] is a programming error.
     */
    fun truncate(vector: FloatArray, dim: Int): FloatArray {
        require(dim > 0) { "dim must be positive, was $dim" }
        val head = if (dim >= vector.size) vector else vector.copyOf(dim)
        return l2Normalize(head)
    }

    /** Returns an L2-normalised copy. A zero vector (norm 0) is returned unchanged rather than dividing by 0. */
    fun l2Normalize(vector: FloatArray): FloatArray {
        var sumSq = 0.0
        for (v in vector) sumSq += v.toDouble() * v
        val norm = sqrt(sumSq)
        if (norm == 0.0) return vector.copyOf()
        return FloatArray(vector.size) { (vector[it] / norm).toFloat() }
    }

    /**
     * Cosine similarity in [-1, 1]. Computed as a normalised dot product so it's correct even if the
     * inputs aren't pre-normalised; when they already are (the retrieval path), it's just the dot product.
     * Mismatched lengths are a programming error.
     */
    fun cosine(a: FloatArray, b: FloatArray): Float {
        require(a.size == b.size) { "vector length mismatch: ${a.size} vs ${b.size}" }
        var dot = 0.0
        var na = 0.0
        var nb = 0.0
        for (i in a.indices) {
            dot += a[i].toDouble() * b[i]
            na += a[i].toDouble() * a[i]
            nb += b[i].toDouble() * b[i]
        }
        val denom = sqrt(na) * sqrt(nb)
        return if (denom == 0.0) 0f else (dot / denom).toFloat()
    }

    /**
     * The [k] most similar corpus vectors to [query], most similar first, as `(id, score)` pairs. Brute
     * force over the whole corpus — trivial at 504 items. Pass vectors already truncated to the same
     * dimension; a length mismatch is a programming error surfaced by [cosine].
     */
    fun <T> topK(query: FloatArray, corpus: List<Pair<T, FloatArray>>, k: Int): List<Scored<T>> =
        corpus.asSequence()
            .map { (id, vec) -> Scored(id, cosine(query, vec)) }
            .sortedByDescending { it.score }
            .take(k)
            .toList()

    /** A corpus item paired with its similarity to a query — the ranked-retrieval unit. */
    data class Scored<T>(val id: T, val score: Float)
}
