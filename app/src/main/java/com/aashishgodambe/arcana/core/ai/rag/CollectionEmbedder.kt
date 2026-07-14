package com.aashishgodambe.arcana.core.ai.rag

/**
 * The seam the RAG retriever crosses to turn text into a vector. One impl runs EmbeddingGemma on the
 * LiteRT interpreter ([EmbeddingGemmaEncoder]); a fake backs JVM tests. Like [com.aashishgodambe.arcana
 * .core.ai.OwnModelEngine], the model is **side-loaded and may be absent** — [isModelAvailable] presence-
 * gates it so Ask can fall back cleanly to lexical retrieval when the embedder isn't installed.
 *
 * **Query vs document asymmetry.** EmbeddingGemma is trained with task-specific prompt prefixes, and a
 * search query embeds under a *different* prefix than the documents it's matched against. Collapsing the
 * two measurably hurts retrieval, so the asymmetry is part of the contract: [embedQuery] for the user's
 * question, [embedDocument] for a collection item. Both return a raw native-dimension vector; MRL
 * truncation to the shipping dimension is applied by the caller via [EmbeddingMath.truncate].
 */
interface CollectionEmbedder {

    /** The encoder's native output dimension (768 for EmbeddingGemma), before any MRL truncation. */
    val nativeDimension: Int

    /** True when the side-loaded model is present and readable — the gate Ask checks before using vectors. */
    fun isModelAvailable(): Boolean

    /** Embed the user's search query (retrieval "query" prefix). Null if the model is unavailable or fails. */
    suspend fun embedQuery(text: String): FloatArray?

    /** Embed a collection-item descriptor (retrieval "document" prefix). Null if unavailable or on failure. */
    suspend fun embedDocument(text: String): FloatArray?
}
