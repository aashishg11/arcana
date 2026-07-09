package com.aashishgodambe.arcana.feature.settings

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Switch
import androidx.compose.material3.SwitchDefaults
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.aashishgodambe.arcana.core.ai.capability.ModelReadiness
import com.aashishgodambe.arcana.core.ai.model.InferenceLocation
import com.aashishgodambe.arcana.core.data.settings.ThemeMode
import com.aashishgodambe.arcana.ui.component.ArcanaChip
import com.aashishgodambe.arcana.ui.component.ChipStyle
import com.aashishgodambe.arcana.ui.component.InferenceBadge
import com.aashishgodambe.arcana.ui.theme.ArcanaTheme
import com.aashishgodambe.arcana.ui.theme.Mono

private const val GITHUB_URL = "https://github.com/aashishg11/arcana"

@Composable
fun SettingsScreen(
    onBack: () -> Unit,
    onOpenBenchmark: () -> Unit,
    vm: SettingsViewModel = hiltViewModel(),
) {
    val c = ArcanaTheme.colors
    val workerEnabled by vm.weeklyWorkerEnabled.collectAsStateWithLifecycle()
    val themeMode by vm.themeMode.collectAsStateWithLifecycle()
    val readiness by vm.onDeviceReadiness.collectAsStateWithLifecycle()
    var licensesOpen by remember { mutableStateOf(false) }
    val uriHandler = LocalUriHandler.current

    Scaffold(containerColor = c.bg) { padding ->
        Column(
            Modifier.padding(padding).fillMaxSize().verticalScroll(rememberScrollState()).padding(horizontal = 20.dp),
        ) {
            Row(Modifier.fillMaxWidth().padding(top = 8.dp, bottom = 10.dp), verticalAlignment = Alignment.CenterVertically) {
                Box(
                    Modifier.size(38.dp).clip(RoundedCornerShape(12.dp)).background(c.surface)
                        .border(1.dp, c.hairline, RoundedCornerShape(12.dp)).clickable(onClick = onBack),
                    contentAlignment = Alignment.Center,
                ) { Text("‹", color = c.textDim, fontSize = 20.sp, fontWeight = FontWeight.Bold) }
                Spacer(Modifier.width(12.dp))
                Text("Settings", fontWeight = FontWeight.ExtraBold, fontSize = 22.sp, color = c.text)
            }

            // --- Weekly price sync ---
            SectionLabel("PRICE TRACKING")
            SettingCard {
                Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                    Column(Modifier.weight(1f)) {
                        Text("Weekly price sync", fontWeight = FontWeight.SemiBold, fontSize = 15.sp, color = c.text)
                        Spacer(Modifier.height(3.dp))
                        Text(
                            "Refresh values in the background once a week (Wi-Fi only).",
                            fontFamily = Mono, fontSize = 11.sp, color = c.textFaint, lineHeight = 15.sp,
                        )
                    }
                    Spacer(Modifier.width(12.dp))
                    Switch(
                        checked = workerEnabled,
                        onCheckedChange = vm::setWeeklyWorkerEnabled,
                        colors = SwitchDefaults.colors(
                            checkedThumbColor = Color.White,
                            checkedTrackColor = c.iris,
                            uncheckedThumbColor = c.textDim,
                            uncheckedTrackColor = c.surface,
                            uncheckedBorderColor = c.hairlineStrong,
                        ),
                    )
                }
            }

            // --- On-device AI status ---
            Spacer(Modifier.height(18.dp))
            SectionLabel("ON-DEVICE AI")
            SettingCard {
                Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                    Column(Modifier.weight(1f)) {
                        Text("Gemini Nano", fontWeight = FontWeight.SemiBold, fontSize = 15.sp, color = c.text)
                        Spacer(Modifier.height(3.dp))
                        Text(readiness.detail(), fontFamily = Mono, fontSize = 11.sp, color = c.textFaint, lineHeight = 15.sp)
                    }
                    Spacer(Modifier.width(12.dp))
                    ReadinessChip(readiness)
                }
                Spacer(Modifier.height(10.dp))
                Text(
                    "Re-check",
                    fontFamily = Mono, fontSize = 11.sp, color = c.iris,
                    modifier = Modifier.clickable(onClick = vm::refreshReadiness),
                )
            }

            // --- Theme ---
            Spacer(Modifier.height(18.dp))
            SectionLabel("THEME")
            SettingCard {
                Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                    Text("Appearance", fontWeight = FontWeight.SemiBold, fontSize = 15.sp, color = c.text, modifier = Modifier.weight(1f))
                    ThemeSegmented(themeMode, vm::setThemeMode)
                }
            }

            // --- Developer / benchmark ---
            Spacer(Modifier.height(18.dp))
            SectionLabel("DEVELOPER")
            SettingCard(onClick = onOpenBenchmark) {
                Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                    Column(Modifier.weight(1f)) {
                        Text("Benchmark", fontWeight = FontWeight.SemiBold, fontSize = 15.sp, color = c.text)
                        Spacer(Modifier.height(3.dp))
                        Text("On-device vs cloud latency, p50 / p95.", fontFamily = Mono, fontSize = 11.sp, color = c.textFaint)
                    }
                    Text("›", color = c.textFaint, fontSize = 20.sp, fontWeight = FontWeight.Bold)
                }
            }

            // --- About ---
            Spacer(Modifier.height(18.dp))
            SectionLabel("ABOUT")
            SettingCard {
                AboutRow("Version", "v${vm.appVersion}")
                Spacer(Modifier.height(12.dp))
                Text(
                    "View source on GitHub",
                    fontWeight = FontWeight.Medium, fontSize = 14.sp, color = c.iris,
                    modifier = Modifier.fillMaxWidth().clickable { uriHandler.openUri(GITHUB_URL) },
                )
                Spacer(Modifier.height(12.dp))
                Text(
                    "Open-source licenses",
                    fontWeight = FontWeight.Medium, fontSize = 14.sp, color = c.textDim,
                    modifier = Modifier.fillMaxWidth().clickable { licensesOpen = true },
                )
            }

            Spacer(Modifier.height(40.dp))
        }
    }

    if (licensesOpen) {
        AlertDialog(
            onDismissRequest = { licensesOpen = false },
            confirmButton = { TextButton(onClick = { licensesOpen = false }) { Text("Close", color = c.iris) } },
            title = { Text("Open-source licenses", color = c.text, fontWeight = FontWeight.Bold) },
            text = {
                Text(
                    "Arcana is built with Jetpack Compose, Hilt, Room, Coil, Firebase AI Logic, and ML Kit " +
                        "GenAI — each under its own open-source license (Apache 2.0 / MIT). Fonts: Bricolage " +
                        "Grotesque, Inter, and JetBrains Mono (SIL OFL).",
                    fontFamily = Mono, fontSize = 12.sp, color = c.textDim, lineHeight = 18.sp,
                )
            },
            containerColor = c.surface,
        )
    }
}

private fun ModelReadiness.detail(): String = when (this) {
    ModelReadiness.Available -> "Ready — inference runs privately on your device."
    ModelReadiness.Downloading -> "Downloading the on-device model…"
    ModelReadiness.Downloadable -> "Not downloaded — falling back to cloud."
    ModelReadiness.Unavailable -> "Not available on this device — using cloud."
    ModelReadiness.Unknown -> "Checking availability…"
}

@Composable
private fun ReadinessChip(readiness: ModelReadiness) = when (readiness) {
    ModelReadiness.Available -> InferenceBadge(InferenceLocation.OnDevice)
    ModelReadiness.Downloading -> ArcanaChip("Downloading…", ChipStyle.Gold)
    ModelReadiness.Downloadable -> ArcanaChip("Cloud", ChipStyle.Cloud)
    ModelReadiness.Unavailable -> ArcanaChip("Cloud", ChipStyle.Cloud)
    ModelReadiness.Unknown -> ArcanaChip("Checking…", ChipStyle.Plain)
}

@Composable
private fun ThemeSegmented(selected: ThemeMode, onSelect: (ThemeMode) -> Unit) {
    val c = ArcanaTheme.colors
    Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
        ThemeMode.entries.forEach { mode ->
            val on = mode == selected
            Text(
                mode.name,
                fontFamily = Mono, fontSize = 11.sp, fontWeight = FontWeight.Medium,
                color = if (on) Color.White else c.textDim,
                modifier = Modifier
                    .clip(RoundedCornerShape(8.dp))
                    .then(if (on) Modifier.background(c.iris) else Modifier.border(1.dp, c.hairline, RoundedCornerShape(8.dp)))
                    .clickable { onSelect(mode) }
                    .padding(horizontal = 10.dp, vertical = 5.dp),
            )
        }
    }
}

@Composable
private fun AboutRow(label: String, value: String) {
    val c = ArcanaTheme.colors
    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
        Text(label, fontSize = 14.sp, color = c.text, modifier = Modifier.weight(1f))
        Text(value, fontFamily = Mono, fontSize = 13.sp, color = c.textDim)
    }
}

@Composable
private fun SectionLabel(text: String) {
    Text(text, fontFamily = Mono, fontSize = 11.sp, color = ArcanaTheme.colors.textFaint, letterSpacing = 1.sp)
    Spacer(Modifier.height(10.dp))
}

@Composable
private fun SettingCard(onClick: (() -> Unit)? = null, content: @Composable () -> Unit) {
    val c = ArcanaTheme.colors
    Column(
        Modifier.fillMaxWidth().clip(RoundedCornerShape(16.dp)).background(c.surface)
            .border(1.dp, c.hairline, RoundedCornerShape(16.dp))
            .then(if (onClick != null) Modifier.clickable(onClick = onClick) else Modifier)
            .padding(16.dp),
    ) { content() }
}
