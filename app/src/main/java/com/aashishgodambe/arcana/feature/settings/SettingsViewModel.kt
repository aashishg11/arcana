package com.aashishgodambe.arcana.feature.settings

import android.content.Context
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.aashishgodambe.arcana.core.ai.OwnModelEngine
import com.aashishgodambe.arcana.core.ai.capability.DeviceCapabilityChecker
import com.aashishgodambe.arcana.core.ai.capability.ModelReadiness
import com.aashishgodambe.arcana.core.ai.model.AskEngine
import com.aashishgodambe.arcana.core.data.settings.SettingsStore
import com.aashishgodambe.arcana.core.data.settings.ThemeMode
import com.aashishgodambe.arcana.core.data.worker.WeeklyPriceSyncScheduler
import dagger.hilt.android.lifecycle.HiltViewModel
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import javax.inject.Inject

@HiltViewModel
class SettingsViewModel @Inject constructor(
    private val settings: SettingsStore,
    private val syncScheduler: WeeklyPriceSyncScheduler,
    private val capability: DeviceCapabilityChecker,
    private val ownModel: OwnModelEngine,
    @ApplicationContext context: Context,
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
}
