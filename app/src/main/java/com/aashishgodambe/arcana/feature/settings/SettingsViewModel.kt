package com.aashishgodambe.arcana.feature.settings

import android.content.Context
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.aashishgodambe.arcana.core.ai.capability.DeviceCapabilityChecker
import com.aashishgodambe.arcana.core.ai.capability.ModelReadiness
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
    @ApplicationContext context: Context,
) : ViewModel() {

    val weeklyWorkerEnabled: StateFlow<Boolean> = settings.weeklyWorkerEnabled
    val themeMode: StateFlow<ThemeMode> = settings.themeMode

    private val _readiness = MutableStateFlow(ModelReadiness.Unknown)
    val onDeviceReadiness: StateFlow<ModelReadiness> = _readiness.asStateFlow()

    val appVersion: String =
        runCatching { context.packageManager.getPackageInfo(context.packageName, 0).versionName }
            .getOrNull() ?: "1.0"

    init {
        refreshReadiness()
    }

    fun refreshReadiness() {
        viewModelScope.launch { _readiness.value = capability.onDeviceReadiness() }
    }

    /** Persist the choice AND actually schedule/cancel the worker — the toggle isn't cosmetic. */
    fun setWeeklyWorkerEnabled(enabled: Boolean) {
        settings.setWeeklyWorkerEnabled(enabled)
        if (enabled) syncScheduler.schedule() else syncScheduler.cancel()
    }

    fun setThemeMode(mode: ThemeMode) = settings.setThemeMode(mode)
}
