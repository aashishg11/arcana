package com.aashishgodambe.arcana.feature.benchmark

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
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.aashishgodambe.arcana.core.ai.benchmark.BenchmarkEngine
import com.aashishgodambe.arcana.core.ai.benchmark.BenchmarkResult
import com.aashishgodambe.arcana.core.ai.capability.ModelReadiness
import com.aashishgodambe.arcana.core.ai.capability.ProvisioningProgress
import com.aashishgodambe.arcana.core.ai.model.InferenceLocation
import com.aashishgodambe.arcana.ui.component.ArcanaChip
import com.aashishgodambe.arcana.ui.component.ChipStyle
import com.aashishgodambe.arcana.ui.component.InferenceBadge
import com.aashishgodambe.arcana.ui.component.PillButton
import com.aashishgodambe.arcana.ui.theme.ArcanaTheme
import com.aashishgodambe.arcana.ui.theme.Mono

/** Each benchmark engine's badge colour: iris Nano / gold own-model / cloud. */
private fun BenchmarkEngine.badgeLocation(): InferenceLocation = when (this) {
    BenchmarkEngine.OnDevice -> InferenceLocation.OnDevice
    BenchmarkEngine.OwnModel -> InferenceLocation.OnDeviceOwnModel
    BenchmarkEngine.Cloud -> InferenceLocation.Cloud
}

/** null → "n/a"; sub-second in ms, else seconds — Mono, so columns align. */
private fun latency(ms: Long?): String = when {
    ms == null -> "n/a"
    ms < 1000 -> "$ms ms"
    else -> "%.2f s".format(ms / 1000.0)
}

@Composable
fun BenchmarkScreen(
    onBack: () -> Unit,
    vm: BenchmarkViewModel = hiltViewModel(),
) {
    val c = ArcanaTheme.colors
    val s by vm.state.collectAsStateWithLifecycle()

    Scaffold(containerColor = c.bg) { padding ->
        Column(
            Modifier
                .padding(padding)
                .fillMaxSize()
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 20.dp),
        ) {
            // app bar
            Row(Modifier.fillMaxWidth().padding(top = 8.dp, bottom = 6.dp), verticalAlignment = Alignment.CenterVertically) {
                Box(
                    Modifier.size(38.dp).clip(RoundedCornerShape(12.dp)).background(c.surface)
                        .border(1.dp, c.hairline, RoundedCornerShape(12.dp)).clickable(onClick = onBack),
                    contentAlignment = Alignment.Center,
                ) { Text("‹", color = c.textDim, fontSize = 20.sp, fontWeight = FontWeight.Bold) }
                Spacer(Modifier.width(12.dp))
                Text("Benchmark", fontWeight = FontWeight.ExtraBold, fontSize = 22.sp, color = c.text)
            }

            Spacer(Modifier.height(4.dp))
            Text(
                "Nano (on-device) vs your Gemma (LiteRT q4) vs cloud — first-token and total latency, p50. " +
                    "Lower is faster; the fastest engine in each row is green.",
                fontSize = 14.sp, color = c.textDim, lineHeight = 20.sp,
            )

            Spacer(Modifier.height(16.dp))
            ReadinessRow(s.onDeviceReadiness, s.provisioning, onDownload = vm::downloadOnDeviceModel)

            Spacer(Modifier.height(14.dp))
            if (s.isRunning) {
                RunningCard(s)
            } else {
                PillButton(
                    text = if (s.results.isEmpty()) "Run benchmark" else "Run again",
                    onClick = vm::runBenchmark,
                )
            }

            if (s.results.isNotEmpty()) {
                Spacer(Modifier.height(22.dp))
                ResultsSection(s.results)
            } else if (!s.isRunning) {
                Spacer(Modifier.height(18.dp))
                IdleHint()
            }

            Spacer(Modifier.height(22.dp))
            Text(
                "p50 over warm samples per cell (8 on-device / own-model, 4 cloud) — indicative, not " +
                    "production-grade statistics. Cloud runs fewer iterations to conserve the free-tier budget. " +
                    "Cold-start (first call in the process) and token counts are listed under the matrix; your " +
                    "Gemma's one-time ~3 s model load happens before inference and isn't included. Nano never " +
                    "reports token counts; your Gemma and cloud do. Own-model column appears only when the model " +
                    "is side-loaded.",
                fontFamily = Mono, fontSize = 10.sp, color = c.textFaint, lineHeight = 15.sp,
            )
            Spacer(Modifier.height(40.dp))
        }
    }
}

@Composable
private fun ReadinessRow(
    readiness: ModelReadiness,
    provisioning: ProvisioningProgress?,
    onDownload: () -> Unit,
) {
    val c = ArcanaTheme.colors
    Column(
        Modifier.fillMaxWidth().clip(RoundedCornerShape(16.dp)).background(c.surface)
            .border(1.dp, c.hairline, RoundedCornerShape(16.dp)).padding(14.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            Text("ON-DEVICE MODEL", fontFamily = Mono, fontSize = 11.sp, color = c.textFaint, modifier = Modifier.weight(1f))
            when (readiness) {
                ModelReadiness.Available -> InferenceBadge(InferenceLocation.OnDevice)
                ModelReadiness.Downloading -> ArcanaChip("Downloading…", ChipStyle.Gold)
                ModelReadiness.Downloadable -> ArcanaChip("Not downloaded", ChipStyle.Gold)
                ModelReadiness.Unavailable -> ArcanaChip("Unavailable", ChipStyle.Plain)
                ModelReadiness.Unknown -> ArcanaChip("Checking…", ChipStyle.Plain)
            }
        }
        when (readiness) {
            ModelReadiness.Downloadable, ModelReadiness.Unavailable -> {
                Text(
                    "Nano isn't provisioned — the sweep will run cloud-only. Download the on-device model " +
                        "(hundreds of MB, Wi-Fi) to benchmark on-device.",
                    fontSize = 13.sp, color = c.textDim, lineHeight = 18.sp,
                )
                Box(
                    Modifier.clip(RoundedCornerShape(11.dp)).border(1.dp, c.gold, RoundedCornerShape(11.dp))
                        .clickable(onClick = onDownload).padding(horizontal = 14.dp, vertical = 9.dp),
                ) { Text("Download on-device model", color = c.gold, fontWeight = FontWeight.Medium, fontSize = 13.sp) }
            }
            ModelReadiness.Downloading -> {
                val mb = (provisioning as? ProvisioningProgress.Downloading)?.megabytes
                Text(
                    if (mb != null) "Downloading… $mb MB" else "Downloading… this can take several minutes.",
                    fontFamily = Mono, fontSize = 12.sp, color = c.gold,
                )
                LinearProgressIndicator(
                    Modifier.fillMaxWidth().height(3.dp).clip(RoundedCornerShape(2.dp)),
                    color = c.gold, trackColor = c.goldSoft,
                )
            }
            else -> Unit
        }
    }
}

@Composable
private fun RunningCard(s: BenchmarkUiState) {
    val c = ArcanaTheme.colors
    val p = s.progress
    val fraction = if (p != null && p.total > 0) p.completed.toFloat() / p.total else 0f
    Column(
        Modifier.fillMaxWidth().clip(RoundedCornerShape(16.dp)).background(c.surface)
            .border(1.dp, c.hairline, RoundedCornerShape(16.dp)).padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            Text("Running benchmark", fontWeight = FontWeight.SemiBold, fontSize = 15.sp, color = c.text, modifier = Modifier.weight(1f))
            Text(
                p?.let { "${it.completed} / ${it.total}" } ?: "starting…",
                fontFamily = Mono, fontSize = 13.sp, fontWeight = FontWeight.Medium, color = c.iris,
            )
        }
        LinearProgressIndicator(
            progress = { fraction },
            modifier = Modifier.fillMaxWidth().height(4.dp).clip(RoundedCornerShape(2.dp)),
            color = c.iris, trackColor = c.irisSoft,
        )
        p?.let {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                InferenceBadge(it.engine.badgeLocation())
                Text(it.prompt.label, fontFamily = Mono, fontSize = 12.sp, color = c.textDim)
            }
        }
    }
}

/** Column order for the comparison matrix; engines absent from the run are filtered out. */
private val COLUMN_ORDER = listOf(BenchmarkEngine.OnDevice, BenchmarkEngine.OwnModel, BenchmarkEngine.Cloud)
private val LABEL_COL_WIDTH = 66.dp

private fun BenchmarkEngine.columnName(): String = when (this) {
    BenchmarkEngine.OnDevice -> "Nano"
    BenchmarkEngine.OwnModel -> "Gemma"
    BenchmarkEngine.Cloud -> "Cloud"
}

/**
 * Three-column comparison matrix (Nano / your Gemma / Cloud). Each metric is a row read straight across;
 * a per-cell bar is scaled within its row and the fastest cell goes green, so the verdict lands before you
 * read a digit. Your Gemma is the tinted centre column — the model under evaluation, between baseline and
 * ceiling. Replaces the old per-engine stack, which forced you to hold one engine's numbers in your head.
 */
@Composable
private fun ResultsSection(results: List<BenchmarkResult>) {
    val c = ArcanaTheme.colors
    val byEngine = results.groupBy { it.engine }
    val engines = COLUMN_ORDER.filter { byEngine.containsKey(it) }
    val prompts = results.map { it.promptLabel }.distinct()

    Column(
        Modifier.fillMaxWidth().clip(RoundedCornerShape(18.dp)).background(c.surface)
            .border(1.dp, c.hairline, RoundedCornerShape(18.dp)).padding(14.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        // Column headers: an empty cell over the metric-label column, then one header per engine.
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.Bottom) {
            Spacer(Modifier.width(LABEL_COL_WIDTH))
            engines.forEach { e -> ColumnHeader(e, Modifier.weight(1f)) }
        }
        prompts.forEach { prompt ->
            val n = results.firstOrNull { it.promptLabel == prompt }?.warmSampleCount ?: 0
            Column(verticalArrangement = Arrangement.spacedBy(9.dp)) {
                Text(
                    "$prompt · n=$n".uppercase(), fontFamily = Mono, fontSize = 10.sp, color = c.textFaint, letterSpacing = 0.8.sp,
                )
                MatrixRow("first tok", prompt, engines, byEngine) { it.firstTokenWarm.p50Ms }
                MatrixRow("total", prompt, engines, byEngine) { it.totalWarm.p50Ms }
            }
        }
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            Box(Modifier.size(8.dp).clip(RoundedCornerShape(2.dp)).background(c.up))
            Text("green = fastest for that row", fontFamily = Mono, fontSize = 10.sp, color = c.up)
        }
    }

    // Cold-start + token counts are kept out of the comparison matrix (they'd dilute the read) but preserved
    // here per engine: cold-start is the first-call latency, tokens the generated count (Nano reports none).
    Spacer(Modifier.height(12.dp))
    ColdAndTokens(results, engines)
}

@Composable
private fun ColumnHeader(engine: BenchmarkEngine, modifier: Modifier) {
    val c = ArcanaTheme.colors
    val dot = when (engine) {
        BenchmarkEngine.OnDevice -> c.iris
        BenchmarkEngine.OwnModel -> c.gold
        BenchmarkEngine.Cloud -> c.cloud
    }
    Column(
        modifier.padding(horizontal = 3.dp)
            .then(if (engine == BenchmarkEngine.OwnModel) Modifier.clip(RoundedCornerShape(8.dp)).background(c.irisSoft) else Modifier)
            .padding(horizontal = 7.dp, vertical = 5.dp),
        verticalArrangement = Arrangement.spacedBy(2.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(5.dp)) {
            Box(Modifier.size(6.dp).clip(CircleShape).background(dot))
            Text(engine.columnName(), fontWeight = FontWeight.Bold, fontSize = 13.sp, color = c.text)
        }
        if (engine == BenchmarkEngine.OwnModel) {
            Text("YOURS", fontFamily = Mono, fontSize = 8.sp, color = c.gold, letterSpacing = 1.5.sp)
        }
    }
}

@Composable
private fun MatrixRow(
    label: String,
    prompt: String,
    engines: List<BenchmarkEngine>,
    byEngine: Map<BenchmarkEngine, List<BenchmarkResult>>,
    selector: (BenchmarkResult) -> Long?,
) {
    val c = ArcanaTheme.colors
    val values = engines.map { e -> byEngine[e]?.firstOrNull { it.promptLabel == prompt }?.let(selector) }
    val present = values.filterNotNull()
    val fastest = present.minOrNull()
    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.Top) {
        Text(label, fontFamily = Mono, fontSize = 10.sp, color = c.textFaint, modifier = Modifier.width(LABEL_COL_WIDTH).padding(top = 3.dp))
        engines.forEachIndexed { i, e ->
            val v = values[i]
            // Bar filled by relative speed: the fastest (min latency) fills the row, slower cells are shorter.
            val frac = if (v != null && v > 0 && fastest != null) (fastest.toFloat() / v).coerceIn(0.1f, 1f) else 0f
            MatrixCell(
                text = latency(v),
                barFraction = frac,
                isFastest = v != null && v == fastest,
                isOwnModel = e == BenchmarkEngine.OwnModel,
                modifier = Modifier.weight(1f),
            )
        }
    }
}

@Composable
private fun MatrixCell(text: String, barFraction: Float, isFastest: Boolean, isOwnModel: Boolean, modifier: Modifier) {
    val c = ArcanaTheme.colors
    val valueColor = if (isFastest) c.up else c.text
    val barColor = if (isFastest) c.up else c.iris.copy(alpha = 0.5f)
    Column(
        modifier.padding(horizontal = 3.dp)
            .then(if (isOwnModel) Modifier.clip(RoundedCornerShape(8.dp)).background(c.irisSoft) else Modifier)
            .padding(horizontal = 7.dp, vertical = 5.dp),
        verticalArrangement = Arrangement.spacedBy(5.dp),
    ) {
        Text(text, fontFamily = Mono, fontSize = 14.sp, fontWeight = FontWeight.Bold, color = valueColor, maxLines = 1)
        Box(Modifier.fillMaxWidth().height(4.dp).clip(RoundedCornerShape(2.dp)).background(c.hairline)) {
            Box(Modifier.fillMaxWidth(barFraction).height(4.dp).clip(RoundedCornerShape(2.dp)).background(barColor))
        }
    }
}

@Composable
private fun ColdAndTokens(results: List<BenchmarkResult>, engines: List<BenchmarkEngine>) {
    val c = ArcanaTheme.colors
    Column(verticalArrangement = Arrangement.spacedBy(5.dp)) {
        engines.forEach { e ->
            val rs = results.filter { it.engine == e }
            val cold = rs.firstNotNullOfOrNull { it.coldTotalMs }
            val tok = rs.firstNotNullOfOrNull { it.outputTokenCount }
            val errors = rs.sumOf { it.errorCount }
            val parts = buildList {
                add(e.columnName())
                cold?.let { add("cold ${latency(it)}") }
                add(tok?.let { "$it tok" } ?: "no tok")
                if (errors > 0) add("$errors rate-limited")
            }
            Text(parts.joinToString("  ·  "), fontFamily = Mono, fontSize = 10.sp, color = c.textFaint)
        }
    }
}

@Composable
private fun IdleHint() {
    val c = ArcanaTheme.colors
    Column(
        Modifier.fillMaxWidth().clip(RoundedCornerShape(18.dp)).background(c.surface)
            .border(1.dp, c.hairline, RoundedCornerShape(18.dp)).padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        Text("Same prompts, up to three engines", fontWeight = FontWeight.SemiBold, fontSize = 14.sp, color = c.text)
        Text(
            "A short and a grounded prompt run on Nano (on-device), your Gemma (LiteRT q4), and Gemini (cloud), " +
                "forced to each engine via RoutingHint. Takes a minute or two — cloud calls are paced under the " +
                "free-tier quota; the own-model column shows only when the model is side-loaded.",
            fontFamily = Mono, fontSize = 11.sp, color = c.textFaint, lineHeight = 16.sp,
        )
    }
}
