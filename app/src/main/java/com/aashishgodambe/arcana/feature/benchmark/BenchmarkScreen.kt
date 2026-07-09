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
                "Gemini Nano on-device vs cloud — first-token and total latency, p50 / p95.",
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
                "p50 / p95 over warm samples per cell (8 on-device, 4 cloud) — indicative, not production-grade " +
                    "statistics. Cloud runs fewer iterations to conserve the free-tier budget. Cold-start (the " +
                    "first call in the process) is shown separately in gold. On-device never reports token " +
                    "counts; cloud does.",
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
                InferenceBadge(if (it.engine == BenchmarkEngine.Cloud) InferenceLocation.Cloud else InferenceLocation.OnDevice)
                Text(it.prompt.label, fontFamily = Mono, fontSize = 12.sp, color = c.textDim)
            }
        }
    }
}

@Composable
private fun ResultsSection(results: List<BenchmarkResult>) {
    val c = ArcanaTheme.colors
    // Grouped by engine, preserving the aggregator's engine-then-prompt order.
    results.groupBy { it.engine }.forEach { (engine, cells) ->
        Row(Modifier.fillMaxWidth().padding(bottom = 10.dp), verticalAlignment = Alignment.CenterVertically) {
            InferenceBadge(if (engine == BenchmarkEngine.Cloud) InferenceLocation.Cloud else InferenceLocation.OnDevice)
        }
        cells.forEach { r ->
            ResultCard(r)
            Spacer(Modifier.height(10.dp))
        }
        Spacer(Modifier.height(8.dp))
    }
}

@Composable
private fun ResultCard(r: BenchmarkResult) {
    val c = ArcanaTheme.colors
    Column(
        Modifier.fillMaxWidth().clip(RoundedCornerShape(18.dp)).background(c.surface)
            .border(1.dp, c.hairline, RoundedCornerShape(18.dp)).padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            Text(r.promptLabel, fontWeight = FontWeight.SemiBold, fontSize = 15.sp, color = c.text, modifier = Modifier.weight(1f))
            Text("n=${r.warmSampleCount}", fontFamily = Mono, fontSize = 11.sp, color = c.textFaint)
        }
        MetricRow("FIRST TOKEN", r.firstTokenWarm.p50Ms, r.firstTokenWarm.p95Ms)
        MetricRow("TOTAL", r.totalWarm.p50Ms, r.totalWarm.p95Ms)
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
            r.coldTotalMs?.let { ArcanaChip("cold · ${latency(it)}", ChipStyle.Gold) }
            ArcanaChip(
                r.outputTokenCount?.let { "$it tok" } ?: "n/a tokens",
                if (r.outputTokenCount != null) ChipStyle.Cloud else ChipStyle.Plain,
            )
            if (r.errorCount > 0) {
                Spacer(Modifier.weight(1f))
                Text("${r.errorCount} rate-limited", fontFamily = Mono, fontSize = 10.sp, color = c.down)
            }
        }
    }
}

@Composable
private fun MetricRow(label: String, p50: Long?, p95: Long?) {
    val c = ArcanaTheme.colors
    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.Bottom) {
        Text(label, fontFamily = Mono, fontSize = 11.sp, color = c.textFaint, modifier = Modifier.width(104.dp))
        Column(Modifier.weight(1f)) {
            Text("p50", fontFamily = Mono, fontSize = 9.sp, color = c.textFaint)
            Text(latency(p50), fontFamily = Mono, fontSize = 17.sp, fontWeight = FontWeight.Bold, color = c.text)
        }
        Column(Modifier.weight(1f)) {
            Text("p95", fontFamily = Mono, fontSize = 9.sp, color = c.textFaint)
            Text(latency(p95), fontFamily = Mono, fontSize = 17.sp, fontWeight = FontWeight.Medium, color = c.textDim)
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
        Text("Same prompts, two engines", fontWeight = FontWeight.SemiBold, fontSize = 14.sp, color = c.text)
        Text(
            "A short and a grounded prompt run on Nano (on-device) and Gemini (cloud), forced to each engine " +
                "via RoutingHint. Takes a minute or two — cloud calls are paced under the free-tier quota.",
            fontFamily = Mono, fontSize = 11.sp, color = c.textFaint, lineHeight = 16.sp,
        )
    }
}
