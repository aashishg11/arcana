package com.aashishgodambe.arcana.feature.ask

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.aashishgodambe.arcana.core.ai.GeminiService
import com.aashishgodambe.arcana.core.ai.model.InferenceMetadata
import com.aashishgodambe.arcana.core.ai.model.InferenceResult
import com.aashishgodambe.arcana.core.ai.model.RoutingHint
import com.aashishgodambe.arcana.core.data.repository.CollectibleRepository
import com.aashishgodambe.arcana.core.domain.model.Collectible
import com.aashishgodambe.arcana.core.domain.model.FunkoPop
import com.aashishgodambe.arcana.ui.formatUsd
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import javax.inject.Inject

/** A collection item pulled in to ground an answer, shown as a tappable chip in the turn's strip. */
data class GroundingItem(val localId: Long, val label: String)

/** One question and its grounded answer, rendered inline as a unit: question → chips → answer. */
data class AskTurn(
    val question: String,
    val grounding: List<GroundingItem> = emptyList(),
    /** Partial answer while streaming; null once [answer] is final or on error. */
    val streamingAnswer: String? = null,
    val answer: String? = null,
    val metadata: InferenceMetadata? = null,
    val error: String? = null,
)

data class AskUiState(
    val turns: List<AskTurn> = emptyList(),
    val isRunning: Boolean = false,
) {
    /** Header badge follows the most recent completed answer's location. */
    val badge get() = turns.lastOrNull { it.metadata != null }?.metadata?.executedOn
}

@HiltViewModel
class AskViewModel @Inject constructor(
    private val gemini: GeminiService,
    private val repository: CollectibleRepository,
) : ViewModel() {

    private val _state = MutableStateFlow(AskUiState())
    val state: StateFlow<AskUiState> = _state.asStateFlow()

    /** The single suggested question for v1 — the grounded money-shot. */
    val suggestedQuestion = "What's my most valuable item?"

    private var lastQuestion: String? = null

    /** The items grounding the current thread — reused for subject-less follow-ups ("tell me more"). */
    private var lastGrounding: List<Collectible> = emptyList()

    /** Normal path: try on-device first, fall back to cloud (see [RoutingHint.Auto]). */
    fun ask(question: String) = run(question, RoutingHint.Auto)

    /** Benchmark path: re-ask the last question forcing cloud, for a direct on-device-vs-cloud flip. */
    fun compareOnCloud() {
        run(lastQuestion ?: return, RoutingHint.OnlyCloud)
    }

    /** The reverse benchmark: re-ask the last question forcing on-device (Nano). */
    fun compareOnDevice() {
        run(lastQuestion ?: return, RoutingHint.OnlyOnDevice)
    }

    private fun run(question: String, routingHint: RoutingHint) {
        val q = question.trim()
        if (q.isEmpty() || _state.value.isRunning) return
        lastQuestion = q
        val history = _state.value.turns // prior turns, captured before this one is appended

        _state.update {
            it.copy(turns = it.turns + AskTurn(question = q, streamingAnswer = ""), isRunning = true)
        }

        viewModelScope.launch {
            // Ground on the keyword-relevant subset. If the question has no salient terms it's either
            // a follow-up ("tell me what they are") — reuse the current thread's focus — or a ranking
            // question ("what's my most valuable?") on a fresh thread — fall back to top value. Keeping
            // matches un-mixed stops top-value items leaking into a "do I have X?" answer. Lexical
            // retrieval for now; Week-9 upgrades it to semantic embeddings.
            val relevant = repository.search(q, RELEVANT_LIMIT)
            val grounded = relevant.ifEmpty {
                lastGrounding.ifEmpty { repository.getMostValuable(TOP_VALUE_LIMIT) }
            }
            lastGrounding = grounded
            updateLastTurn { it.copy(grounding = grounded.map(::groundingOf)) }

            val prompt = buildPrompt(q, grounded, history)
            gemini.generateText(prompt, routingHint).collect { result ->
                when (result) {
                    is InferenceResult.Streaming ->
                        updateLastTurn { it.copy(streamingAnswer = result.partialText) }

                    is InferenceResult.Success ->
                        updateLastTurn(done = true) { it.copy(answer = result.fullText, streamingAnswer = null, metadata = result.metadata) }

                    is InferenceResult.Error ->
                        updateLastTurn(done = true) { it.copy(error = result.cause.message ?: "Inference failed", streamingAnswer = null) }
                }
            }
        }
    }

    /**
     * Applies [transform] to the in-flight (last) turn. When [done] is true the turn is terminal, so
     * `isRunning` is cleared in the *same* emission — no intermediate "answered but still running" state.
     */
    private fun updateLastTurn(done: Boolean = false, transform: (AskTurn) -> AskTurn) {
        _state.update { s ->
            val turns = if (s.turns.isEmpty()) s.turns else s.turns.dropLast(1) + transform(s.turns.last())
            s.copy(turns = turns, isRunning = if (done) false else s.isRunning)
        }
    }

    private fun groundingOf(c: Collectible): GroundingItem =
        GroundingItem(localId = c.localId, label = "${c.name} · ${formatUsd(c.estimatedValueCents)}")

    private fun buildPrompt(question: String, items: List<Collectible>, history: List<AskTurn>): String {
        val lines = items.mapIndexed { i, c ->
            val series = c.series.joinToString(", ").takeIf { it.isNotBlank() }?.let { " — series: $it" } ?: ""
            val nft = if (c is FunkoPop && c.isNftRedeemable) " (NFT redeemable)" else ""
            "${i + 1}. ${c.name} — ${formatUsd(c.estimatedValueCents)}$series$nft"
        }.joinToString("\n").ifEmpty { "(no matching items)" }

        val conversation = history.takeLast(HISTORY_TURNS)
            .flatMap { t -> listOfNotNull("User: ${t.question}", t.answer?.let { "Arcana: $it" }) }
            .joinToString("\n")

        // Built with buildString (not a trimIndent template) because interpolating the multi-line
        // conversation into a trimIndent block would zero out the common indent and break the dedent.
        val instructions = """
            You are Arcana, a private assistant for a collectibles portfolio.
            The list below is the subset of the user's collection retrieved for their question.
            It is NOT the whole collection.

            Rules:
            - Answer using ONLY these rows. Do not invent items or use outside knowledge about which
              character belongs to which franchise — rely on the "series" field shown.
            - If no row matches the question, say you don't see any matching items in what was retrieved.
            - Be concise (2-3 sentences). State counts and dollar values exactly.
        """.trimIndent()

        return buildString {
            appendLine(instructions)
            appendLine()
            appendLine("Items (most valuable first):")
            appendLine(lines)
            appendLine()
            if (conversation.isNotBlank()) {
                appendLine("Recent conversation (for context; \"they\"/\"them\" refer to the items above):")
                appendLine(conversation)
                appendLine()
            }
            appendLine("Current question: $question")
            append("Answer:")
        }
    }

    private companion object {
        const val TOP_VALUE_LIMIT = 3
        const val RELEVANT_LIMIT = 12
        const val HISTORY_TURNS = 3
    }
}
