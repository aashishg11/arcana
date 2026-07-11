package com.aashishgodambe.arcana.core.ai.model

/**
 * Where an inference actually executed. Surfaced in the UI as the inference badge.
 *
 * - [OnDevice] — Gemini Nano via AICore (out-of-process, zero app-resident memory).
 * - [OnDeviceOwnModel] — the self-quantized Gemma 3 1B (LiteRT q4, in-process) — the user-selectable
 *   showcase engine. Distinct from [OnDevice] because "my own model runs on-device" is a different
 *   claim than "the OS model runs on-device," and the badge colour reflects it (gold vs iris).
 * - [Cloud] — Gemini 2.5 Flash-Lite fallback.
 */
enum class InferenceLocation { OnDevice, OnDeviceOwnModel, Cloud }
