package com.aashishgodambe.arcana.core.ai.rag

import com.aashishgodambe.arcana.core.data.repository.FakeCollectibleRepository
import com.aashishgodambe.arcana.core.domain.model.Collectible
import com.aashishgodambe.arcana.core.domain.model.FunkoPop
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.LocalDate

/** JVM tests for the hybrid router seam — that questions reach the right path and degrade to lexical. */
class HybridCollectionRetrieverTest {

    private fun funko(id: Long, name: String, series: List<String>) = FunkoPop(
        localId = id, name = name, brand = "Funko", imageUrl = null,
        estimatedValueCents = 10_000, lastKnownValueCents = null, quantity = 1,
        itemCondition = "Mint", packagingCondition = "Mint", series = series, productionTags = emptyList(),
        dateAdded = LocalDate.of(2023, 1, 1), pricePaidCents = null, storageLocation = null,
        upc = "0$id", popNumber = "$id", exclusiveTo = null, isNftRedeemable = false,
    )

    private val collection = listOf(
        funko(1, "Daenerys Targaryen", listOf("Game of Thrones")),
        funko(2, "Iron Man", listOf("Marvel")),
        funko(3, "Thor", listOf("Marvel")),
    )

    private suspend fun hybrid(embedderAvailable: Boolean = true): HybridCollectionRetriever {
        val repo = FakeCollectibleRepository(collection)
        val embedder = FakeCollectionEmbedder(available = embedderAvailable)
        val store = CollectionVectorStore(FakeVectorDao())
        if (embedderAvailable) {
            collection.forEach { store.upsert(it.localId, embedder.embedDocument(CollectionDocument.of(it))!!, 0) }
        }
        return HybridCollectionRetriever(StructuredRetriever(repo), SemanticRetriever(embedder, store, repo), repo)
    }

    @Test
    fun `a count question routes to structured with an authoritative fact`() = runTest {
        val g = hybrid().retrieve("how many Marvel do I own?")
        assertEquals(RetrievalStrategy.Structured, g.strategy)
        assertTrue(g.facts.single().contains("2 Pops matching \"marvel\""))
    }

    @Test
    fun `a fuzzy question routes to semantic when the embedder is available`() = runTest {
        val g = hybrid(embedderAvailable = true).retrieve("game of thrones")
        assertEquals(RetrievalStrategy.Semantic, g.strategy)
        assertTrue(g.items.any { it.name.contains("Daenerys") })
    }

    @Test
    fun `a fuzzy question degrades to lexical when the embedder is absent`() = runTest {
        val g = hybrid(embedderAvailable = false).retrieve("game of thrones")
        assertEquals(RetrievalStrategy.Lexical, g.strategy)
        assertTrue(g.items.any { it.name.contains("Daenerys") })
    }

    @Test
    fun `a back-reference routes to follow-up with no items`() = runTest {
        val g = hybrid().retrieve("tell me more")
        assertEquals(RetrievalStrategy.FollowUp, g.strategy)
        assertTrue(g.items.isEmpty())
    }
}
