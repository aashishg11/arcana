package com.aashishgodambe.arcana.feature.settings

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.net.Uri
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.aashishgodambe.arcana.core.ai.OwnModelEngine
import com.aashishgodambe.arcana.core.ai.capability.DeviceCapabilityChecker
import com.aashishgodambe.arcana.core.ai.capability.ModelReadiness
import com.aashishgodambe.arcana.core.ai.cascade.CaptureCascade
import com.aashishgodambe.arcana.core.ai.cascade.CascadeState
import com.aashishgodambe.arcana.core.ai.model.AskEngine
import com.aashishgodambe.arcana.core.ai.rag.CollectionDocument
import com.aashishgodambe.arcana.core.ai.rag.CollectionEmbedder
import com.aashishgodambe.arcana.core.ai.rag.CollectionIndexer
import com.aashishgodambe.arcana.core.ai.rag.CollectionVectorStore
import com.aashishgodambe.arcana.core.ai.model.InferenceResult
import com.aashishgodambe.arcana.core.ai.rag.EmbeddingMath
import com.aashishgodambe.arcana.core.ai.writing.ListingWriter
import com.aashishgodambe.arcana.core.data.repository.CollectibleRepository
import com.aashishgodambe.arcana.core.domain.model.FunkoPop
import android.util.Log
import java.time.LocalDate
import androidx.work.WorkManager
import com.aashishgodambe.arcana.core.data.settings.SettingsStore
import com.aashishgodambe.arcana.core.data.settings.ThemeMode
import com.aashishgodambe.arcana.core.data.worker.WeeklyPriceSyncScheduler
import com.aashishgodambe.arcana.core.data.worker.WeeklyPriceSyncWorker
import dagger.hilt.android.lifecycle.HiltViewModel
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.time.Instant
import javax.inject.Inject

@HiltViewModel
class SettingsViewModel @Inject constructor(
    private val settings: SettingsStore,
    private val syncScheduler: WeeklyPriceSyncScheduler,
    private val capability: DeviceCapabilityChecker,
    private val ownModel: OwnModelEngine,
    private val captureCascade: CaptureCascade,
    private val indexer: CollectionIndexer,
    private val vectorStore: CollectionVectorStore,
    private val embedder: CollectionEmbedder,
    private val repository: CollectibleRepository,
    private val listingWriter: ListingWriter,
    @ApplicationContext private val context: Context,
) : ViewModel() {

    val weeklyWorkerEnabled: StateFlow<Boolean> = settings.weeklyWorkerEnabled
    val themeMode: StateFlow<ThemeMode> = settings.themeMode

    /**
     * Sync status for the Settings row: when prices last actually refreshed (the newest sync snapshot —
     * manual "Sync now" or the weekly worker; the mock seed is disabled and superseded by real syncs), and
     * when the periodic worker next runs (WorkManager's own schedule).
     */
    val syncStatus: StateFlow<SyncStatus> = combine(
        repository.observePortfolioSeries(),
        WorkManager.getInstance(context).getWorkInfosForUniqueWorkFlow(WeeklyPriceSyncWorker.NAME),
    ) { points, infos ->
        val nextAt = infos.firstOrNull { !it.state.isFinished }
            ?.nextScheduleTimeMillis
            ?.takeIf { it != Long.MAX_VALUE }
            ?.let { Instant.ofEpochMilli(it) }
        SyncStatus(lastSyncedAt = points.lastOrNull()?.at, nextSyncAt = nextAt)
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), SyncStatus())

    /** Selected Ask Arcana engine (persisted) and whether the side-loaded own-model can be picked. */
    val askEngine: StateFlow<AskEngine> = settings.askEngine
    private val _ownModelAvailable = MutableStateFlow(ownModel.isModelAvailable())
    val ownModelAvailable: StateFlow<Boolean> = _ownModelAvailable.asStateFlow()

    private val _readiness = MutableStateFlow(ModelReadiness.Unknown)
    val onDeviceReadiness: StateFlow<ModelReadiness> = _readiness.asStateFlow()

    val appVersion: String =
        runCatching { context.packageManager.getPackageInfo(context.packageName, 0).versionName }
            .getOrNull() ?: "1.0"

    init {
        refreshReadiness()
    }

    fun refreshReadiness() {
        _ownModelAvailable.value = ownModel.isModelAvailable() // cheap file check; re-run on demand
        viewModelScope.launch { _readiness.value = capability.onDeviceReadiness() }
    }

    /** If the side-loaded model is gone, don't leave a now-invalid own-model selection stuck. */
    fun setAskEngine(engine: AskEngine) {
        if (engine == AskEngine.OwnModel && !ownModel.isModelAvailable()) return
        settings.setAskEngine(engine)
    }

    /** Persist the choice AND actually schedule/cancel the worker — the toggle isn't cosmetic. */
    fun setWeeklyWorkerEnabled(enabled: Boolean) {
        settings.setWeeklyWorkerEnabled(enabled)
        if (enabled) syncScheduler.schedule() else syncScheduler.cancel()
    }

    fun setThemeMode(mode: ThemeMode) = settings.setThemeMode(mode)

    // --- Dev harness: run the Week-8 capture cascade on a picked photo (debug-only) ---

    private val _cascade = MutableStateFlow(CascadeHarnessState())
    val cascade: StateFlow<CascadeHarnessState> = _cascade.asStateFlow()

    fun runCascade(uri: Uri) {
        viewModelScope.launch {
            _cascade.value = CascadeHarnessState(running = true, log = listOf("Loading image…"))
            val bitmap = withContext(Dispatchers.IO) {
                runCatching { context.contentResolver.openInputStream(uri)?.use { BitmapFactory.decodeStream(it) } }
                    .getOrNull()
            }
            if (bitmap == null) {
                _cascade.value = CascadeHarnessState(log = listOf("Couldn't load that image"))
                return@launch
            }

            val log = mutableListOf<String>()
            var subject: Bitmap? = null
            var settled = false   // the description can trail Settled; don't flip back to "running"
            fun render(done: Boolean = false, summary: String? = null, telemetry: String? = null) {
                _cascade.value = CascadeHarnessState(
                    running = !done, log = log.toList(), subject = subject,
                    summary = summary ?: _cascade.value.summary, telemetry = telemetry ?: _cascade.value.telemetry,
                )
            }
            runCatching {
                captureCascade.identify(bitmap).collect { state ->
                    when (state) {
                        is CascadeState.Segmenting -> {
                            state.subject?.let { subject = it }
                            if (log.lastOrNull() != "Segmenting…") log += "Segmenting…"
                        }
                        is CascadeState.Describing -> {
                            val line = "Nano › " + state.text.replace("\n", " ").take(80)
                            if (log.lastOrNull()?.startsWith("Nano ›") == true) log[log.lastIndex] = line else log += line
                        }
                        is CascadeState.Read -> log += "OCR › #${state.layout.popNumber ?: "?"} · " +
                            "${state.layout.franchise ?: "?"} · ${state.layout.character ?: "?"}"
                        CascadeState.Matching -> log += "Matching catalog…"
                        is CascadeState.Settled -> {
                            settled = true
                            val r = state.result
                            val summary = r.entry?.let {
                                "${it.name} · ${it.sourceName} · ${(it.confidence * 100).toInt()}%" +
                                    if (r.owned) " · you own this" else ""
                            } ?: "Unresolved — no confident match"
                            log += "✓ $summary"
                            render(done = true, summary = summary, telemetry = "${r.telemetry.totalMs}ms · ${r.telemetry.perStageMs}")
                            return@collect
                        }
                        is CascadeState.Failed -> log += "✗ failed at ${state.stage}: ${state.cause.message}"
                    }
                    render(done = settled)
                }
            }.onFailure {
                log += "✗ ${it.message}"
                render(done = true)
            }
        }
    }

    // --- Dev harness: RAG index + semantic query over the real collection (debug-only) ---

    private val _rag = MutableStateFlow(RagHarnessState())
    val rag: StateFlow<RagHarnessState> = _rag.asStateFlow()

    init {
        viewModelScope.launch { _rag.update { it.copy(indexCount = vectorStore.count()) } }
    }

    /** Rebuild the vector index over the whole collection, timing it. Incremental after the first run. */
    fun buildIndex() {
        if (_rag.value.building) return
        viewModelScope.launch {
            _rag.update { it.copy(building = true, log = listOf("Indexing…")) }
            val started = System.currentTimeMillis()
            val result = indexer.index { p -> _rag.update { it.copy(log = listOf("Indexing ${p.done}/${p.total}…")) } }
            val ms = System.currentTimeMillis() - started
            val line = if (!result.available) "Model not installed — side-load it"
            else "embedded ${result.embedded} · skipped ${result.skipped} · failed ${result.failed} in ${ms}ms"
            _rag.update { it.copy(building = false, indexCount = vectorStore.count(), log = listOf(line)) }
        }
    }

    /** Embed [query] and show the top-k collection items by cosine — the retrieval money-shot. */
    fun queryRag(query: String) {
        if (query.isBlank() || _rag.value.building) return
        viewModelScope.launch {
            _rag.update { it.copy(building = true) }
            val qv = embedder.embedQuery(query)
            if (qv == null) {
                _rag.update { it.copy(building = false, log = listOf("Model not installed — side-load it")) }
                return@launch
            }
            val names = repository.allCollectibles().associateBy { it.localId }
            val top = vectorStore.topK(qv, k = 5)
            val lines = top.map { s ->
                "${"%.2f".format(s.score)}  ${names[s.id]?.name ?: "#${s.id}"}"
            }.ifEmpty { listOf("(index empty — build it first)") }
            _rag.update { it.copy(building = false, log = listOf("\"$query\" →") + lines) }
        }
    }

    /**
     * Document-shape A/B: for a real item, does the **rich** descriptor (name + series) retrieve a
     * franchise query better than the **bare name**? Reports both cosines — the plan's "document shape >
     * model choice" claim, measured on the user's own data.
     */
    fun docShapeAB() {
        viewModelScope.launch {
            _rag.update { it.copy(building = true, log = listOf("Comparing shapes…")) }
            val item = repository.allCollectibles().firstOrNull { it.series.any { s -> s.isNotBlank() } }
            if (item == null) {
                _rag.update { it.copy(building = false, log = listOf("No item with a series to compare")) }
                return@launch
            }
            val query = item.series.first { it.isNotBlank() }
            val qv = embedder.embedQuery(query)
            val bare = embedder.embedDocument(item.name)
            val rich = embedder.embedDocument(CollectionDocument.of(item))
            if (qv == null || bare == null || rich == null) {
                _rag.update { it.copy(building = false, log = listOf("Model not installed — side-load it")) }
                return@launch
            }
            val dim = EmbeddingMath.SHIPPING_DIMENSION
            val q = EmbeddingMath.truncate(qv, dim)
            val cosBare = EmbeddingMath.cosine(q, EmbeddingMath.truncate(bare, dim))
            val cosRich = EmbeddingMath.cosine(q, EmbeddingMath.truncate(rich, dim))
            _rag.update {
                it.copy(
                    building = false,
                    log = listOf(
                        "${item.name}  ·  query \"$query\"",
                        "bare name:  ${"%.3f".format(cosBare)}",
                        "rich (+series):  ${"%.3f".format(cosRich)}",
                        if (cosRich > cosBare) "→ rich wins" else "→ bare wins",
                    ),
                )
            }
        }
    }

    // --- Dev harness: draft a listing for a normal + a horror pop, to probe the safety filter (debug-only) ---

    private val _listing = MutableStateFlow(listOf("Drafts a listing for a normal and a horror pop (checks the safety filter)."))
    val listing: StateFlow<List<String>> = _listing.asStateFlow()

    fun draftListingSmoke() {
        viewModelScope.launch {
            for ((label, pop) in listOf("Aang (normal)" to testPop("Fire Nation Aang", listOf("Avatar The Last Airbender")),
                "Pennywise (horror)" to testPop("Pennywise with Spider Legs", listOf("IT")))) {
                _listing.value = listOf("$label…")
                listingWriter.draft(pop).collect { r ->
                    when (r) {
                        is InferenceResult.Streaming -> _listing.value = listOf("$label ›", r.partialText)
                        is InferenceResult.Success -> {
                            Log.i("ListingSmoke", "$label OK (${r.metadata.totalLatencyMs}ms): ${r.fullText}")
                            _listing.value = listOf("$label ✓ ${r.metadata.totalLatencyMs}ms", r.fullText)
                        }
                        is InferenceResult.Error -> {
                            Log.i("ListingSmoke", "$label ERROR: ${r.cause.message}")
                            _listing.value = listOf("$label ✗ ${r.cause.message}")
                        }
                    }
                }
            }
        }
    }

    private fun testPop(name: String, series: List<String>) = FunkoPop(
        localId = 0, name = name, brand = "Funko", imageUrl = null, estimatedValueCents = 1000,
        lastKnownValueCents = null, quantity = 1, itemCondition = "Mint", packagingCondition = "Mint",
        series = series, productionTags = emptyList(), dateAdded = LocalDate.of(2023, 1, 1),
        pricePaidCents = null, storageLocation = null, upc = "0", popNumber = "1",
        exclusiveTo = null, isNftRedeemable = false,
    )
}

/** Weekly-sync status for the Settings row: last price refresh, and the worker's next scheduled run. */
data class SyncStatus(val lastSyncedAt: Instant? = null, val nextSyncAt: Instant? = null)

/** Debug dev-harness state for the RAG index + query. */
data class RagHarnessState(
    val indexCount: Int = 0,
    val building: Boolean = false,
    val log: List<String> = emptyList(),
)

/** Debug dev-harness state for the capture cascade. */
data class CascadeHarnessState(
    val running: Boolean = false,
    val log: List<String> = emptyList(),
    val subject: Bitmap? = null,
    val summary: String? = null,
    val telemetry: String? = null,
)
