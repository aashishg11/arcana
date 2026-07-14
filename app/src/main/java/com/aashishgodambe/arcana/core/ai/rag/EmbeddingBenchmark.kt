package com.aashishgodambe.arcana.core.ai.rag

import android.os.SystemClock
import android.util.Log

/**
 * The Day-1 on-device probe for the embedder: proves EmbeddingGemma embeds on the Pixel, measures cold vs
 * warm latency, and produces the **dimension-benchmark** evidence for Decision C — does a keyword-free
 * semantic query still land the right item when the 768-dim vector is MRL-truncated to 256 or 128?
 *
 * The sample docs are collection archetypes chosen so the query has **no lexical overlap** with the right
 * answer ("dragons" must reach *Drogon*, whose descriptor never says "dragon"), which is exactly what
 * lexical retrieval can't do and semantic search can. Run from the debug Settings harness.
 */
object EmbeddingBenchmark {

    private data class Doc(val label: String, val text: String)

    private val docs = listOf(
        Doc("Daenerys", "Daenerys Targaryen with Dragon Egg. Series: Game of Thrones"),
        Doc("Drogon", "Drogon. Series: Game of Thrones"),
        Doc("Batman", "Batman. Series: DC Super Heroes"),
        Doc("Aang", "Fire Nation Aang. Series: Avatar The Last Airbender"),
        Doc("Deadpool", "Deadpool. Series: Marvel"),
        Doc("Popeye", "Freddy Funko as Popeye. Series: Popeye"),
    )

    private val queries = listOf("any pops with dragons?", "caped crime-fighting superheroes")
    private val dims = listOf(768, 256, 128)

    suspend fun run(embedder: CollectionEmbedder): String {
        if (!embedder.isModelAvailable()) {
            return "Not installed — side-load embeddinggemma-300m.tflite + sentencepiece.model into files/models/"
        }

        // Cold vs warm latency on the same short document.
        val coldStart = SystemClock.elapsedRealtime()
        val warmup = embedder.embedDocument(docs.first().text)
            ?: return "Embed returned null — check the model/tokenizer files"
        val coldMs = SystemClock.elapsedRealtime() - coldStart
        val warmStart = SystemClock.elapsedRealtime()
        embedder.embedDocument(docs.first().text)
        val warmMs = SystemClock.elapsedRealtime() - warmStart

        val docVectors = docs.map { it to embedder.embedDocument(it.text) }
            .mapNotNull { (d, v) -> v?.let { d to it } }

        val lines = queries.map { q ->
            val qv = embedder.embedQuery(q) ?: return@map "Q \"$q\": embed failed"
            val perDim = dims.joinToString("  ") { dim ->
                val ranked = EmbeddingMath.topK(
                    EmbeddingMath.truncate(qv, dim),
                    docVectors.map { (d, v) -> d.label to EmbeddingMath.truncate(v, dim) },
                    k = 1,
                )
                val top = ranked.firstOrNull()
                "$dim→${top?.id ?: "?"}(${"%.2f".format(top?.score ?: 0f)})"
            }
            "Q \"${q.take(20)}\": $perDim"
        }

        return buildString {
            append("dim ${warmup.size} · cold ${coldMs}ms · warm ${warmMs}ms\n")
            append(lines.joinToString("\n"))
        }.also { Log.i(TAG, "\n$it") }
    }

    private const val TAG = "EmbeddingBench"
}
