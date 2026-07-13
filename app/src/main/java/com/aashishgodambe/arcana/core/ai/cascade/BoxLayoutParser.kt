package com.aashishgodambe.arcana.core.ai.cascade

import kotlin.math.abs

/**
 * Extracts a Funko box's structured fields from OCR using the box's fixed **positional layout** (verified
 * on real photos of Aang #406 and Freddy Funko / Popeye #32):
 *   - **franchise**  — the topmost prominent banner line (AVATAR, POPEYE), minus boilerplate/number;
 *   - **popNumber**  — the visually largest number (delegated to [PopNumberParser]);
 *   - **character**  — the tallest line directly above the "VINYL FIGURE / FIGURA DE VINIL" landmark
 *                      (AANG…, FREDDY FUNKO); the finish (e.g. METALLIC) can run onto this line;
 *   - **rarityOrExclusive** — the seal above the character: a rarity tier (Legendary) or an exclusivity
 *                      label (NFT Release), whichever is present;
 *   - **editionSize** — the piece count in that seal, taken as the number vertically nearest the "PCS".
 *
 * This is the reliable, on-device structuring the cascade trusts over Nano's Prompt-API field *labels*,
 * which swap character/franchise. Pure (no android/ML Kit types) → JVM-tested against both real boxes.
 * Real OCR is noisy (merged lines like "eg POPEYE 32", PCS→PGS, split "WITH"), so these are best-effort
 * heuristics, not guarantees; Day-3 fusion reconciles them with Nano and the catalog.
 */
object BoxLayoutParser {

    private val VINYL = Regex("(?i)vinyl figure|figura de vinil|figurine en vinyle")
    private val PCS = Regex("(?i)\\bp[cg]s\\b|pieces?\\b")
    private val BOILERPLATE = Regex("(?i)nickelodeon|enicelodeon|www|\\.com|^\\W*(digital|funko|pop)\\W*$")
    private val RARITY = Regex("(?i)\\b(common|uncommon|rare|epic|legendary|grail|mythic)\\b")
    private val EXCLUSIVE = Regex("(?i)\\b(nft|release|exclusive|convention|chase|shared)\\b")
    // Finish/variant tag that's part of the canonical name (appended with " - "). Optional.
    private val FINISH =
        Regex("(?i)\\b(metallic|glow in the dark|gitd|flocked|chase|diamond|chrome|black ?light|scented|translucent|glitter|patina)\\b")
    // The top-left POP! bubble names the product line (series): "Pop! Digital" / "Pop! Vinyl".
    private val SERIES_LINE = Regex("(?i)\\b(digital|vinyl)\\b")
    private val NUMERIC = Regex("\\d{2,5}")

    private const val PROMINENT = 30          // min line height (px) to count as a "banner" line
    private const val CHAR_BAND = 6           // character sits within ~6 landmark-heights above it

    fun parse(lines: List<RecognizedLine>): BoxLayout {
        val number = PopNumberParser.parse(lines).best
        val landmark = lines.filter { VINYL.containsMatchIn(it.text) }.minByOrNull { it.box.top }

        val rawCharacterLine = landmark?.let { lm ->
            val bandTop = lm.box.top - CHAR_BAND * lm.box.height
            lines.filter { it.box.top in bandTop until lm.box.top && it.box.height >= PROMINENT }
                .maxByOrNull { it.box.height }?.text
        }
        // The finish tag usually rides on the character line ("AANG ARMOR METALLIC"); fall back to a
        // scan of all lines in case it's a separate badge. Split it out of the character name.
        val finish = (rawCharacterLine?.let { FINISH.find(it)?.value }
            ?: lines.firstNotNullOfOrNull { FINISH.find(it.text)?.value })
            ?.lowercase()?.replaceFirstChar { it.uppercase() }
        val character = rawCharacterLine?.let { collapse(FINISH.replace(it, "")) }?.ifBlank { null }

        val franchise = lines
            .filter { it.box.height >= PROMINENT }
            .filterNot { BOILERPLATE.containsMatchIn(it.text) }
            .filterNot { isOnlyNumber(it.text) }
            .filter { landmark == null || it.box.top < landmark.box.top }
            .minByOrNull { it.box.top }
            ?.let { franchiseFrom(it.text, number) }

        // Series = the top-left POP! product-line bubble ("Pop! Digital"). Restricted to the upper box
        // (above the "VINYL FIGURE" landmark) so the landmark's own "VINYL" doesn't match.
        val series = lines
            .filter { landmark == null || it.box.top < landmark.box.top }
            .sortedBy { it.box.top }
            .firstNotNullOfOrNull { SERIES_LINE.find(it.text)?.value }
            ?.lowercase()?.replaceFirstChar { it.uppercase() }
            ?.let { "Pop! $it" }

        val editionSize = lines.firstOrNull { PCS.containsMatchIn(it.text) }?.let { pcs ->
            val pcsCenter = (pcs.box.top + pcs.box.bottom) / 2
            lines
                .flatMap { l -> NUMERIC.findAll(l.text).map { it.value to l } }
                .filterNot { (n, _) -> n == number || isYear(n) }
                .minByOrNull { (_, l) -> abs((l.box.top + l.box.bottom) / 2 - pcsCenter) }
                ?.first
        }

        val rarityOrExclusive = lines.firstNotNullOfOrNull { RARITY.find(it.text)?.value }
            ?.lowercase()?.replaceFirstChar { it.uppercase() }
            ?: lines.filter { EXCLUSIVE.containsMatchIn(it.text) }
                .sortedBy { it.box.top }
                .flatMap { l -> EXCLUSIVE.findAll(l.text).map { it.value.uppercase() } }
                .distinct()
                .ifEmpty { null }
                ?.joinToString(" ")

        return BoxLayout(
            franchise = franchise,
            series = series,
            popNumber = number,
            character = character,
            finish = finish,
            rarityOrExclusive = rarityOrExclusive,
            editionSize = editionSize,
        )
    }

    /** Strip the Pop number and short lowercase OCR noise from a banner line, leaving the franchise. */
    private fun franchiseFrom(text: String, number: String?): String? =
        collapse(text)
            .split(" ")
            .filter { it.isNotBlank() && it != number && (it.length >= 3 && it.any(Char::isLetter)) }
            .joinToString(" ")
            .ifBlank { null }

    private fun collapse(text: String): String = text.trim().replace(Regex("\\s+"), " ")

    private fun isOnlyNumber(text: String): Boolean {
        val stripped = text.filter { it.isLetterOrDigit() }
        return stripped.isNotEmpty() && stripped.all { it.isDigit() }
    }

    private fun isYear(num: String): Boolean = num.length == 4 && num.toIntOrNull()?.let { it in 1900..2099 } == true
}

/**
 * Structured, positionally-extracted Funko box fields. Any field may be null when OCR didn't surface it;
 * the cascade treats these as strong hints to reconcile with Nano and the catalog, not final truth.
 */
data class BoxLayout(
    val franchise: String?,
    /** Product line from the top-left POP! bubble, e.g. "Pop! Digital". */
    val series: String?,
    val popNumber: String?,
    val character: String?,
    /** Finish/variant tag (Metallic, GITD, Chase…), or null. Canonical name = "$character - $finish". */
    val finish: String?,
    val rarityOrExclusive: String?,
    val editionSize: String?,
) {
    /** The canonical display name: character with the finish appended when present. */
    val fullName: String?
        get() = when {
            character == null -> null
            finish == null -> character
            else -> "$character - $finish"
        }
}
