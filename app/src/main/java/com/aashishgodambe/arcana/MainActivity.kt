package com.aashishgodambe.arcana

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.runtime.getValue
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.aashishgodambe.arcana.core.data.settings.SettingsStore
import com.aashishgodambe.arcana.core.data.settings.ThemeMode
import com.aashishgodambe.arcana.ui.navigation.ArcanaNavHost
import com.aashishgodambe.arcana.ui.theme.ArcanaTheme
import dagger.hilt.android.AndroidEntryPoint
import javax.inject.Inject

@AndroidEntryPoint
class MainActivity : ComponentActivity() {

    @Inject lateinit var settings: SettingsStore

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge() // adapts system-bar icon color to the active (light/dark) theme
        setContent {
            val mode by settings.themeMode.collectAsStateWithLifecycle()
            val dark = when (mode) {
                ThemeMode.System -> isSystemInDarkTheme()
                ThemeMode.Light -> false
                ThemeMode.Dark -> true
            }
            ArcanaTheme(darkTheme = dark) {
                ArcanaNavHost()
            }
        }
    }
}
