package com.aashishgodambe.arcana.core.ai.cascade

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * JVM unit tests for [PopNumberParser], anchored on the real Day-2 OCR recon of the Aang #406 box:
 * numeric tokens were [406, 2025, 4, 1727, 36, 36, 36], with 406 rendered ~4.5x taller than the rest.
 */
class PopNumberParserTest {

    private fun line(text: String, height: Int) = RecognizedLine(text, BoundingBox(0, 0, 100, height))

    @Test
    fun picks_largest_number_from_real_box_noise() {
        val lines = listOf(
            line("406", 92),                                      // Pop number — visually largest
            line("-2025 SET4-", 19),                              // release year + set
            line("1727", 20),                                     // edition size
            line("PCS", 11),                                      // (edition unit)
            line("LEGENDARY", 30),
            line("A WARNING: CHOKING HAZARD, 36 months", 30),     // age warning
        )
        val result = PopNumberParser.parse(lines)
        assertEquals("406", result.best)
    }

    @Test
    fun explicit_hash_wins_regardless_of_size() {
        val lines = listOf(line("999", 200), line("#42", 10))
        assertEquals("42", PopNumberParser.parse(lines).best)
    }

    @Test
    fun drops_four_digit_release_year() {
        val lines = listOf(line("2025", 90), line("406", 40))
        assertEquals("406", PopNumberParser.parse(lines).best)
    }

    @Test
    fun ignores_numbers_inside_edition_and_warning_lines() {
        val lines = listOf(
            line("1727 PCS", 80),                                 // edition — dropped by context
            line("under 36 months", 80),                          // warning — dropped by context
            line("406", 40),
        )
        assertEquals("406", PopNumberParser.parse(lines).best)
    }

    @Test
    fun no_number_returns_null() {
        assertNull(PopNumberParser.parse(listOf(line("AANG", 50), line("METALLIC", 40))).best)
    }
}
