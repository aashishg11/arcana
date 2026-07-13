package com.aashishgodambe.arcana.core.ai.cascade

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * JVM tests for [BoxLayoutParser], built from the *actual* ML Kit OCR line sets (text + y-geometry)
 * captured on real photos of both boxes, so the positional heuristics are pinned to reality — including
 * the noisy cases: Aang's separate franchise/number vs Popeye's merged "eg POPEYE 32", and PCS->PGS.
 */
class BoxLayoutParserTest {

    // left/right are irrelevant to the parser; only top/bottom (height + vertical position) matter.
    private fun line(text: String, top: Int, bottom: Int) = RecognizedLine(text, BoundingBox(0, top, 100, bottom))

    private val aang = listOf(
        line("enicelodeon", 83, 107),
        line("406", 92, 184),
        line("AVATAR", 114, 160),
        line("h TH€ LAST AIRBENDER", 158, 178),
        line("Digital", 211, 233),
        line("LEGENDARY", 764, 794),
        line("-2025 SET4-", 787, 806),
        line("1727", 815, 835),
        line("PCS", 840, 851),
        line("Ww", 865, 886),
        line("AANG ARMOR METALLIC", 933, 1005),
        line("WITH", 942, 961),
        line("VINYL FIGURE / FIGURINE EN VINYLE", 996, 1021),
        line("FIGURA DE VINIL", 1024, 1043),
        line("A WARNING: CHOKNG HAZARD, DANGER 36 mois", 1048, 1078),
    )

    private val popeye = listOf(
        line("eg POPEYE 32", 469, 666),
        line("Digital", 647, 677),
        line("INA H", 1167, 1201),
        line("**NFT**", 1188, 1211),
        line("RELEASE", 1213, 1238),
        line("-3198", 1252, 1283),
        line("PGS", 1273, 1293),
        line("FREDDY FUNKO", 1413, 1508),
        line("VINYL FIGURE / FIGURINE EN VINYLE", 1492, 1533),
        line("FIGURA DE VINIL", 1528, 1559),
        line("A WARNNG HAZARO 36 mois", 1539, 1608),
    )

    @Test
    fun parses_aang_box_by_position() {
        val r = BoxLayoutParser.parse(aang)
        assertEquals("AVATAR", r.franchise)
        assertEquals("Pop! Digital", r.series)
        assertEquals("406", r.popNumber)
        assertTrue("character was ${r.character}", r.character!!.contains("AANG"))
        assertFalse("finish should be split out of character", r.character!!.contains("METALLIC", ignoreCase = true))
        assertEquals("Metallic", r.finish)
        assertEquals("Legendary", r.rarityOrExclusive)
        assertEquals("1727", r.editionSize)
    }

    @Test
    fun parses_popeye_box_despite_merged_franchise_and_pcs_typo() {
        val r = BoxLayoutParser.parse(popeye)
        assertEquals("POPEYE", r.franchise)               // extracted from merged "eg POPEYE 32"
        assertEquals("Pop! Digital", r.series)
        assertEquals("32", r.popNumber)
        assertTrue("character was ${r.character}", r.character!!.contains("FREDDY"))
        assertNull("Popeye #32 has no finish tag", r.finish)
        assertTrue("rarity/excl was ${r.rarityOrExclusive}", r.rarityOrExclusive!!.contains("NFT"))
        assertEquals("3198", r.editionSize)               // nearest number to the PGS(=PCS) token
    }
}
