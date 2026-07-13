package com.aashishgodambe.arcana.core.ai.cascade

/**
 * Picks the Funko Pop number out of noisy OCR. Real boxes rarely print a '#', and several numbers
 * compete — release year (2025), edition size (1727 PCS), age warnings (36 months). Day-2 recon on the
 * Aang #406 box showed the Pop number is the *visually largest* number (its line ~4.5x taller than any
 * other), so the ranking is:
 *   1. an explicit `#NNN` anywhere wins (unambiguous), else
 *   2. numeric lines ranked by height, after dropping 4-digit years and lines that are clearly edition
 *      or age-warning text.
 *
 * Pure (no android/ML Kit types) → JVM-unit-tested. Returns ranked [PopNumberResult.candidates]; Day-3
 * fusion with the Prompt API's labeled number breaks any remaining tie.
 */
object PopNumberParser {

    private val HASH = Regex("#\\s?(\\d{1,4})")
    private val NUMBER = Regex("\\b\\d{1,4}\\b")
    // Words that mark a line as edition size / age warning / choking notice — never the Pop number.
    private val NON_POP_CONTEXT =
        Regex("(?i)pcs|piece|month|mois|warning|chok|hazard|advertencia|attention|danger")

    fun parse(lines: List<RecognizedLine>): PopNumberResult {
        // 1. Explicit '#NNN' is unambiguous — take it regardless of size.
        for (line in lines) {
            HASH.find(line.text)?.let { return PopNumberResult(it.groupValues[1], listOf(it.groupValues[1])) }
        }
        // 2. Otherwise rank standalone numbers by line height, dropping years and edition/warning lines.
        val ranked = lines
            .filterNot { NON_POP_CONTEXT.containsMatchIn(it.text) }
            .flatMap { line -> NUMBER.findAll(line.text).map { it.value to line.box.height } }
            .filterNot { (num, _) -> isYear(num) }
            .sortedByDescending { (_, height) -> height }
            .map { (num, _) -> num }
            .distinct()
        return PopNumberResult(ranked.firstOrNull(), ranked)
    }

    private fun isYear(num: String): Boolean = num.length == 4 && num.toIntOrNull()?.let { it in 1900..2099 } == true
}

/** The chosen Pop number ([best]) plus the height-ranked fallbacks the cascade can disambiguate later. */
data class PopNumberResult(val best: String?, val candidates: List<String>)
