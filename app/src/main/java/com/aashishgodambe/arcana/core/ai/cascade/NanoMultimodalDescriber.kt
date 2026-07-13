package com.aashishgodambe.arcana.core.ai.cascade

import android.graphics.Bitmap
import android.util.Log
import com.google.mlkit.genai.common.FeatureStatus
import com.google.mlkit.genai.prompt.Generation
import com.google.mlkit.genai.prompt.ImagePart
import com.google.mlkit.genai.prompt.TextPart
import com.google.mlkit.genai.prompt.generateContentRequest
import org.json.JSONObject
import javax.inject.Inject

/**
 * Gemini Nano impl of [MultimodalDescriber] via the ML Kit GenAI Prompt API (the surface chosen in
 * Gate A over the fixed captioner, which safety-refuses fantasy/horror boxes). A terse, product-framed
 * prompt asks for compact JSON, which parses into an [LlmBoxRead] fusion hint — kept fast by not asking
 * for prose (~6s vs ~18s for a paragraph, per Gate A).
 *
 * Best-effort by contract: AICore needs the app foreground and can still refuse an output on policy, so
 * any failure returns null and the cascade proceeds on OCR + catalog. Nano's field *labels* are
 * unreliable (it swaps character/franchise), so downstream fusion treats these as fallbacks only.
 */
class NanoMultimodalDescriber @Inject constructor() : MultimodalDescriber {

    override suspend fun describe(bitmap: Bitmap, onPartial: (String) -> Unit): LlmBoxRead? {
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
            val text = model.generateContent(request).candidates.firstOrNull()?.text?.trim().orEmpty()
            if (text.isBlank()) return null
            onPartial(text)
            parse(text)
        } catch (e: Exception) {
            Log.i(TAG, "Nano describe unavailable/refused: ${e.message}")
            null
        } finally {
            runCatching { model.close() }
        }
    }

    /** Parse the terse JSON (tolerating a ```json fence) into an [LlmBoxRead]; keep the raw text as a hint. */
    private fun parse(text: String): LlmBoxRead {
        // Nano often wraps JSON in a markdown fence despite instructions — take the outermost {...}.
        val start = text.indexOf('{')
        val end = text.lastIndexOf('}')
        val obj = if (start in 0 until end) {
            runCatching { JSONObject(text.substring(start, end + 1)) }.getOrNull()
        } else {
            null
        } ?: return LlmBoxRead(rawText = text)
        fun s(key: String) = obj.optString(key).trim().ifBlank { null }
        return LlmBoxRead(
            character = s("character"),
            franchise = s("franchise"),
            number = s("number"),
            series = s("series"),
            rawText = text,
        )
    }

    private companion object {
        const val TAG = "NanoDescriber"
        const val PROMPT =
            "This is a Funko Pop collectible box. Read the box and output ONLY compact JSON with keys " +
                "character, franchise, number (the Pop number), series. No prose, no markdown."
    }
}
