package com.aashishgodambe.arcana.core.ai.catalog

import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Test

class CatalogProviderChainTest {

    private fun entry(source: String, confidence: Float) =
        CatalogEntry(source, "id", "Name", null, emptyList(), "1", null, null, confidence)

    private fun provider(name: String, result: CatalogEntry?, onCalled: () -> Unit = {}) =
        object : CatalogProvider {
            override val sourceName = name
            override suspend fun lookup(query: CatalogQuery): CatalogEntry? {
                onCalled(); return result
            }
        }

    private val anyQuery = CatalogQuery(popNumber = "1", franchise = null, character = null)

    @Test
    fun short_circuits_on_first_confident_hit_without_calling_later_providers() = runBlocking {
        var laterCalled = false
        val chain = CatalogProviderChain(
            listOf(
                provider("Your collection", entry("Your collection", 0.95f)),
                provider("Cloud", entry("Cloud", 0.99f)) { laterCalled = true },
            ),
        )
        val result = chain.identify(anyQuery)
        assertEquals("Your collection", result!!.sourceName)
        assertFalse("a confident hit must not trigger later (network) providers", laterCalled)
    }

    @Test
    fun falls_through_low_confidence_and_returns_the_best() = runBlocking {
        val chain = CatalogProviderChain(
            listOf(
                provider("A", entry("A", 0.50f)),
                provider("B", entry("B", 0.65f)),
            ),
        )
        // Neither clears the 0.7 threshold, so the highest-confidence best-effort wins.
        assertEquals("B", chain.identify(anyQuery)!!.sourceName)
    }

    @Test
    fun a_failing_provider_is_skipped_not_fatal() = runBlocking {
        val chain = CatalogProviderChain(
            listOf(
                object : CatalogProvider {
                    override val sourceName = "Boom"
                    override suspend fun lookup(query: CatalogQuery): CatalogEntry? = throw RuntimeException("network down")
                },
                provider("B", entry("B", 0.9f)),
            ),
        )
        assertEquals("B", chain.identify(anyQuery)!!.sourceName)
    }

    @Test
    fun returns_null_when_no_provider_matches() = runBlocking {
        val chain = CatalogProviderChain(listOf(provider("A", null), provider("B", null)))
        assertNull(chain.identify(anyQuery))
    }
}
