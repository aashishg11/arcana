package com.aashishgodambe.arcana.core.ai.di

import com.aashishgodambe.arcana.core.ai.GeminiService
import com.aashishgodambe.arcana.core.ai.HybridGeminiService
import com.aashishgodambe.arcana.core.ai.capability.DeviceCapabilityChecker
import com.aashishgodambe.arcana.core.ai.capability.FirebaseDeviceCapabilityChecker
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent
import javax.inject.Singleton

@Module
@InstallIn(SingletonComponent::class)
object AiModule {

    @Provides
    @Singleton
    fun provideGeminiService(): GeminiService = HybridGeminiService()

    @Provides
    @Singleton
    fun provideDeviceCapabilityChecker(): DeviceCapabilityChecker = FirebaseDeviceCapabilityChecker()
}
