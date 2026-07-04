package com.aashishgodambe.arcana

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import com.aashishgodambe.arcana.ui.navigation.ArcanaNavHost
import com.aashishgodambe.arcana.ui.theme.ArcanaTheme
import dagger.hilt.android.AndroidEntryPoint

@AndroidEntryPoint
class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge() // adapts system-bar icon color to the active (light/dark) theme
        setContent {
            ArcanaTheme {
                ArcanaNavHost()
            }
        }
    }
}
