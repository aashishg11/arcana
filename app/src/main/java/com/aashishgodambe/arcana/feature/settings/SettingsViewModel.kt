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
import com.aashishgodambe.arcana.core.data.settings.SettingsStore
import com.aashishgodambe.arcana.core.data.settings.ThemeMode
import com.aashishgodambe.arcana.core.data.worker.WeeklyPriceSyncScheduler
import dagger.hilt.android.lifecycle.HiltViewModel
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import javax.inject.Inject

@HiltViewModel
class SettingsViewModel @Inject constructor(
    private val settings: SettingsStore,
    private val syncScheduler: WeeklyPriceSyncScheduler,
    private val capability: DeviceCapabilityChecker,
    private val ownModel: OwnModelEngine,
    private val captureCascade: CaptureCascade,
    @ApplicationContext private val context: Context,
) : ViewModel() {

    val weeklyWorkerEnabled: StateFlow<Boolean> = settings.weeklyWorkerEnabled
    val themeMode: StateFlow<ThemeMode> = settings.themeMode

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
                    render()
                }
            }.onFailure {
                log += "✗ ${it.message}"
                render(done = true)
            }
        }
    }
}

/** Debug dev-harness state for the capture cascade. */
data class CascadeHarnessState(
    val running: Boolean = false,
    val log: List<String> = emptyList(),
    val subject: Bitmap? = null,
    val summary: String? = null,
    val telemetry: String? = null,
)
