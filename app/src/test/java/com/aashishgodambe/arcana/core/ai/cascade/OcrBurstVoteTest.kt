package com.aashishgodambe.arcana.core.ai.cascade

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * JVM tests for [OcrBurstVote] — the escalation-burst decision. Pins the two rules the camera relies on:
 * a corroborated primary read never bursts, and a burst resolves the Pop number by majority, preferring a
 * corroborated frame (modeling the Week-8 32→82 backlight misread being outvoted).
 */
class OcrBurstVoteTest {

    private fun layout(
        popNumber: String?,
        franchise: String? = null,
        character: String? = null,
    ) = BoxLayout(
        franchise = franchise,
        series = null,
        popNumber = popNumber,
        character = character,
        finish = null,
        rarityOrExclusive = null,
        editionSize = null,
    )

    @Test
    fun `number with franchise or character is corroborated`() {
        assertTrue(OcrBurstVote.isCorroborated(layout("32", franchise = "Popeye")))
        assertTrue(OcrBurstVote.isCorroborated(layout("406", character = "Aang")))
    }

    @Test
    fun `bare number or no number is not corroborated`() {
        assertFalse(OcrBurstVote.isCorroborated(layout("32")))
        assertFalse(OcrBurstVote.isCorroborated(layout(null, franchise = "Popeye")))
    }

    @Test
    fun `majority number wins the burst`() {
        val layouts = listOf(
            layout("82"),   // the backlight misread
            layout("32"),
            layout("32"),
            layout("32"),
        )
        assertEquals("32", layouts[OcrBurstVote.pick(layouts)].popNumber)
    }

    @Test
    fun `corroborated frame is preferred among frames sharing the winning number`() {
        val layouts = listOf(
            layout("32"),                       // bare
            layout("32", franchise = "Popeye"), // corroborated — should win the tie
        )
        val winner = layouts[OcrBurstVote.pick(layouts)]
        assertEquals("32", winner.popNumber)
        assertTrue(OcrBurstVote.isCorroborated(winner))
    }

    @Test
    fun `ties break to the first frame seen`() {
        val layouts = listOf(layout("32"), layout("82"))
        assertEquals("32", layouts[OcrBurstVote.pick(layouts)].popNumber)
    }

    @Test
    fun `all-null burst falls back to the primary frame`() {
        val layouts = listOf(layout(null), layout(null))
        assertEquals(0, OcrBurstVote.pick(layouts))
    }
}
