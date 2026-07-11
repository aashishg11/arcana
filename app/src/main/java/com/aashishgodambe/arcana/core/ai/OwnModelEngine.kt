package com.aashishgodambe.arcana.core.ai

/**
 * A [GeminiService] whose model is **side-loaded** and therefore may be absent. The Settings engine
 * picker gates the "Your Gemma" option on [isModelAvailable]; the delegating router only routes to it
 * when the model is present. Keeping availability off the base [GeminiService] interface means Nano,
 * cloud, and the fakes stay unaware of a concern that only the own-model engine has.
 */
interface OwnModelEngine : GeminiService {
    /** True when the side-loaded model file is present **and readable** — a designed, checkable state. */
    fun isModelAvailable(): Boolean
}
