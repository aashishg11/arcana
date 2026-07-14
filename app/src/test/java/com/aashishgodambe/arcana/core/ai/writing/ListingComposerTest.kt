package com.aashishgodambe.arcana.core.ai.writing

import com.aashishgodambe.arcana.core.domain.model.FunkoPop
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.LocalDate

/** JVM tests for the raw listing text — the eBay-compliance boundary: only the item's own fields, no market data. */
class ListingComposerTest {

    private fun pop(
        name: String = "Fire Nation Aang", number: String? = "04", series: List<String> = listOf("Avatar The Last Airbender"),
        nft: Boolean = false, exclusive: String? = null, condition: String = "Mint", box: String = "Near Mint",
    ) = FunkoPop(
        localId = 1, name = name, brand = "Funko", imageUrl = null, estimatedValueCents = 5000,
        lastKnownValueCents = null, quantity = 1, itemCondition = condition, packagingCondition = box,
        series = series, productionTags = emptyList(), dateAdded = LocalDate.of(2023, 1, 1),
        pricePaidCents = null, storageLocation = null, upc = "0", popNumber = number,
        exclusiveTo = exclusive, isNftRedeemable = nft,
    )

    @Test
    fun `composes from the item's own identity and condition`() {
        val text = ListingComposer.compose(pop())
        assertTrue(text.contains("Fire Nation Aang"))
        assertTrue(text.contains("#04"))
        assertTrue(text.contains("Avatar The Last Airbender"))
        assertTrue(text.contains("figure Mint"))
        assertTrue(text.contains("box Near Mint"))
    }

    @Test
    fun `surfaces the NFT flag and an exclusive when present`() {
        val text = ListingComposer.compose(pop(nft = true, exclusive = "SDCC"))
        assertTrue(text.contains("NFT-redeemable"))
        assertTrue(text.contains("SDCC exclusive"))
    }

    @Test
    fun `never contains market or price data (eBay-compliance boundary)`() {
        // The composer takes only a FunkoPop — it has no access to eBay data — so the raw text can't leak
        // a price. Assert the obvious markers stay absent as a guard against a future field being added.
        val text = ListingComposer.compose(pop()).lowercase()
        assertFalse(text.contains("$"))
        assertFalse(text.contains("median"))
        assertFalse(text.contains("ebay"))
    }

    @Test
    fun `stays comfortably under the rewriting input budget`() {
        // Rewriting caps input at ~256 tokens; a terse descriptor is well under even for a wordy pop.
        val text = ListingComposer.compose(pop(name = "Daenerys Targaryen with Drogon and Three Dragon Eggs", series = listOf("Game of Thrones", "House of the Dragon")))
        assertTrue("words=${text.split(" ").size}", text.split(" ").size < 60)
    }
}
