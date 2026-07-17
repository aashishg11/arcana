package com.aashishgodambe.arcana.feature.settings

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.Image
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
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Switch
import androidx.compose.material3.SwitchDefaults
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import java.time.Duration
import java.time.Instant
import com.aashishgodambe.arcana.BuildConfig
import com.aashishgodambe.arcana.core.ai.LiteRtGeminiService
import com.aashishgodambe.arcana.core.ai.rag.EmbeddingBenchmark
import com.aashishgodambe.arcana.core.ai.rag.EmbeddingGemmaEncoder
import com.aashishgodambe.arcana.core.ai.capability.ModelReadiness
import com.aashishgodambe.arcana.core.ai.model.AskEngine
import com.aashishgodambe.arcana.core.ai.model.InferenceResult
import com.aashishgodambe.arcana.core.ai.model.RoutingHint
import kotlinx.coroutines.launch
import com.aashishgodambe.arcana.core.ai.model.InferenceLocation
import com.aashishgodambe.arcana.core.data.settings.ThemeMode
import com.aashishgodambe.arcana.ui.component.ArcanaChip
import com.aashishgodambe.arcana.ui.component.ChipStyle
import com.aashishgodambe.arcana.ui.component.InferenceBadge
import com.aashishgodambe.arcana.ui.theme.ArcanaTheme
import com.aashishgodambe.arcana.ui.theme.Mono

private const val GITHUB_URL = "https://github.com/aashishg11/arcana"

/** "Last synced 2h ago · auto-sync in 3d" — or "· auto-sync off" when the worker is disabled. The two are
 *  independent clocks (last real sync vs. the background schedule), so the label keeps them distinct. */
private fun syncStatusLine(status: SyncStatus, enabled: Boolean): String {
    val last = status.lastSyncedAt?.let { "Last synced ${agoLabel(it)}" } ?: "Not synced yet"
    val next = when {
        !enabled -> "auto-sync off"
        status.nextSyncAt != null -> "auto-sync ${untilLabel(status.nextSyncAt)}"
        else -> "auto-sync pending"
    }
    return "$last · $next"
}

private fun agoLabel(at: Instant): String {
    val d = Duration.between(at, Instant.now())
    return when {
        d.isNegative || d.toMinutes() < 1 -> "just now"
        d.toHours() < 1 -> "${d.toMinutes()}m ago"
        d.toDays() < 1 -> "${d.toHours()}h ago"
        d.toDays() < 30 -> "${d.toDays()}d ago"
        else -> "30d+ ago"
    }
}

private fun untilLabel(at: Instant): String {
    val d = Duration.between(Instant.now(), at)
    return when {
        d.isNegative || d.toMinutes() < 1 -> "shortly"
        d.toHours() < 1 -> "in ${d.toMinutes()}m"
        d.toDays() < 1 -> "in ${d.toHours()}h"
        else -> "in ${d.toDays()}d"
    }
}

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
    val askEngine by vm.askEngine.collectAsStateWithLifecycle()
    val ownModelAvailable by vm.ownModelAvailable.collectAsStateWithLifecycle()
    val syncStatus by vm.syncStatus.collectAsStateWithLifecycle()
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
                        Spacer(Modifier.height(6.dp))
                        Text(
                            syncStatusLine(syncStatus, workerEnabled),
                            fontFamily = Mono, fontSize = 11.sp, color = c.textDim, lineHeight = 15.sp,
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

            // --- Ask Arcana engine picker ---
            Spacer(Modifier.height(18.dp))
            SectionLabel("ASK ARCANA ENGINE")
            SettingCard {
                EngineOption(
                    title = "Gemini Nano",
                    subtitle = "On-device · zero app memory · default",
                    selected = askEngine == AskEngine.Nano,
                    enabled = true,
                    accent = c.iris,
                ) { vm.setAskEngine(AskEngine.Nano) }
                EngineDivider()
                EngineOption(
                    title = "Your Gemma",
                    subtitle = if (ownModelAvailable) {
                        "Self-quantized Gemma 3 1B · LiteRT q4, on-device"
                    } else {
                        "Developer feature — model side-loaded"
                    },
                    selected = askEngine == AskEngine.OwnModel,
                    enabled = ownModelAvailable,
                    accent = c.gold,
                ) { vm.setAskEngine(AskEngine.OwnModel) }
                EngineDivider()
                EngineOption(
                    title = "Cloud",
                    subtitle = "Gemini 3.1 Flash-Lite · cloud fallback",
                    selected = askEngine == AskEngine.Cloud,
                    enabled = true,
                    accent = c.cloud,
                ) { vm.setAskEngine(AskEngine.Cloud) }
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
                        Text("On-device vs cloud latency, p50.", fontFamily = Mono, fontSize = 11.sp, color = c.textFaint)
                    }
                    Text("›", color = c.textFaint, fontSize = 20.sp, fontWeight = FontWeight.Bold)
                }
            }

            // Day-1 throwaway: proves the MediaPipe/LiteRT surface loads the side-loaded q4 model and
            // generates a token in-app. Debug-only; removed when LiteRtGeminiService (Day 2) lands.
            if (BuildConfig.DEBUG) {
                Spacer(Modifier.height(10.dp))
                LiteRtSmokeCard()
                Spacer(Modifier.height(10.dp))
                EmbeddingSmokeCard()
                Spacer(Modifier.height(10.dp))
                RagIndexCard(vm)
                Spacer(Modifier.height(10.dp))
                ListingWriterCard(vm)
                Spacer(Modifier.height(10.dp))
                CascadeHarnessCard(vm)
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
private fun EngineOption(
    title: String,
    subtitle: String,
    selected: Boolean,
    enabled: Boolean,
    accent: Color,
    onSelect: () -> Unit,
) {
    val c = ArcanaTheme.colors
    Row(
        Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(10.dp))
            .then(if (enabled) Modifier.clickable(onClick = onSelect) else Modifier)
            .padding(vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(
            Modifier.size(20.dp).clip(CircleShape)
                .border(2.dp, if (selected) accent else c.hairlineStrong, CircleShape),
            contentAlignment = Alignment.Center,
        ) {
            if (selected) Box(Modifier.size(10.dp).clip(CircleShape).background(accent))
        }
        Spacer(Modifier.width(12.dp))
        Column(Modifier.weight(1f)) {
            Text(
                title,
                fontWeight = FontWeight.SemiBold, fontSize = 15.sp,
                color = if (enabled) c.text else c.textFaint,
            )
            Spacer(Modifier.height(2.dp))
            Text(subtitle, fontFamily = Mono, fontSize = 11.sp, color = c.textFaint, lineHeight = 15.sp)
        }
    }
}

@Composable
private fun EngineDivider() {
    Box(Modifier.fillMaxWidth().height(1.dp).background(ArcanaTheme.colors.hairline))
}

@Composable
private fun LiteRtSmokeCard() {
    val c = ArcanaTheme.colors
    val context = LocalContext.current
    val service = remember { LiteRtGeminiService(context.applicationContext) }
    val scope = rememberCoroutineScope()
    var running by remember { mutableStateOf(false) }
    var status by remember { mutableStateOf("Streams a token from the side-loaded q4 Gemma via LiteRtGeminiService (CPU).") }

    SettingCard {
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Text("LiteRT smoke test", fontWeight = FontWeight.SemiBold, fontSize = 15.sp, color = c.text)
                Spacer(Modifier.height(3.dp))
                Text(status, fontFamily = Mono, fontSize = 11.sp, color = c.textFaint, lineHeight = 15.sp)
            }
            Spacer(Modifier.width(12.dp))
            Text(
                if (running) "Running…" else "Run",
                fontFamily = Mono, fontSize = 12.sp, fontWeight = FontWeight.Medium,
                color = if (running) c.textDim else c.iris,
                modifier = Modifier
                    .clip(RoundedCornerShape(8.dp))
                    .border(1.dp, c.hairline, RoundedCornerShape(8.dp))
                    .clickable(enabled = !running) {
                        running = true
                        status = if (service.isModelAvailable()) "Loading model…" else "Model not installed — side-load it."
                        scope.launch {
                            service.generateText(
                                "What is the capital of France? Answer in one word.",
                                RoutingHint.Auto,
                            ).collect { r ->
                                status = when (r) {
                                    is InferenceResult.Streaming -> "… ${r.partialText}"
                                    is InferenceResult.Success -> {
                                        val m = r.metadata
                                        "OK · first ${m.firstTokenLatencyMs}ms · total ${m.totalLatencyMs}ms · " +
                                            "${m.outputTokenCount ?: "n/a"} tok\n→ ${r.fullText}"
                                    }
                                    is InferenceResult.Error -> "Failed: ${r.cause.message}"
                                }
                            }
                            running = false
                        }
                    }
                    .padding(horizontal = 12.dp, vertical = 6.dp),
            )
        }
    }
}

@Composable
private fun EmbeddingSmokeCard() {
    val c = ArcanaTheme.colors
    val context = LocalContext.current
    val encoder = remember { EmbeddingGemmaEncoder(context.applicationContext) }
    val scope = rememberCoroutineScope()
    var running by remember { mutableStateOf(false) }
    var status by remember {
        mutableStateOf("Embeds sample docs on-device (EmbeddingGemma/LiteRT) and benchmarks 768/256/128 retrieval.")
    }

    SettingCard {
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Text("Embedding benchmark", fontWeight = FontWeight.SemiBold, fontSize = 15.sp, color = c.text)
                Spacer(Modifier.height(3.dp))
                Text(status, fontFamily = Mono, fontSize = 11.sp, color = c.textFaint, lineHeight = 15.sp)
            }
            Spacer(Modifier.width(12.dp))
            Text(
                if (running) "Running…" else "Run",
                fontFamily = Mono, fontSize = 12.sp, fontWeight = FontWeight.Medium,
                color = if (running) c.textDim else c.iris,
                modifier = Modifier
                    .clip(RoundedCornerShape(8.dp))
                    .border(1.dp, c.hairline, RoundedCornerShape(8.dp))
                    .clickable(enabled = !running) {
                        running = true
                        status = if (encoder.isModelAvailable()) "Embedding…" else "Model not installed — side-load it."
                        scope.launch {
                            status = runCatching { EmbeddingBenchmark.run(encoder) }
                                .getOrElse { "Failed: ${it.message}" }
                            running = false
                        }
                    }
                    .padding(horizontal = 12.dp, vertical = 6.dp),
            )
        }
    }
}

@Composable
private fun RagIndexCard(vm: SettingsViewModel) {
    val c = ArcanaTheme.colors
    val state by vm.rag.collectAsStateWithLifecycle()
    var query by remember { mutableStateOf("") }

    SettingCard {
        Column(Modifier.fillMaxWidth()) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Text("RAG index", fontWeight = FontWeight.SemiBold, fontSize = 15.sp, color = c.text)
                    Spacer(Modifier.height(3.dp))
                    Text(
                        "${state.indexCount} vectors · semantic search over your collection",
                        fontFamily = Mono, fontSize = 11.sp, color = c.textFaint, lineHeight = 15.sp,
                    )
                }
                HarnessButton(if (state.building) "…" else "Build", enabled = !state.building) { vm.buildIndex() }
            }
            Spacer(Modifier.height(10.dp))
            Row(verticalAlignment = Alignment.CenterVertically) {
                OutlinedTextField(
                    value = query,
                    onValueChange = { query = it },
                    placeholder = { Text("dragons, airbender…", fontFamily = Mono, fontSize = 12.sp) },
                    singleLine = true,
                    modifier = Modifier.weight(1f),
                )
                Spacer(Modifier.width(8.dp))
                HarnessButton("Ask", enabled = !state.building) { vm.queryRag(query) }
            }
            Spacer(Modifier.height(8.dp))
            HarnessButton("Doc-shape A/B", enabled = !state.building) { vm.docShapeAB() }
            if (state.log.isNotEmpty()) {
                Spacer(Modifier.height(10.dp))
                state.log.forEach { Text(it, fontFamily = Mono, fontSize = 11.sp, color = c.textDim, lineHeight = 16.sp) }
            }
        }
    }
}

@Composable
private fun ListingWriterCard(vm: SettingsViewModel) {
    val c = ArcanaTheme.colors
    val lines by vm.listing.collectAsStateWithLifecycle()

    SettingCard {
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Text("Listing writer", fontWeight = FontWeight.SemiBold, fontSize = 15.sp, color = c.text)
                Spacer(Modifier.height(3.dp))
                lines.forEach { Text(it, fontFamily = Mono, fontSize = 11.sp, color = c.textDim, lineHeight = 16.sp) }
            }
            Spacer(Modifier.width(12.dp))
            HarnessButton("Draft") { vm.draftListingSmoke() }
        }
    }
}

/** The small mono outline button used across the debug harness cards. */
@Composable
private fun HarnessButton(label: String, enabled: Boolean = true, onClick: () -> Unit) {
    val c = ArcanaTheme.colors
    Text(
        label,
        fontFamily = Mono, fontSize = 12.sp, fontWeight = FontWeight.Medium,
        color = if (enabled) c.iris else c.textDim,
        modifier = Modifier
            .clip(RoundedCornerShape(8.dp))
            .border(1.dp, c.hairline, RoundedCornerShape(8.dp))
            .clickable(enabled = enabled, onClick = onClick)
            .padding(horizontal = 12.dp, vertical = 6.dp),
    )
}

@Composable
private fun CascadeHarnessCard(vm: SettingsViewModel) {
    val c = ArcanaTheme.colors
    val state by vm.cascade.collectAsStateWithLifecycle()
    val picker = rememberLauncherForActivityResult(ActivityResultContracts.PickVisualMedia()) { uri ->
        uri?.let { vm.runCascade(it) }
    }

    SettingCard {
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Text("Capture cascade", fontWeight = FontWeight.SemiBold, fontSize = 15.sp, color = c.text)
                Spacer(Modifier.height(3.dp))
                Text(
                    "Pick a Funko box photo and watch it identify — segment · describe · OCR · match.",
                    fontFamily = Mono, fontSize = 11.sp, color = c.textFaint, lineHeight = 15.sp,
                )
            }
            Spacer(Modifier.width(12.dp))
            Text(
                if (state.running) "Running…" else "Pick",
                fontFamily = Mono, fontSize = 12.sp, fontWeight = FontWeight.Medium,
                color = if (state.running) c.textDim else c.iris,
                modifier = Modifier
                    .clip(RoundedCornerShape(8.dp))
                    .border(1.dp, c.hairline, RoundedCornerShape(8.dp))
                    .clickable(enabled = !state.running) {
                        picker.launch(PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly))
                    }
                    .padding(horizontal = 12.dp, vertical = 6.dp),
            )
        }

        state.subject?.let { subject ->
            Spacer(Modifier.height(10.dp))
            Image(
                bitmap = subject.asImageBitmap(),
                contentDescription = "segmented subject",
                modifier = Modifier.height(120.dp).clip(RoundedCornerShape(10.dp)),
            )
        }

        if (state.log.isNotEmpty()) {
            Spacer(Modifier.height(10.dp))
            state.log.forEach { line ->
                Text(line, fontFamily = Mono, fontSize = 11.sp, color = c.textDim, lineHeight = 16.sp)
            }
        }

        state.summary?.let { summary ->
            Spacer(Modifier.height(8.dp))
            Text(summary, fontWeight = FontWeight.SemiBold, fontSize = 13.sp, color = c.iris)
            state.telemetry?.let { t ->
                Text(t, fontFamily = Mono, fontSize = 10.sp, color = c.textFaint, lineHeight = 14.sp)
            }
        }
    }
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
