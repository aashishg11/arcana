package com.aashishgodambe.arcana.core.ai.rag

import com.aashishgodambe.arcana.core.ai.rag.QueryRouter.Route
import com.aashishgodambe.arcana.core.data.repository.FakeCollectibleRepository
import com.aashishgodambe.arcana.core.domain.model.FunkoPop
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.LocalDate

/** JVM tests for the structured (SQL) retrieval path — the half that fixes counting. */
class StructuredRetrieverTest {

    private fun funko(
        id: Long, name: String, valueCents: Int, series: List<String> = emptyList(),
        nft: Boolean = false, year: Int = 2023, qty: Int = 1,
    ) = FunkoPop(
        localId = id, name = name, brand = "Funko", imageUrl = null,
        estimatedValueCents = valueCents, lastKnownValueCents = null, quantity = qty,
        itemCondition = "Mint", packagingCondition = "Mint", series = series, productionTags = emptyList(),
        dateAdded = LocalDate.of(year, 6, 1), pricePaidCents = null, storageLocation = null,
        upc = "0$id", popNumber = "$id", exclusiveTo = null, isNftRedeemable = nft,
    )

    private val collection = listOf(
        funko(1, "Spider-Man", 30_000, series = listOf("Marvel")),
        funko(2, "Iron Man", 40_000, series = listOf("Marvel"), nft = true),
        funko(3, "Thor", 50_000, series = listOf("Marvel"), year = 2022),
        funko(4, "Batman", 20_000, series = listOf("DC Super Heroes")),
        funko(5, "Superman", 25_000, series = listOf("DC Super Heroes"), nft = true),
    )

    private fun retriever() = StructuredRetriever(FakeCollectibleRepository(collection))

    @Test
    fun `count returns the full matching count and value, not a truncated list`() = runTest {
        val g = retriever().retrieve(Route.Count("marvel"))
        assertEquals(RetrievalStrategy.Structured, g.strategy)
        assertEquals(3, g.items.size)
        assertTrue(g.facts.single().contains("3 Pops matching \"marvel\""))
        assertTrue("value 30000+40000+50000 = \$1,200", g.facts.single().contains("$1,200"))
    }

    @Test
    fun `a blank-subject count is the whole collection`() = runTest {
        val g = retriever().retrieve(Route.Count(""))
        assertTrue(g.facts.single().contains("5 Pops in your collection"))
    }

    @Test
    fun `nft filter counts only the redeemable flag`() = runTest {
        val g = retriever().retrieve(Route.NftRedeemable)
        assertEquals(2, g.items.size)
        assertTrue(g.facts.single().contains("2 Pops that are NFT-redeemable"))
    }

    @Test
    fun `added-in-year filters by acquisition year`() = runTest {
        val g = retriever().retrieve(Route.AddedInYear(2022))
        assertEquals(listOf("Thor"), g.items.map { it.name })
        assertTrue(g.facts.single().contains("1 Pop added in 2022"))
    }

    @Test
    fun `most valuable grounds on the top items with no count fact`() = runTest {
        val g = retriever().retrieve(Route.MostValuable)
        assertEquals("Thor", g.items.first().name)
        assertTrue(g.facts.isEmpty())
    }

    @Test
    fun `duplicate copies count toward value but not the entry count`() = runTest {
        val withDup = StructuredRetriever(FakeCollectibleRepository(listOf(funko(9, "Groot", 10_000, series = listOf("Marvel"), qty = 3))))
        val g = withDup.retrieve(Route.Count("marvel"))
        assertTrue(g.facts.single().contains("1 Pop matching")) // one entry
        assertTrue(g.facts.single().contains("(3 incl. duplicates)")) // three copies
        assertTrue(g.facts.single().contains("$300")) // 10000c * 3
    }
}
