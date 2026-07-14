package com.aashishgodambe.arcana.core.ai.rag

import com.aashishgodambe.arcana.core.ai.rag.QueryRouter.Route
import org.junit.Assert.assertEquals
import org.junit.Test

/** JVM tests for the rules-based hybrid-retrieval router — the classifier must be inspectable and defensible. */
class QueryRouterTest {

    @Test
    fun `counts route to the structured path with a cleaned subject`() {
        assertEquals(Route.Count("marvel"), QueryRouter.classify("how many Marvel do I own?"))
        assertEquals(Route.Count("star wars"), QueryRouter.classify("how many Star Wars pops do I have"))
        assertEquals(Route.Count("marvel"), QueryRouter.classify("how much are my Marvel worth"))
    }

    @Test
    fun `a bare count with no subject means the whole collection`() {
        assertEquals(Route.Count(""), QueryRouter.classify("how many pops do I own"))
    }

    @Test
    fun `ranking questions route to most-valuable`() {
        assertEquals(Route.MostValuable, QueryRouter.classify("what's my most valuable item?"))
        assertEquals(Route.MostValuable, QueryRouter.classify("which pop is worth the most"))
        assertEquals(Route.MostValuable, QueryRouter.classify("show my most expensive pops"))
    }

    @Test
    fun `nft flag questions route to the boolean filter, not a name keyword`() {
        assertEquals(Route.NftRedeemable, QueryRouter.classify("which are NFT redeemable"))
        assertEquals(Route.NftRedeemable, QueryRouter.classify("show my nft pops"))
    }

    @Test
    fun `temporal questions extract the acquisition year`() {
        assertEquals(Route.AddedInYear(2023), QueryRouter.classify("what did I add in 2023"))
        assertEquals(Route.AddedInYear(2022), QueryRouter.classify("pops from 2022"))
    }

    @Test
    fun `a year inside a title without temporal words is not treated as a date`() {
        // No "added/from/in…" cue → not a date filter; falls through to semantic.
        assertEquals(Route.Semantic("Blade Runner 2049"), QueryRouter.classify("Blade Runner 2049"))
    }

    @Test
    fun `back-references and 'more' route to follow-up`() {
        assertEquals(Route.FollowUp, QueryRouter.classify("tell me more"))
        assertEquals(Route.FollowUp, QueryRouter.classify("what are they worth"))
        assertEquals(Route.FollowUp, QueryRouter.classify("more"))
    }

    @Test
    fun `fuzzy meaning questions route to semantic`() {
        assertEquals(Route.Semantic("any pops with dragons?"), QueryRouter.classify("any pops with dragons?"))
        assertEquals(Route.Semantic("the one with the crown"), QueryRouter.classify("the one with the crown"))
        assertEquals(Route.Semantic("sci-fi pops"), QueryRouter.classify("sci-fi pops"))
    }
}
