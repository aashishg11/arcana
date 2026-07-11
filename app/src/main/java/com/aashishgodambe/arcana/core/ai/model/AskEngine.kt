package com.aashishgodambe.arcana.core.ai.model

/**
 * Which engine powers "Ask Arcana" — the user's choice in the Settings engine picker, persisted and read
 * by [com.aashishgodambe.arcana.core.ai.DelegatingGeminiService] on every [RoutingHint.Auto] call.
 *
 * - [Nano] — Gemini Nano (default): out-of-process via AICore, **zero app-resident memory**.
 * - [OwnModel] — the self-quantized Gemma 3 1B (LiteRT q4, in-process). The showcase engine; opt-in
 *   because its ~1 GB footprint shouldn't be paid by every user. Selectable only when the side-loaded
 *   model is present.
 * - [Cloud] — Gemini 2.5 Flash-Lite.
 */
enum class AskEngine { Nano, OwnModel, Cloud }
