package com.aashishgodambe.arcana.core.ai.cascade

import android.graphics.Bitmap

/**
 * The cascade's on-device LLM stage: Gemini Nano's read of the box (via the ML Kit Prompt API). Best
 * effort by design — Gate A found it can safety-refuse fantasy/horror imagery and needs the app in the
 * foreground — so it returns null when unavailable/refused and the cascade carries on (identification is
 * anchored on OCR + catalog, not this). [onPartial] streams the description for the Review screen.
 */
interface MultimodalDescriber {
    suspend fun describe(bitmap: Bitmap, onPartial: (String) -> Unit = {}): LlmBoxRead?
}
