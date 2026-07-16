package com.aashishgodambe.arcana.core.ai.rag

import android.util.Log
import androidx.room.Room
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.aashishgodambe.arcana.core.data.database.ArcanaDatabase
import com.aashishgodambe.arcana.core.data.importer.HobbyDbCsvImporter
import com.aashishgodambe.arcana.core.data.importer.model.ImportResult
import com.aashishgodambe.arcana.core.data.repository.CollectibleRepositoryImpl
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Week-11 retrieval eval — the **structured** accuracy suite. Reproducible: imports the committed 504-item
 * CSV into a fresh in-memory DB (no eBay snapshots → values are deterministic), then drives the labeled
 * structured queries through the REAL production seam ([HybridCollectionRetriever] → router →
 * [StructuredRetriever]) and scores each computed count against ground truth that was derived INDEPENDENTLY
 * of the retriever (tools/eval/derive_ground_truth.py; recorded in query_fixtures.tsv). No model needed —
 * the semantic path is wired with a null embedder and never taken for structured routes.
 *
 * Import fidelity itself (504 rows / 0 skipped / 141 NFT) is already covered by HobbyDbImportIntegrationTest;
 * this suite measures the retrieval LAYER on top of it. See EVAL_METHODOLOGY.md §1.2 / §5.
 */
@RunWith(AndroidJUnit4::class)
class StructuredRetrievalEvalTest {

    private lateinit var db: ArcanaDatabase
    private lateinit var hybrid: HybridCollectionRetriever

    /** The semantic path is never taken for structured queries; a null embedder keeps the suite model-free. */
    private val nullEmbedder = object : CollectionEmbedder {
        override val nativeDimension = 768
        override fun isModelAvailable() = false
        override suspend fun embedQuery(text: String): FloatArray? = null
        override suspend fun embedDocument(text: String): FloatArray? = null
    }

    @Before
    fun setUp() = runBlocking {
        val ctx = InstrumentationRegistry.getInstrumentation().targetContext
        db = Room.inMemoryDatabaseBuilder(ctx, ArcanaDatabase::class.java).allowMainThreadQueries().build()
        val repo = CollectibleRepositoryImpl(
            db, db.collectibleDao(), db.funkoMetadataDao(), db.seriesDao(), db.valueSnapshotDao(),
        )
        val csv = InstrumentationRegistry.getInstrumentation().context.assets.open(CSV_FIXTURE)
        val parsed = csv.use { HobbyDbCsvImporter.parse(it) } as ImportResult.Success
        repo.importFrom(parsed.items)
        val store = CollectionVectorStore(db.vectorDao())
        hybrid = HybridCollectionRetriever(
            StructuredRetriever(repo), SemanticRetriever(nullEmbedder, store, repo), repo,
        )
    }

    @After
    fun tearDown() = db.close()

    @Test
    fun scores_structured_retrieval_over_the_committed_collection() = runBlocking {
        val fixtures = loadFixtures().filter { it.answerKind == "structured" }
        assertTrue("no structured fixtures loaded", fixtures.isNotEmpty())

        var correct = 0
        val misses = mutableListOf<String>()
        val rows = mutableListOf<String>()

        for (f in fixtures) {
            val g = hybrid.retrieve(f.query)
            val routedStructured = g.strategy == RetrievalStrategy.Structured
            val fact = g.facts.firstOrNull().orEmpty()

            val (got, hit) = when (f.expectedRoute) {
                "MostValuable" -> {
                    val top = g.items.firstOrNull()?.name.orEmpty()
                    val expected = f.groundTruth.split("|").map { it.trim() }
                    top to (routedStructured && expected.any { it.equals(top, ignoreCase = true) })
                }
                else -> {
                    // Count / NftRedeemable / AddedInYear: the authoritative count is stated in the fact.
                    val n = COUNT_IN_FACT.find(fact)?.groupValues?.get(1)?.toIntOrNull()
                    val expected = f.groundTruth.toIntOrNull()
                    n?.toString().orEmpty() to (routedStructured && n != null && n == expected)
                }
            }
            if (hit) correct++ else misses.add("  “${f.query}”  →  got [$got] via ${g.strategy}  ·  expected ${f.groundTruth}")
            rows.add("  [${if (hit) "OK " else "XX"}] ${f.query.padEnd(38)} got=${got.padEnd(34)} want=${f.groundTruth}")
            // Value is deterministic on a fresh import (no snapshots); log for eyeballing, don't assert.
            VALUE_IN_FACT.find(fact)?.let { Log.i(TAG, "value  ${f.query} -> ${it.value}") }
        }

        Log.i(TAG, "===== Structured retrieval accuracy (Week-11 eval, N=${fixtures.size}) =====")
        rows.forEach { Log.i(TAG, it) }
        Log.i(TAG, "----- misses (${misses.size}) -----")
        if (misses.isEmpty()) Log.i(TAG, "  (none)") else misses.forEach { Log.i(TAG, it) }
        Log.i(TAG, "Structured accuracy: $correct/${fixtures.size} (${pct(correct, fixtures.size)})")

        assertTrue(
            "structured retrieval scored $correct/${fixtures.size} — investigate the misses above",
            correct == fixtures.size,
        )
    }

    private data class Fixture(
        val query: String,
        val expectedRoute: String,
        val answerKind: String,
        val groundTruth: String,
    )

    private fun loadFixtures(): List<Fixture> {
        val lines = InstrumentationRegistry.getInstrumentation().context.assets
            .open(QUERY_FIXTURE).bufferedReader().readLines()
            .map { it.trim() }
            .filter { it.isNotEmpty() && !it.startsWith("#") }
        return lines.drop(1).mapNotNull { line ->
            val c = line.split("\t")
            if (c.size < 4) return@mapNotNull null
            Fixture(c[0].trim(), c[1].trim(), c[2].trim(), c[3].trim())
        }
    }

    private fun pct(n: Int, d: Int) = if (d == 0) "n/a" else "%.0f%%".format(100.0 * n / d)

    private companion object {
        const val TAG = "StructuredEval"
        const val CSV_FIXTURE = "collectibles_2026-07-03.csv"
        const val QUERY_FIXTURE = "eval/query_fixtures.tsv"
        val COUNT_IN_FACT = Regex("You own (\\d+)")
        val VALUE_IN_FACT = Regex("total value \\$[\\d,]+")
    }
}
