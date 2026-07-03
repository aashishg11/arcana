package com.aashishgodambe.arcana.core.data.importer

import com.aashishgodambe.arcana.core.data.importer.model.ImportResult
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.LocalDate

/** Fast JVM tests for the parser transforms and lenient-skip behavior — no device needed. */
class HobbyDbCsvImporterTest {

    @Test
    fun unwrapHyperlink_extractsUrl() {
        assertEquals("https://x/1", HobbyDbCsvImporter.unwrapHyperlink("""=HYPERLINK("https://x/1")"""))
        assertEquals("https://plain", HobbyDbCsvImporter.unwrapHyperlink("https://plain"))
    }

    @Test
    fun splitMulti_trimsAndDropsBlanks() {
        assertEquals(listOf("A", "B", "C"), HobbyDbCsvImporter.splitMulti("A, B ,C"))
        assertEquals(emptyList<String>(), HobbyDbCsvImporter.splitMulti("   "))
    }

    @Test
    fun parseCents_handlesDollarsCommasAndBlanks() {
        assertEquals(2800, HobbyDbCsvImporter.parseCents("28.0"))
        assertEquals(123450, HobbyDbCsvImporter.parseCents("$1,234.50"))
        assertNull(HobbyDbCsvImporter.parseCents(""))
    }

    @Test
    fun parseLooseDate_handlesRaggedFormats() {
        assertEquals(LocalDate.of(2021, 6, 26), HobbyDbCsvImporter.parseLooseDate("2021-06-26"))
        assertEquals(LocalDate.of(2021, 8, 3), HobbyDbCsvImporter.parseLooseDate("2021/8/3"))
        assertEquals(LocalDate.of(2021, 10, 1), HobbyDbCsvImporter.parseLooseDate("2021/10"))
        assertEquals(LocalDate.of(2021, 1, 1), HobbyDbCsvImporter.parseLooseDate("2021"))
        assertNull(HobbyDbCsvImporter.parseLooseDate(""))
    }

    @Test
    fun parse_handlesQuirks_andSkipsBlankNameRow() {
        val csv = """
            HDBID,Name,Brand,Series,Exclusive To,UPC,Reference Number,Estimated Value,Quantity,Production Status,Image URL,Date Added To Collectible
            1,Daenerys,Funko,"Pop! Vinyl, Pop! Digital",NFT Redeemable,0889698686839,03,590.0,1,"Exclusive, Limited Edition","=HYPERLINK(""https://img/1.png"")",2026-06-26
            2,,Funko,Pop! Vinyl,Target,889698000001,10,14.0,1,Common,https://img/2.png,2024-01-01
            3,Groot,Funko,Pop! Marvel,,889698000002,20,33.0,2,Common,https://img/3.png,2023-05-05
        """.trimIndent()

        val result = HobbyDbCsvImporter.parse(csv.byteInputStream())
        assertTrue(result is ImportResult.Success)
        result as ImportResult.Success

        assertEquals(2, result.itemsParsed)
        assertEquals(1, result.itemsSkipped)
        assertEquals(1, result.warnings.size)

        val daenerys = result.items.first { it.name == "Daenerys" }
        assertEquals(listOf("Pop! Vinyl", "Pop! Digital"), daenerys.series)
        assertEquals(listOf("Exclusive", "Limited Edition"), daenerys.productionTags)
        assertEquals("https://img/1.png", daenerys.imageUrl) // =HYPERLINK stripped
        assertEquals("0889698686839", daenerys.funkoMetadata?.upc) // leading zero preserved
        assertEquals("03", daenerys.funkoMetadata?.popNumber) // leading zero preserved
        assertEquals(true, daenerys.funkoMetadata?.isNftRedeemable)
        assertEquals(59000, daenerys.estimatedValueCents)

        val groot = result.items.first { it.name == "Groot" }
        assertEquals(false, groot.funkoMetadata?.isNftRedeemable)
        assertEquals(2, groot.quantity)
    }
}
