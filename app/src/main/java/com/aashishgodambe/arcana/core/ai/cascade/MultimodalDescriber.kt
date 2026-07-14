package com.aashishgodambe.arcana.core.ai.cascade

import android.graphics.Bitmap

/**
 * The cascade's on-device LLM stage: Gemini Nano's look at the box (via the ML Kit Prompt API), returning
 * a short **visual description of the figure's appearance** — "a masked figure in a red suit holding a can
 * of spinach".
 *
 * It deliberately does **not** return identity fields. Week-8's Gate A established that Nano's *labels* are
 * unreliable (it swaps character and franchise), and since Week 9 the description no longer feeds
 * identification at all — that's anchored on OCR + the catalog chain. So the stage is re-pointed at what
 * Nano is genuinely good at: describing what it *sees*, not asserting what it *is*.
 *
 * Best effort by design — it needs the app in the foreground and can safety-refuse fantasy/horror imagery —
 * so it returns null when unavailable or refused, and the cascade carries on. [onPartial] streams the text
 * to the Review screen, where its absence is rendered as normal, not as an error.
 */
interface MultimodalDescriber {
    suspend fun describe(bitmap: Bitmap, onPartial: (String) -> Unit = {}): String?
}
