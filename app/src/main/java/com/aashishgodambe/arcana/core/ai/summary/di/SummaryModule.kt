package com.aashishgodambe.arcana.core.ai.summary.di

import com.aashishgodambe.arcana.core.ai.summary.CollectionSummarizer
import com.aashishgodambe.arcana.core.ai.summary.GeminiCollectionSummarizer
import dagger.Binds
import dagger.Module
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent

/**
 * Binds the summary engine. v1 uses the [GeminiService][com.aashishgodambe.arcana.core.ai.GeminiService]-
 * backed impl; swapping in an ML Kit GenAI Summarization impl is a one-line change here once a device
 * spike confirms it fits.
 */
@Module
@InstallIn(SingletonComponent::class)
abstract class SummaryModule {

    @Binds
    abstract fun bindCollectionSummarizer(impl: GeminiCollectionSummarizer): CollectionSummarizer
}
