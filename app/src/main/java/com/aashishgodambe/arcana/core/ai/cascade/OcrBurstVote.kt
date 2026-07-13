package com.aashishgodambe.arcana.core.ai.cascade

/**
 * The escalation-burst decision, pure and JVM-tested (no android/ML Kit types). Week 8's honest failure
 * catalogue found single-frame OCR misreads a glyph under harsh backlight (32 → 82); the measured fix is
 * to burst a few frames only when the first read is weak, OCR them concurrently, and take the majority
 * vote on the Pop number — the winning frame then feeds the downstream stages.
 *
 * "Weak" = the first frame gave no *corroborated* number. A bare number with nothing around it is exactly
 * the case that misreads silently, so it's the trigger; a number backed by a franchise or character read
 * (the common owned-Pop case) is trusted and never bursts — keeping the happy path fast.
 */
object OcrBurstVote {

    /**
     * A Pop number is corroborated when the same positional read also surfaced a franchise or character —
     * an independent signal that the box was read coherently, not a lone digit lifted from noise.
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
