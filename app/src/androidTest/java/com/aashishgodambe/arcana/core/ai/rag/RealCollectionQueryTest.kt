package com.aashishgodambe.arcana.core.ai.rag

import android.util.Log
import androidx.room.Room
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.aashishgodambe.arcana.core.data.database.ArcanaDatabase
import com.aashishgodambe.arcana.core.data.repository.CollectibleRepositoryImpl
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Ad-hoc probe (not a strict assertion) that runs real semantic queries against the **live** on-device
 * index — the actual `arcana.db` the app populated after import, not a seeded fixture. Skips unless the
 * index is built. Read the logcat to eyeball retrieval quality over the whole collection.
 */
@RunWith(AndroidJUnit4::class)
class RealCollectionQueryTest {

    private val ctx = InstrumentationRegistry.getInstrumentation().targetContext
    private val db = Room.databaseBuilder(ctx, ArcanaDatabase::class.java, "arcana.db").build()
    private val embedder = EmbeddingGemmaEncoder(ctx.applicationContext)

    @After
    fun tearDown() = db.close()

    @Test
    fun semantic_queries_over_the_live_collection() = runBlocking {
        assumeTrue("EmbeddingGemma not side-loaded", embedder.isModelAvailable())
        val store = CollectionVectorStore(db.vectorDao())
        val indexed = store.count()
        assumeTrue("index not built yet (count=$indexed)", indexed > 0)

        val repo = CollectibleRepositoryImpl(
            db, db.collectibleDao(), db.funkoMetadataDao(), db.seriesDao(), db.valueSnapshotDao(),
        )
        val names = repo.allCollectibles().associateBy { it.localId }
        Log.i(TAG, "index has $indexed vectors over ${names.size} items")

        for (q in QUERIES) {
            val qv = embedder.embedQuery(q) ?: continue
            val top = store.topK(qv, k = 5)
            Log.i(TAG, "\"$q\" → " + top.joinToString(" · ") { "${names[it.id]?.name ?: "#${it.id}"} ${"%.2f".format(it.score)}" })
        }
    }

    private companion object {
        const val TAG = "RealRagQuery"
        val QUERIES = listOf(
            "pops with dragons",
            "avatar the last airbender",
            "marvel superheroes",
            "spooky horror characters",
            "star wars",
        )
    }
}
