package com.aashishgodambe.arcana.core.ai.rag

import android.util.Log
import androidx.room.Room
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.aashishgodambe.arcana.core.data.database.ArcanaDatabase
import com.aashishgodambe.arcana.core.data.repository.CollectibleRepositoryImpl
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.junit.runner.RunWith

/**
 * On-device end-to-end for the hybrid retriever over the **live** collection: the whole point of Day 3 is
 * that a counting question is answered by SQL (the real total) while a fuzzy one is answered by vectors.
 * Skips unless the index is built. Read logcat to see the routed answers.
 */
@RunWith(AndroidJUnit4::class)
class HybridRetrieverE2eTest {

    private val ctx = InstrumentationRegistry.getInstrumentation().targetContext
    private val db = Room.databaseBuilder(ctx, ArcanaDatabase::class.java, "arcana.db").build()
    private val embedder = EmbeddingGemmaEncoder(ctx.applicationContext)

    @After
    fun tearDown() = db.close()

    private fun hybrid(): HybridCollectionRetriever {
        val repo = CollectibleRepositoryImpl(
            db, db.collectibleDao(), db.funkoMetadataDao(), db.seriesDao(), db.valueSnapshotDao(),
        )
        val store = CollectionVectorStore(db.vectorDao())
        return HybridCollectionRetriever(StructuredRetriever(repo), SemanticRetriever(embedder, store, repo), repo)
    }

    @Test
    fun routes_counts_to_sql_and_fuzzy_to_vectors() = runBlocking {
        assumeTrue("EmbeddingGemma not side-loaded", embedder.isModelAvailable())
        val store = CollectionVectorStore(db.vectorDao())
        assumeTrue("index not built", store.count() > 0)
        val hybrid = hybrid()

        for (q in listOf(
            "how many Marvel do I own?",
            "how many pops do I own",
            "which are NFT redeemable",
            "what's my most valuable item?",
            "any pops with dragons?",
            "spooky horror characters",
        )) {
            val g = hybrid.retrieve(q)
            val detail = if (g.facts.isNotEmpty()) g.facts.first()
            else g.items.take(3).joinToString(", ") { it.name }
            Log.i(TAG, "[$q] → ${g.strategy}: $detail")
        }

        // The two DoD anchors: a count is structured (a real total), "dragons" is semantic.
        val count = hybrid.retrieve("how many Marvel do I own?")
        assertEquals(RetrievalStrategy.Structured, count.strategy)
        assertTrue("count fact should be present", count.facts.isNotEmpty())

        val dragons = hybrid.retrieve("any pops with dragons?")
        assertEquals(RetrievalStrategy.Semantic, dragons.strategy)
        assertTrue("semantic should return items", dragons.items.isNotEmpty())
    }

    private companion object {
        const val TAG = "HybridRagE2e"
    }
}
