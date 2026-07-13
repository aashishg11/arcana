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
     * The index of the winning layout among a burst: the most-frequent Pop number wins, preferring a frame
     * whose read is also corroborated; ties break to the first frame seen (the primary). Returns 0 when no
     * frame produced a number, so the caller falls back to the primary frame.
     */
    fun pick(layouts: List<BoxLayout>): Int {
        if (layouts.isEmpty()) return 0
        val counts = layouts.mapNotNull { it.popNumber }.groupingBy { it }.eachCount()
        val winningNumber = counts.maxByOrNull { it.value }?.key ?: return 0
        return layouts.indexOfFirst { it.popNumber == winningNumber && isCorroborated(it) }
            .takeIf { it >= 0 }
            ?: layouts.indexOfFirst { it.popNumber == winningNumber }
    }
}
