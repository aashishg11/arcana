package com.aashishgodambe.arcana.core.ai.rag

import com.aashishgodambe.arcana.core.domain.model.FunkoPop
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.LocalDate

/** JVM tests for the pure document rendering + EmbeddingGemma task prefixes. */
class CollectionDocumentTest {

    private fun pop(
        name: String = "Daenerys Targaryen with Egg",
        series: List<String> = listOf("Game of Thrones", "Pop! Digital"),
        nft: Boolean = true,
        exclusive: String? = null,
    ) = FunkoPop(
        localId = 1,
        name = name,
        brand = "Funko",
        imageUrl = null,
        estimatedValueCents = 69000,
        lastKnownValueCents = null,
        quantity = 1,
        itemCondition = "Mint",
        packagingCondition = "Mint",
        series = series,
        productionTags = emptyList(),
        dateAdded = LocalDate.of(2023, 1, 1),
        pricePaidCents = null,
        storageLocation = null,
        upc = "889698000000",
        popNumber = "01",
        exclusiveTo = exclusive,
        isNftRedeemable = nft,
    )

    @Test
    fun `query and document prefixes match EmbeddingGemma's retrieval prompts`() {
        assertEquals("task: search result | query: any dragons?", CollectionDocument.queryPrompt("  any dragons? "))
        assertEquals("title: none | text: Deadpool", CollectionDocument.documentPrompt("Deadpool"))
    }

    @Test
    fun `default document uses the natural shape woven with the franchise`() {
        val text = CollectionDocument.of(pop())
        assertTrue(text.contains("Daenerys Targaryen with Egg"))
        assertTrue(text.contains("from Game of Thrones, Pop! Digital"))
        assertTrue(text.contains("NFT-redeemable Funko Pop"))
        assertFalse("natural shape has no label scaffolding", text.contains("Series:"))
    }

    @Test
    fun `bare-name shape is just the name`() {
        assertEquals("Daenerys Targaryen with Egg", CollectionDocument.Shape.BareName.render(pop()))
    }

    @Test
    fun `labelled shape keeps the Series label`() {
        val text = CollectionDocument.Shape.Labelled.render(pop())
        assertTrue(text.contains("Series: Game of Thrones, Pop! Digital"))
        assertTrue(text.contains("NFT redeemable"))
    }

    @Test
    fun `shapes omit an empty series`() {
        val bare = pop(series = listOf("", "  "), nft = false)
        assertFalse(CollectionDocument.Shape.Natural.render(bare).contains("from"))
        assertFalse(CollectionDocument.Shape.Labelled.render(bare).contains("Series:"))
    }
}
