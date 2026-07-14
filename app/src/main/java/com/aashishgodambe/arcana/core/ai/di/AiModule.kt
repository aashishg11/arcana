package com.aashishgodambe.arcana.core.ai.di

import android.content.Context
import com.aashishgodambe.arcana.core.ai.DelegatingGeminiService
import com.aashishgodambe.arcana.core.ai.GeminiService
import com.aashishgodambe.arcana.core.ai.HybridGeminiService
import com.aashishgodambe.arcana.core.ai.LiteRtGeminiService
import com.aashishgodambe.arcana.core.ai.OwnModelEngine
import com.aashishgodambe.arcana.core.ai.capability.DeviceCapabilityChecker
import com.aashishgodambe.arcana.core.ai.capability.FirebaseDeviceCapabilityChecker
import com.aashishgodambe.arcana.core.ai.cascade.CaptureCascade
import com.aashishgodambe.arcana.core.ai.cascade.MlKitBarcodeScanner
import com.aashishgodambe.arcana.core.ai.cascade.MlKitImageSegmenter
import com.aashishgodambe.arcana.core.ai.cascade.MlKitTextExtractor
import com.aashishgodambe.arcana.core.ai.cascade.NanoMultimodalDescriber
import com.aashishgodambe.arcana.core.ai.catalog.CatalogProviderChain
import com.aashishgodambe.arcana.core.ai.catalog.CloudMultimodalCatalogProvider
import com.aashishgodambe.arcana.core.ai.catalog.LocalCollectionCatalogProvider
import com.aashishgodambe.arcana.core.ai.rag.CollectionEmbedder
import com.aashishgodambe.arcana.core.ai.rag.CollectionRetriever
import com.aashishgodambe.arcana.core.ai.rag.EmbeddingGemmaEncoder
import com.aashishgodambe.arcana.core.ai.rag.HybridCollectionRetriever
import com.aashishgodambe.arcana.core.ai.writing.ListingWriter
import com.aashishgodambe.arcana.core.ai.writing.MlKitListingWriter
import com.aashishgodambe.arcana.core.data.repository.CollectibleRepository
import com.aashishgodambe.arcana.core.data.settings.SettingsStore
import com.aashishgodambe.arcana.core.domain.model.FunkoPop
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

    /**
     * The on-device RAG embedder — a singleton so the ~200 MB EmbeddingGemma interpreter loads once and is
     * shared by the index worker and Ask. Side-loaded + presence-gated; absent → Ask uses lexical fallback.
     */
    @Provides
    @Singleton
    fun provideCollectionEmbedder(@ApplicationContext context: Context): CollectionEmbedder =
        EmbeddingGemmaEncoder(context)

    /** The Ask grounding seam — the hybrid router (structured SQL + semantic vectors + lexical fallback). */
    @Provides
    @Singleton
    fun provideCollectionRetriever(hybrid: HybridCollectionRetriever): CollectionRetriever = hybrid

    /** The listing writer (`genai-writing-assistance`) — ML Kit Rewriting over the item's own data. */
    @Provides
    @Singleton
    fun provideListingWriter(@ApplicationContext context: Context): ListingWriter = MlKitListingWriter(context)

    /**
     * The capture-cascade engine (Week 8), wired with its stage seams and the ordered catalog chain
     * (local collection first → cloud multimodal escalation). Week 9's capture screen injects this and
     * renders the emitted [com.aashishgodambe.arcana.core.ai.cascade.CascadeState] flow.
     */
    @Provides
    @Singleton
    fun provideCaptureCascade(repository: CollectibleRepository): CaptureCascade =
        CaptureCascade(
            segmenter = MlKitImageSegmenter(),
            describer = NanoMultimodalDescriber(),
            textExtractor = MlKitTextExtractor(),
            barcodeScanner = MlKitBarcodeScanner(),
            catalogChain = CatalogProviderChain(
                listOf(
                    LocalCollectionCatalogProvider(repository),
                    CloudMultimodalCatalogProvider(),
                ),
            ),
            // Owned Pop numbers, for the burst-vote tie-break (fetched lazily, only on a tie).
            ownedNumbers = {
                repository.allCollectibles()
                    .filterIsInstance<FunkoPop>()
                    .mapNotNull { it.popNumber }
                    .toSet()
            },
        )
}
