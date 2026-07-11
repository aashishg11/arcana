package com.aashishgodambe.arcana.core.ai.di

import android.content.Context
import com.aashishgodambe.arcana.core.ai.DelegatingGeminiService
import com.aashishgodambe.arcana.core.ai.GeminiService
import com.aashishgodambe.arcana.core.ai.HybridGeminiService
import com.aashishgodambe.arcana.core.ai.LiteRtGeminiService
import com.aashishgodambe.arcana.core.ai.OwnModelEngine
import com.aashishgodambe.arcana.core.ai.capability.DeviceCapabilityChecker
import com.aashishgodambe.arcana.core.ai.capability.FirebaseDeviceCapabilityChecker
import com.aashishgodambe.arcana.core.data.settings.SettingsStore
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.android.qualifiers.ApplicationContext
import dagger.hilt.components.SingletonComponent
import javax.inject.Singleton

@Module
@InstallIn(SingletonComponent::class)
object AiModule {

    /** The own-model engine — also injected into Settings so the picker can presence-gate its row. */
    @Provides
    @Singleton
    fun provideOwnModelEngine(@ApplicationContext context: Context): OwnModelEngine =
        LiteRtGeminiService(context)

    /**
     * The app-wide [GeminiService] is the [DelegatingGeminiService]: it reads the persisted engine
     * selection per call and routes Ask Arcana to Nano, the own-model, or cloud. Explicit [RoutingHint]s
     * (used by the benchmark) bypass the selection.
     */
    @Provides
    @Singleton
    fun provideGeminiService(
        ownModel: OwnModelEngine,
        settings: SettingsStore,
    ): GeminiService = DelegatingGeminiService(
        hybrid = HybridGeminiService(),
        ownModel = ownModel,
        selectedEngine = { settings.askEngine.value },
    )

    @Provides
    @Singleton
    fun provideDeviceCapabilityChecker(): DeviceCapabilityChecker = FirebaseDeviceCapabilityChecker()
}
