package com.aashishgodambe.arcana.core.ai.catalog

import com.aashishgodambe.arcana.core.data.repository.FakeCollectibleRepository
import com.aashishgodambe.arcana.testFunko
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * JVM tests for [LocalCollectionCatalogProvider], seeded with a slice of the user's real collection
 * (Avatar Digital pops it owns) so the tricky cases are exercised: an owned hit, the Popeye #32-vs-owned
 * #30 miss, and a same-number-but-wrong-franchise coincidence.
 */
class LocalCollectionCatalogProviderTest {

    private val collection = listOf(
        testFunko(52, name = "Fire Nation Aang", popNumber = "52", upc = "889698685146",
            series = listOf("Pop! Digital", "Avatar Legends x Funko Series 1")),
        testFunko(30, name = "Popeye With Swee'Pea", popNumber = "30",
            series = listOf("Pop! Digital", "Series 1")),
        testFunko(56, name = "Freddy Funko as Aang", popNumber = "56",
            series = listOf("Pop! Digital", "Avatar Legends x Funko Series 1")),
    )
    private val provider = LocalCollectionCatalogProvider(FakeCollectibleRepository(collection))

    @Test
    fun identifies_owned_pop_by_number_and_context() = runBlocking {
        val match = provider.lookup(
            CatalogQuery(popNumber = "52", franchise = "Avatar", character = "Fire Nation Aang"),
        )
        assertNotNull(match)
        assertEquals(52L, match!!.matchedLocalId)
        assertEquals("Fire Nation Aang", match.name)
        assertTrue("confidence was ${match.confidence}", match.confidence >= 0.9f)
    }

    @Test
    fun does_not_snap_unowned_32_to_owned_30() = runBlocking {
        val match = provider.lookup(
            CatalogQuery(popNumber = "32", franchise = "Popeye", character = "Freddy Funko"),
        )
        assertNull("#32 is unowned — must escalate, not match #30", match)
    }

    @Test
    fun rejects_number_coincidence_when_context_contradicts() = runBlocking {
        // #52 is owned, but it's Avatar / Fire Nation Aang — not Popeye / Freddy Funko.
        val match = provider.lookup(
            CatalogQuery(popNumber = "52", franchise = "Popeye", character = "Freddy Funko"),
        )
        assertNull(match)
    }

    @Test
    fun matches_number_only_at_medium_confidence() = runBlocking {
        val match = provider.lookup(CatalogQuery(popNumber = "52", franchise = null, character = null))
        assertNotNull(match)
        assertEquals(52L, match!!.matchedLocalId)
        assertEquals(0.6f, match.confidence, 0.001f)
    }

    @Test
    fun returns_null_without_a_pop_number() = runBlocking {
        assertNull(provider.lookup(CatalogQuery(popNumber = null, franchise = "Avatar", character = "Aang")))
    }

    @Test
    fun matches_by_upc_for_the_barcode_path() = runBlocking {
        // EAN-13 form (leading zero) must still match the stored UPC-A "889698685146".
        val match = provider.lookup(CatalogQuery(popNumber = null, franchise = null, character = null, upc = "0889698685146"))
        assertNotNull(match)
        assertEquals(52L, match!!.matchedLocalId)
        assertTrue("UPC is a strong key", match.confidence >= 0.9f)
    }
}
