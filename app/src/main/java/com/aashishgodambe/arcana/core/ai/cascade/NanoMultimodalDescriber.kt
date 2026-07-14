package com.aashishgodambe.arcana.core.ai.cascade

import android.graphics.Bitmap
import android.util.Log
import com.google.mlkit.genai.common.FeatureStatus
import com.google.mlkit.genai.prompt.Generation
import com.google.mlkit.genai.prompt.ImagePart
import com.google.mlkit.genai.prompt.TextPart
import com.google.mlkit.genai.prompt.generateContentRequest
import javax.inject.Inject

/**
 * Gemini Nano impl of [MultimodalDescriber] via the ML Kit GenAI Prompt API (chosen in Gate A over the
 * fixed captioner, which safety-refuses fantasy/horror boxes).
 *
 * **The prompt asks for appearance, not identity.** Gate A found Nano's field *labels* unreliable (it swaps
 * character and franchise — it read "Popeye" as the character on a Freddy Funko box), and since Week 9 the
 * description no longer feeds identification. So rather than surface a label it gets wrong, we ask for the
 * one thing it's reliable at — **what the figure looks like** — which is also the honest way to restore the
 * design's streaming "the AI is describing the item" beat.
 *
 * The framing stays **product-oriented** ("this is a collectible box") because Gate A showed a bare
 * "describe this image" is what trips the output safety classifier on fantasy/horror figures. Any failure
 * or refusal returns null and the cascade proceeds on OCR + catalog; the UI renders that absence as normal.
 */
class NanoMultimodalDescriber @Inject constructor() : MultimodalDescriber {

    override suspend fun describe(bitmap: Bitmap, onPartial: (String) -> Unit): String? {
        val model = Generation.getClient()
        return try {
            if (model.checkStatus() != FeatureStatus.AVAILABLE) {
                model.download().collect { }
            }
            // The Prompt API recycles the ImagePart bitmap after consuming it (Gate A finding). Hand it a
            // throwaway copy so the shared capture frame survives for the later cloud-escalation stage.
            val copy = bitmap.copy(bitmap.config ?: Bitmap.Config.ARGB_8888, false)
            val request = generateContentRequest(ImagePart(copy), TextPart(PROMPT)) {
                temperature = 0.2f
                topK = 10
            }
            val response = model.generateContent(request)
            val text = response.candidates.firstOrNull()?.text?.trim().orEmpty()
            // An output-safety refusal comes back as an empty/candidate-less response rather than an
            // exception, so name it explicitly — otherwise "refused" looks identical to "worked".
            if (text.isBlank()) {
                Log.i(TAG, "nano returned nothing (candidates=${response.candidates.size}) — likely refused")
                return null
            }
            val description = clean(text)
            if (description.isBlank()) return null
            onPartial(description)
            description
        } catch (e: Exception) {
            Log.i(TAG, "Nano describe unavailable/refused: ${e.message}")
            null
        } finally {
            runCatching { model.close() }
        }
    }

    /** Keep it to one tidy sentence — strip any preamble/markdown Nano adds despite being told not to. */
    private fun clean(text: String): String =
        text.removePrefix("*").trim().removeSurrounding("\"")
            .lineSequence().firstOrNull { it.isNotBlank() }.orEmpty()
            .trim()
            .take(MAX_CHARS)

    private companion object {
        const val TAG = "NanoDescriber"
        const val MAX_CHARS = 140
        const val PROMPT =
            "This is a Funko Pop collectible box. In one short sentence, describe only the figure's visual " +
                "appearance — its colours, outfit, and anything it is holding. Do NOT name the character or " +
                "the franchise, and do not guess what it is. No preamble, no markdown."
    }
}
