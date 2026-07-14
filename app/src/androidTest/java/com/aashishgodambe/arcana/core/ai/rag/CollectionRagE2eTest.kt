package com.aashishgodambe.arcana.core.ai.rag

import android.os.SystemClock
import android.util.Log
import androidx.room.Room
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.aashishgodambe.arcana.core.data.database.ArcanaDatabase
import com.aashishgodambe.arcana.core.data.database.entity.CollectibleCategory
import com.aashishgodambe.arcana.core.data.importer.model.FunkoImportMetadata
import com.aashishgodambe.arcana.core.data.importer.model.ImportedItem
import com.aashishgodambe.arcana.core.data.repository.CollectibleRepositoryImpl
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

/**
 * On-device end-to-end for the RAG index (skips when EmbeddingGemma isn't side-loaded): seed a small,
 * representative collection → embed it with the real model → persist to Room → retrieve by cosine. This
 * exercises what the JVM tests can't: real embeddings, the FloatArray↔BLOB round-trip through Room, and
 * that a **keyword-free** semantic query lands the right item end-to-end. Also measures per-item embed
 * time (the basis for the full-504 index estimate) and confirms the document-shape choice.
 */
@RunWith(AndroidJUnit4::class)
class CollectionRagE2eTest {

    private lateinit var db: ArcanaDatabase
    private lateinit var repo: CollectibleRepositoryImpl
    private lateinit var store: CollectionVectorStore
    private val embedder =
        EmbeddingGemmaEncoder(InstrumentationRegistry.getInstrumentation().targetContext.applicationContext)

    @Before
    fun setUp() {
        val ctx = InstrumentationRegistry.getInstrumentation().targetContext
        db = Room.inMemoryDatabaseBuilder(ctx, ArcanaDatabase::class.java).allowMainThreadQueries().build()
        repo = CollectibleRepositoryImpl(
            db, db.collectibleDao(), db.funkoMetadataDao(), db.seriesDao(), db.valueSnapshotDao(),
        )
        store = CollectionVectorStore(db.vectorDao())
    }

    @After
    fun tearDown() = db.close()

    private fun pop(name: String, number: String, series: List<String>, nft: Boolean = false) = ImportedItem(
        sourceId = number, sourceName = "test", listName = "Test", category = CollectibleCategory.Funko,
        name = name, brand = "Funko", quantity = 1, estimatedValueCents = 1000,
        itemCondition = "Mint", packagingCondition = "Mint", dateAdded = null, imageUrl = null,
        series = series, productionTags = emptyList(),
        funkoMetadata = FunkoImportMetadata("0$number", number, null, nft, null, null, null),
    )

    private val seed = listOf(
        pop("Daenerys Targaryen with Dragon Egg", "01", listOf("Game of Thrones"), nft = true),
        pop("Drogon", "02", listOf("Game of Thrones")),
        pop("Batman", "03", listOf("DC Super Heroes")),
        pop("Fire Nation Aang", "04", listOf("Avatar The Last Airbender")),
        pop("Deadpool", "05", listOf("Marvel")),
        pop("Freddy Funko as Popeye", "06", listOf("Popeye")),
    )

    @Test
    fun indexes_persists_and_retrieves_semantically_on_device() = runBlocking {
        assumeTrue("EmbeddingGemma not side-loaded — skipping", embedder.isModelAvailable())
        repo.importFrom(seed)

        val started = SystemClock.elapsedRealtime()
        val result = CollectionIndexer(repo, embedder, store).index()
        val ms = SystemClock.elapsedRealtime() - started
        Log.i(TAG, "indexed ${result.embedded} in ${ms}ms (~${ms / result.embedded}ms/item)")

        // Persisted through Room's BLOB converter (a broken round-trip would fail retrieval below).
        assertEquals(seed.size, store.count())
        assertEquals(seed.size, result.embedded)

        val names = repo.allCollectibles().associateBy { it.localId }
        val query = embedder.embedQuery("any pops with dragons")!!
        val top = store.topK(query, k = 3)
        Log.i(TAG, "dragons -> " + top.joinToString { "${names[it.id]?.name} ${"%.2f".format(it.score)}" })
        val winner = names[top.first().id]?.name.orEmpty()
        assertTrue("expected a dragon/GoT item on top, got '$winner'",
            winner.contains("Daenerys") || winner.contains("Drogon"))

        // Incremental: nothing changed → a second pass embeds nothing.
        assertEquals(0, CollectionIndexer(repo, embedder, store).index().embedded)
    }

    /**
     * Document-shape A/B at the retrieval level: for a labelled set of queries, how many does each shape
     * rank the right item #1? This is the honest form of "document shape > model choice" — single-item
     * cosine misleads (a bare name whose character implies the franchise can beat a scaffolded one), so we
     * measure top-1 accuracy over the whole set and pick the winner.
     */
    @Test
    fun compares_document_shapes_by_retrieval_quality() = runBlocking {
        assumeTrue("EmbeddingGemma not side-loaded — skipping", embedder.isModelAvailable())
        repo.importFrom(seed)
        val items = repo.allCollectibles()
        val dim = EmbeddingMath.SHIPPING_DIMENSION

        val queries = listOf(
            "any pops with dragons" to setOf("Daenerys", "Drogon"),
            "avatar the last airbender bender" to setOf("Aang"),
            "marvel mercenary anti-hero" to setOf("Deadpool"),
            "dc dark knight superhero" to setOf("Batman"),
            "spinach-eating cartoon sailor" to setOf("Popeye"),
        )
        val queryVecs = queries.map { EmbeddingMath.truncate(embedder.embedQuery(it.first)!!, dim) }

        val scores = CollectionDocument.Shape.values().associateWith { shape ->
            val docs = items.map { it.name to EmbeddingMath.truncate(embedder.embedDocument(shape.render(it))!!, dim) }
            queries.withIndex().count { (i, qp) ->
                val top = EmbeddingMath.topK(queryVecs[i], docs, k = 1).first().id
                qp.second.any { top.contains(it) }
            }
        }
        scores.forEach { (shape, top1) -> Log.i(TAG, "shape ${shape.name}: top-1 $top1/${queries.size}") }

        // The shipping default (of() → Natural) must retrieve at least as well as any other shape.
        val best = scores.values.max()
        val chosen = scores.getValue(CollectionDocument.Shape.Natural)
        Log.i(TAG, "chosen (Natural)=$chosen · best=$best")
        assertTrue("chosen shape $chosen should match the best $best", chosen >= best)
    }

    private companion object {
        const val TAG = "CollectionRagE2e"
    }
}
