package com.aashishgodambe.arcana.core.ai.summary.di

import com.aashishgodambe.arcana.core.ai.summary.CollectionSummarizer
import com.aashishgodambe.arcana.core.ai.summary.FallbackCollectionSummarizer
import dagger.Binds
import dagger.Module
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent

/**
 * Binds the summary engine to the composite [FallbackCollectionSummarizer]: ML Kit GenAI Summarization
 * (the `genai-summarization` sample, on-device) when its feature is provisioned, else the proven Gemini
 * Nano path. Both concrete engines are plain `@Inject` classes the composite depends on directly.
 */
@Module
@InstallIn(SingletonComponent::class)
abstract class SummaryModule {

    @Binds
    abstract fun bindCollectionSummarizer(impl: FallbackCollectionSummarizer): CollectionSummarizer
}
