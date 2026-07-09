package com.aashishgodambe.arcana.core.ai.summary.di

import javax.inject.Qualifier

/** The showcase engine: ML Kit GenAI Summarization (on-device), tried first. */
@Qualifier
@Retention(AnnotationRetention.BINARY)
annotation class MlKitEngine

/** The proven Gemini Nano engine, used as the fallback when ML Kit can't run. */
@Qualifier
@Retention(AnnotationRetention.BINARY)
annotation class GeminiEngine
