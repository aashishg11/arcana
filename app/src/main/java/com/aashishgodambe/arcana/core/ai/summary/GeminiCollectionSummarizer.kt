package com.aashishgodambe.arcana.core.ai.summary

import com.aashishgodambe.arcana.core.ai.GeminiService
import com.aashishgodambe.arcana.core.ai.model.InferenceResult
import com.aashishgodambe.arcana.core.ai.model.RoutingHint
import com.aashishgodambe.arcana.core.domain.model.WeeklyDeltas
import kotlinx.coroutines.flow.Flow
import javax.inject.Inject

/**
 * [CollectionSummarizer] backed by [GeminiService] — the proven on-device path from Week 2. Renders the
 * structured weekly deltas into a compact prompt and asks for a 1-2 sentence "what moved" narration,
 * forbidding a bare restatement of the headline number and any outside knowledge.
 */
class GeminiCollectionSummarizer @Inject constructor(
    private val gemini: GeminiService,
) : CollectionSummarizer {

    override fun summarize(deltas: WeeklyDeltas): Flow<InferenceResult> =
        gemini.generateText(buildPrompt(deltas), RoutingHint.Auto)

    private fun buildPrompt(deltas: WeeklyDeltas): String {
        val movers = deltas.lists.take(MAX_MOVERS)
        val lines = movers.joinToString("\n") { d ->
            "- ${d.listName}: ${signedDollars(d.deltaCents)} (was ${dollars(d.previousCents)}, now ${dollars(d.currentCents)})"
        }
        val remainder = (deltas.lists.size - movers.size).coerceAtLeast(0)
        val tail = if (remainder > 0) "\n- $remainder other list(s) were roughly flat" else ""

        val instructions = """
            You are Arcana, a private assistant for a collectibles portfolio.
            Below is how the value of each collection list changed this week, from the user's own tracked
            history (not from any marketplace listing).

            Rules:
            - Write 1-2 sentences of plain language describing WHAT MOVED: which list led, which slipped,
              which were flat.
            - Do NOT just restate a single total dollar figure — a summary that only repeats the headline
              number is useless. Focus on the relative movement between lists.
            - Use ONLY the lists below. Do not use outside knowledge about the characters or franchises.
            - Be concrete and brief.
        """.trimIndent()

        return buildString {
            appendLine(instructions)
            appendLine()
            appendLine("Changes this week (biggest mover first):")
            appendLine(lines + tail)
            appendLine()
            append("Summary:")
        }
    }

    private fun dollars(cents: Int): String = "$" + "%,d".format(cents / 100)

    private fun signedDollars(cents: Int): String {
        val sign = if (cents >= 0) "+" else "-"
        return sign + "$" + "%,d".format(kotlin.math.abs(cents) / 100)
    }

    private companion object {
        const val MAX_MOVERS = 6
    }
}
