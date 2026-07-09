package com.aashishgodambe.arcana.core.ai.summary.di

import com.aashishgodambe.arcana.core.ai.summary.CollectionSummarizer
import com.aashishgodambe.arcana.core.ai.summary.FallbackCollectionSummarizer
import com.aashishgodambe.arcana.core.ai.summary.GeminiCollectionSummarizer
import com.aashishgodambe.arcana.core.ai.summary.MlKitCollectionSummarizer
import dagger.Binds
import dagger.Module
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent

/**
 * Binds the summary engine to the composite [FallbackCollectionSummarizer]: ML Kit GenAI Summarization
 * (the `genai-summarization` sample, on-device) when its feature is provisioned, else the proven Gemini
 * Nano path. The two engines are bound to [CollectionSummarizer] behind qualifiers so the composite depends
 * on the seam, not concrete types (which keeps its fallback logic unit-testable).
 */
@Module
@InstallIn(SingletonComponent::class)
abstract class SummaryModule {

    @Binds
    @MlKitEngine
    abstract fun bindMlKitEngine(impl: MlKitCollectionSummarizer): CollectionSummarizer

    @Binds
    @GeminiEngine
    abstract fun bindGeminiEngine(impl: GeminiCollectionSummarizer): CollectionSummarizer

    @Binds
    abstract fun bindCollectionSummarizer(impl: FallbackCollectionSummarizer): CollectionSummarizer
}
