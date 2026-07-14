package com.aashishgodambe.arcana.core.ai.cascade

/**
 * The escalation-burst vote, pure and JVM-tested (no android/ML Kit types). Week 8's honest failure
 * catalogue found single-frame OCR misreads a glyph under harsh backlight (32 → 82) — and such a misread
 * looks perfectly confident on its own (right franchise, a plausible number), so it *can't* be detected
 * from one frame. The fix is to always vote across the burst: OCR the frames, take the majority Pop
 * number, and feed the winning frame downstream. [isCorroborated] is no longer a burst *gate* (that let
 * misreads through) — it only breaks a vote tie toward a coherently-read frame.
 */
object OcrBurstVote {

    /**
     * A Pop number is corroborated when the same positional read also surfaced a franchise or character —
     * an independent signal that the box was read coherently. Used only to break a vote tie ([pick]).
     */
    fun isCorroborated(layout: BoxLayout): Boolean =
        layout.popNumber != null && (layout.franchise != null || layout.character != null)

    /**
     * The Pop numbers tied for the most votes. A size > 1 means the burst *can't* decide on frequency alone
     * (e.g. `[62,62,32,32]`), so the caller can break the tie with catalog knowledge before calling [pick].
     */
    fun topNumbers(layouts: List<BoxLayout>): List<String> {
        val counts = layouts.mapNotNull { it.popNumber }.groupingBy { it }.eachCount()
        val max = counts.values.maxOrNull() ?: return emptyList()
        return counts.filter { it.value == max }.keys.toList()
    }

    /**
     * The index of the winning layout among a burst. The most-frequent Pop number wins; a **frequency tie**
     * (`[62,62,32,32]`) is broken toward a number that's [preferred] — one that exists in the local
     * collection (Popeye #32, not #62), the Week-9 known-wrong-answer bug — then toward a corroborated read,
     * then the first frame seen. Among frames sharing the winning number, a corroborated one is preferred.
     * Returns 0 when no frame produced a number, so the caller falls back to the primary frame.
     */
    fun pick(layouts: List<BoxLayout>, preferred: Set<String> = emptySet()): Int {
        if (layouts.isEmpty()) return 0
        val top = topNumbers(layouts)
        val winningNumber = when {
            top.isEmpty() -> return 0
            top.size == 1 -> top.first()
            else -> top.firstOrNull { it in preferred }
                ?: top.firstOrNull { n -> layouts.any { it.popNumber == n && isCorroborated(it) } }
                ?: layouts.firstNotNullOfOrNull { l -> l.popNumber?.takeIf { it in top } }
                ?: top.first()
        }
        return layouts.indexOfFirst { it.popNumber == winningNumber && isCorroborated(it) }
            .takeIf { it >= 0 }
            ?: layouts.indexOfFirst { it.popNumber == winningNumber }
    }
}
