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
    // A 1-4 digit run that is NOT part of a longer number — so "715" is found even in "nT715" or "(132",
    // while long UPC / lot codes ("8969852042", "FAC-053571-21112") yield no spurious 1-4 candidate.
    private val NUMBER = Regex("(?<!\\d)\\d{1,4}(?!\\d)")
    private val PCS = Regex("(?i)\\bp[cg]s\\b|pieces?\\b")
    // A line marked as edition size / age warning / choking notice never holds the Pop number. `\bmes(es|i)\b`
    // catches Spanish/Italian "months" (36 meses / 36 mesi) that the old "month|mois" alone let through.
    private val NON_POP_CONTEXT =
        Regex("(?i)pcs|piece|month|mois|\\bmes(?:es|i)?\\b|warning|chok|hazard|advertencia|attention|danger")

    fun parse(lines: List<RecognizedLine>): PopNumberResult {
        // 1. Explicit '#NNN' is unambiguous — take it regardless of size.
        for (line in lines) {
            HASH.find(line.text)?.let { return PopNumberResult(it.groupValues[1], listOf(it.groupValues[1])) }
        }
        // 2. The edition size (the number nearest a "PCS" line) is never the Pop number — exclude it. This
        //    catches counts printed on a SEPARATE line from "PCS" ("-2300" above "PCS"), which a same-line
        //    text filter misses.
        val edition = editionNumber(lines)
        // 3. Rank the rest by line height — the Pop number is the visually largest number on the box. With
        //    the year, the edition size, and the age-warning numbers now removed, height reliably picks the
        //    Pop number over the remaining noise. (Position-ranking was tried and regressed: it promoted a
        //    stray "0" in "SANDIEG0" and a taller edition when its "PCS" line wasn't OCR'd.)
        val ranked = lines
            .filterNot { NON_POP_CONTEXT.containsMatchIn(it.text) }
            .flatMap { line -> NUMBER.findAll(line.text).map { Candidate(it.value, line) } }
            .filterNot { isYear(it.num) || it.num == edition }
            .sortedByDescending { it.line.box.height }
            .map { it.num }
            .distinct()
        return PopNumberResult(ranked.firstOrNull(), ranked)
    }

    private data class Candidate(val num: String, val line: RecognizedLine)

    /** The piece count vertically nearest a "PCS" line — the edition size, to be excluded as a Pop number. */
    private fun editionNumber(lines: List<RecognizedLine>): String? {
        val pcs = lines.firstOrNull { PCS.containsMatchIn(it.text) } ?: return null
        val center = (pcs.box.top + pcs.box.bottom) / 2
        return lines
            .flatMap { l -> NUMBER.findAll(l.text).map { it.value to l } }
            .filterNot { (n, _) -> isYear(n) }
            .minByOrNull { (_, l) -> kotlin.math.abs((l.box.top + l.box.bottom) / 2 - center) }
            ?.first
    }

    private fun isYear(num: String): Boolean = num.length == 4 && num.toIntOrNull()?.let { it in 1900..2099 } == true
}

/** The chosen Pop number ([best]) plus the height-ranked fallbacks the cascade can disambiguate later. */
data class PopNumberResult(val best: String?, val candidates: List<String>)
