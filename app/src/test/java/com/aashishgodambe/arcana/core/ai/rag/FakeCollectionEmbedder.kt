package com.aashishgodambe.arcana.core.ai.rag

/**
 * Device-free [CollectionEmbedder] for JVM tests. Deterministic **bag-of-tokens** embeddings: each token
 * hashes to a fixed dimension, so two texts that share tokens have a real, non-trivial cosine — enough to
 * exercise the retriever/router without EmbeddingGemma or a device. Query/document prefixes are stripped
 * before hashing so a query embeds near a document it shares words with (mirroring EmbeddingGemma's
 * cross-prefix retrieval, without modelling the true semantic asymmetry).
 *
 * [available] flips the presence gate so tests can drive both the vector path and the lexical fallback.
 * [overrides] pins exact vectors for specific texts when a test needs a controlled semantic relationship
 * that token overlap alone won't produce (e.g. "dragons" ↔ "Daenerys").
 */
class FakeCollectionEmbedder(
    override val nativeDimension: Int = 64,
    var available: Boolean = true,
    private val overrides: Map<String, FloatArray> = emptyMap(),
) : CollectionEmbedder {

    override fun isModelAvailable(): Boolean = available

    override suspend fun embedQuery(text: String): FloatArray? = embed(strip(text))

    override suspend fun embedDocument(text: String): FloatArray? = embed(strip(text))

    private fun embed(text: String): FloatArray? {
        if (!available) return null
        overrides[text]?.let { return it.copyOf() }
        val vec = FloatArray(nativeDimension)
        for (token in tokenize(text)) {
            // Two hashes per token spread signal across dimensions so distinct tokens rarely fully collide.
            vec[floorMod(token.hashCode(), nativeDimension)] += 1f
            vec[floorMod(token.hashCode() * 31 + 7, nativeDimension)] += 0.5f
        }
        return EmbeddingMath.l2Normalize(vec)
    }

    /** Drop the EmbeddingGemma task prefixes the encoder would add, matching on the visible content. */
    private fun strip(text: String): String =
        text.substringAfter("query: ", text).substringAfter("text: ", text)

    private fun tokenize(text: String): List<String> =
        text.lowercase().split(Regex("[^a-z0-9]+")).filter { it.isNotBlank() }

    private fun floorMod(x: Int, m: Int): Int = ((x % m) + m) % m
}
