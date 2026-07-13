package com.aashishgodambe.arcana.core.ai.cascade

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class CascadeHintFusionTest {

    private val aangLayout = BoxLayout(
        franchise = "AVATAR",
        series = "Pop! Digital",
        popNumber = "406",
        character = "AANG ARMOR",
        finish = "Metallic",
        rarityOrExclusive = "Legendary",
        editionSize = "1727",
    )

    @Test
    fun ocr_layout_wins_over_llm_labels() {
        // Nano swapped character/franchise; fusion must keep the OCR positional values.
        val llm = LlmBoxRead(character = "Popeye", franchise = "Freddy Funko", number = "406")
        val q = CascadeHintFusion.toQuery(aangLayout, llm)
        assertEquals("406", q.popNumber)
        assertEquals("AVATAR", q.franchise)
        assertEquals("AANG ARMOR", q.character)
        assertEquals("Pop! Digital", q.series)
        assertEquals("Metallic", q.finish)
    }

    @Test
    fun llm_fills_gaps_the_ocr_missed() {
        val sparseLayout = BoxLayout(
            franchise = null, series = null, popNumber = null,
            character = null, finish = null, rarityOrExclusive = null, editionSize = null,
        )
        val llm = LlmBoxRead(character = "Aang", franchise = "Avatar", number = "406", series = "Digital", rawText = "a masked figure")
        val q = CascadeHintFusion.toQuery(sparseLayout, llm)
        assertEquals("406", q.popNumber)
        assertEquals("Avatar", q.franchise)
        assertEquals("Aang", q.character)
        assertEquals("Digital", q.series)
        assertEquals("a masked figure", q.descriptionHints)
    }

    @Test
    fun carries_upc_and_survives_a_null_llm() {
        val q = CascadeHintFusion.toQuery(aangLayout, llm = null, upc = "889698685146")
        assertEquals("889698685146", q.upc)
        assertEquals("406", q.popNumber)
        assertNull(q.descriptionHints)
    }
}
