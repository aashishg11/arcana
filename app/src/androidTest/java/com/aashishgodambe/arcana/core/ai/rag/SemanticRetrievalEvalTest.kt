package com.aashishgodambe.arcana.core.ai.rag

import android.util.Log
import androidx.room.Room
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.aashishgodambe.arcana.core.data.database.ArcanaDatabase
import com.aashishgodambe.arcana.core.data.repository.CollectibleRepositoryImpl
import com.aashishgodambe.arcana.core.domain.model.Collectible
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Week-11 retrieval eval — the **semantic** accuracy suite. Runs the labeled fuzzy queries through the real
 * [HybridCollectionRetriever] over the **live** on-device index (the actual arcana.db the app built after
 * import), scoring top-1 and top-k(5) hits against ground truth. Needs EmbeddingGemma side-loaded AND the
 * index built — skips cleanly otherwise (same gate as the Ask feature). NOT reproducible from a clean
 * checkout (needs the gated model); that limit is stated honestly in EVAL_METHODOLOGY.md §3.
 *
 * A hit = any ground-truth token appears in the retrieved item's name OR its series (so a character-name
 * target like "Vhagar" and a broad-franchise target like "Marvel" are both scorable).
 */
@RunWith(AndroidJUnit4::class)
class SemanticRetrievalEvalTest {

    private val ctx = InstrumentationRegistry.getInstrumentation().targetContext
    private val db = Room.databaseBuilder(ctx, ArcanaDatabase::class.java, "arcana.db").build()
    private val embedder = EmbeddingGemmaEncoder(ctx.applicationContext)

    @After
    fun tearDown() = db.close()

    @Test
    fun scores_semantic_retrieval_over_the_live_index() = runBlocking {
        assumeTrue("EmbeddingGemma not side-loaded", embedder.isModelAvailable())
        val store = CollectionVectorStore(db.vectorDao())
        val indexed = store.count()
        assumeTrue("index not built (count=$indexed)", indexed > 0)

        val repo = CollectibleRepositoryImpl(
            db, db.collectibleDao(), db.funkoMetadataDao(), db.seriesDao(), db.valueSnapshotDao(),
        )
        val hybrid = HybridCollectionRetriever(StructuredRetriever(repo), SemanticRetriever(embedder, store, repo), repo)
        val fixtures = loadFixtures().filter { it.answerKind == "semantic" }
        assertTrue("no semantic fixtures loaded", fixtures.isNotEmpty())

        var top1 = 0
        var topk = 0
        val rows = mutableListOf<String>()
        val misses = mutableListOf<String>()

        for (f in fixtures) {
            val g = hybrid.retrieve(f.query)
            val routedSemantic = g.strategy == RetrievalStrategy.Semantic
            val targets = f.groundTruth.split("|").map { it.trim().lowercase() }
            val ranked = g.items.take(TOPK)
            val hit1 = routedSemantic && ranked.firstOrNull()?.let { matches(it, targets) } == true
            val hitK = routedSemantic && ranked.any { matches(it, targets) }
            if (hit1) top1++
            if (hitK) topk++
            if (!hitK) misses.add("  “${f.query}”  →  ${ranked.take(3).joinToString { it.name }}  ·  wanted ${f.groundTruth}")

            val mark = if (hit1) "OK " else if (hitK) "~k " else "XX"
            rows.add("  [$mark] ${f.query.padEnd(30)} #1=${ranked.firstOrNull()?.name?.padEnd(26) ?: "—"} want=${f.groundTruth}")
        }

        Log.i(TAG, "===== Semantic retrieval accuracy (Week-11 eval, N=${fixtures.size}, index=$indexed) =====")
        rows.forEach { Log.i(TAG, it) }
        Log.i(TAG, "----- misses / top-1-but-not-#1 detail (${misses.size} full misses) -----")
        if (misses.isEmpty()) Log.i(TAG, "  (none)") else misses.forEach { Log.i(TAG, it) }
        Log.i(TAG, "top-1: $top1/${fixtures.size} (${pct(top1, fixtures.size)})   top-k($TOPK): $topk/${fixtures.size} (${pct(topk, fixtures.size)})")

        // Loose regression floor; the deliverable is the logged top-1/top-k table.
        assertTrue(
            "semantic top-k collapsed to $topk/${fixtures.size} — investigate the index or embedder",
            topk.toDouble() / fixtures.size >= 0.70,
        )
    }

    private fun matches(item: Collectible, targets: List<String>): Boolean {
        val name = item.name.lowercase()
        val series = item.series.joinToString(" ").lowercase()
        return targets.any { it in name || it in series }
    }

    private data class Fixture(val query: String, val answerKind: String, val groundTruth: String)

    private fun loadFixtures(): List<Fixture> {
        val lines = InstrumentationRegistry.getInstrumentation().context.assets
            .open(QUERY_FIXTURE).bufferedReader().readLines()
            .map { it.trim() }
            .filter { it.isNotEmpty() && !it.startsWith("#") }
        return lines.drop(1).mapNotNull { line ->
            val c = line.split("\t")
            if (c.size < 4) return@mapNotNull null
            Fixture(c[0].trim(), c[2].trim(), c[3].trim())
        }
    }

    private fun pct(n: Int, d: Int) = if (d == 0) "n/a" else "%.0f%%".format(100.0 * n / d)

    private companion object {
        const val TAG = "SemanticEval"
        const val TOPK = 5
        const val QUERY_FIXTURE = "eval/query_fixtures.tsv"
    }
}
