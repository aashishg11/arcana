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
    fun `document folds in name, series and the NFT flag`() {
        val text = CollectionDocument.of(pop())
        assertTrue(text.contains("Daenerys Targaryen with Egg"))
        assertTrue(text.contains("Game of Thrones"))
        assertTrue(text.contains("Pop! Digital"))
        assertTrue(text.contains("NFT redeemable"))
    }

    @Test
    fun `document omits an empty series and a null exclusive`() {
        val text = CollectionDocument.of(pop(series = listOf("", "  "), nft = false, exclusive = null))
        assertFalse(text.contains("Series:"))
        assertFalse(text.contains("Exclusive:"))
        assertFalse(text.contains("NFT"))
    }

    @Test
    fun `document surfaces an exclusive when present`() {
        assertTrue(CollectionDocument.of(pop(exclusive = "SDCC")).contains("Exclusive: SDCC"))
    }
}
