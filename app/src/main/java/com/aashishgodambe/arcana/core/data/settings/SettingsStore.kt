package com.aashishgodambe.arcana.core.data.settings

import android.content.Context
import com.aashishgodambe.arcana.core.ai.model.AskEngine
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import javax.inject.Inject
import javax.inject.Singleton

/** How the app resolves its palette: follow the OS, or force one. Both palettes are designed. */
enum class ThemeMode { System, Light, Dark }

/**
 * Small persistent user-settings store (SharedPreferences) — the source of truth for the Settings screen.
 * Values are exposed as [StateFlow]s (initialized from prefs, write-through on set) so the UI and the app
 * root can observe them; settings only change from within the app, so no cross-process listener is needed.
 */
@Singleton
class SettingsStore @Inject constructor(@ApplicationContext context: Context) {

    private val prefs = context.getSharedPreferences("arcana_settings", Context.MODE_PRIVATE)

    private val _weeklyWorkerEnabled = MutableStateFlow(prefs.getBoolean(KEY_WORKER, true))
    /** Whether the weekly background price-sync worker should be scheduled. Defaults on. */
    val weeklyWorkerEnabled: StateFlow<Boolean> = _weeklyWorkerEnabled.asStateFlow()

    fun setWeeklyWorkerEnabled(enabled: Boolean) {
        prefs.edit().putBoolean(KEY_WORKER, enabled).apply()
        _weeklyWorkerEnabled.value = enabled
    }

    private val _themeMode = MutableStateFlow(readThemeMode())
    val themeMode: StateFlow<ThemeMode> = _themeMode.asStateFlow()

    fun setThemeMode(mode: ThemeMode) {
        prefs.edit().putString(KEY_THEME, mode.name).apply()
        _themeMode.value = mode
    }

    private fun readThemeMode(): ThemeMode =
        runCatching { ThemeMode.valueOf(prefs.getString(KEY_THEME, null) ?: ThemeMode.System.name) }
            .getOrDefault(ThemeMode.System)

    private val _askEngine = MutableStateFlow(readAskEngine())
    /** Which engine powers Ask Arcana. Defaults to Nano — zero app-resident memory; own-model is opt-in. */
    val askEngine: StateFlow<AskEngine> = _askEngine.asStateFlow()

    fun setAskEngine(engine: AskEngine) {
        prefs.edit().putString(KEY_ENGINE, engine.name).apply()
        _askEngine.value = engine
    }

    private fun readAskEngine(): AskEngine =
        runCatching { AskEngine.valueOf(prefs.getString(KEY_ENGINE, null) ?: AskEngine.Nano.name) }
            .getOrDefault(AskEngine.Nano)

    private companion object {
        const val KEY_WORKER = "weekly_worker_enabled"
        const val KEY_THEME = "theme_mode"
        const val KEY_ENGINE = "ask_engine"
    }
}
