package com.aashishgodambe.arcana.core.ai.catalog

import android.os.SystemClock
import android.util.Log
import com.aashishgodambe.arcana.core.ai.model.InferenceLocation
import com.google.firebase.Firebase
import com.google.firebase.ai.ai
import com.google.firebase.ai.type.GenerativeBackend
import com.google.firebase.ai.type.Schema
import com.google.firebase.ai.type.content
import com.google.firebase.ai.type.generationConfig
import org.json.JSONObject
import javax.inject.Inject

/**
 * Last provider in the chain: the cloud escalation. When cheaper providers can't confidently identify the
 * item (a pop the user doesn't own, or a weak on-device read), this sends the segmented image plus the
 * OCR/LLM hints to `gemini-3.1-flash-lite` and asks for the identity as structured JSON. Reached only
 * when the chain's composed-confidence gate isn't met, so it's the escalation of last resort — hence a
 * fixed [CLOUD_CONFIDENCE] (trusted, but not catalog-verified).
 *
 * Needs [CatalogQuery.image]; returns null without it (nothing to look at) or when the model can't
 * identify the figure. The one place the cascade makes a cloud call — App Check + the current model name
 * are handled by the Day-1 gates.
 */
class CloudMultimodalCatalogProvider @Inject constructor() : CatalogProvider {

    override val sourceName = "Cloud"

    private val model by lazy {
        Firebase.ai(backend = GenerativeBackend.googleAI()).generativeModel(
            modelName = CLOUD_MODEL,
            generationConfig = generationConfig {
                responseMimeType = "application/json"
                responseSchema = IDENTITY_SCHEMA
            },
        )
    }

    override suspend fun lookup(query: CatalogQuery): CatalogEntry? {
        val bitmap = query.image ?: return null
        val started = SystemClock.elapsedRealtime()
        val json = try {
            model.generateContent(content { image(bitmap); text(buildPrompt(query)) }).text ?: return null
        } catch (e: Exception) {
            Log.w(TAG, "cloud multimodal identify failed", e)
            return null
        }
        return parse(json, SystemClock.elapsedRealtime() - started)
    }

    private fun buildPrompt(q: CatalogQuery): String = buildString {
        append("Identify this Funko Pop collectible from the box photo. Return the character name, the ")
        append("franchise/license, the Pop number, the series/product line, and any finish or variant ")
        append("(e.g. Metallic, Glow in the Dark). ")
        q.popNumber?.let { append("OCR read the number as #$it. ") }
        q.franchise?.let { append("The franchise appears to be: $it. ") }
        q.character?.let { append("The character appears to be: $it. ") }
        append("If you cannot identify the figure, return empty strings.")
    }

    /** Parse the structured JSON into a [CatalogEntry]. Package-visible for JVM testing of the mapping. */
    internal fun parse(json: String, latencyMs: Long): CatalogEntry? {
        val obj = runCatching { JSONObject(json) }.getOrNull() ?: return null
        val character = obj.optString("character").trim()
        if (character.isBlank()) return null // cloud couldn't identify it
        val finish = obj.optString("finish").trim().ifBlank { null }
        val number = obj.optString("number").trim().ifBlank { null }
        val series = obj.optString("series").trim()
        return CatalogEntry(
            sourceName = sourceName,
            externalId = "cloud:${number ?: character}",
            name = if (finish != null) "$character - $finish" else character,
            franchise = obj.optString("franchise").trim().ifBlank { null },
            series = if (series.isBlank()) emptyList() else listOf(series),
            number = number,
            exclusiveTo = null,
            imageUrl = null,
            confidence = CLOUD_CONFIDENCE,
            executedOn = InferenceLocation.Cloud,
            latencyMs = latencyMs,
        )
    }

    private companion object {
        const val TAG = "CloudCatalog"
        // Kept in step with HybridGeminiService.CLOUD_MODEL (Day-1 Gate C). TODO: Remote Config, shared.
        const val CLOUD_MODEL = "gemini-3.1-flash-lite"
        const val CLOUD_CONFIDENCE = 0.8f
        val IDENTITY_SCHEMA = Schema.obj(
            mapOf(
                "character" to Schema.string(),
                "franchise" to Schema.string(),
                "number" to Schema.string(),
                "series" to Schema.string(),
                "finish" to Schema.string(),
            ),
            optionalProperties = listOf("franchise", "number", "series", "finish"),
        )
    }
}
