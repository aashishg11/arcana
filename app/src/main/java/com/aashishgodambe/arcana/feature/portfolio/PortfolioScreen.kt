package com.aashishgodambe.arcana.feature.portfolio

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
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.ViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import com.aashishgodambe.arcana.core.ai.model.InferenceLocation
import com.aashishgodambe.arcana.core.ai.model.InferenceResult
import com.aashishgodambe.arcana.core.ai.summary.CollectionSummarizer
import com.aashishgodambe.arcana.core.data.database.entity.CollectionGroup
import com.aashishgodambe.arcana.core.data.database.entity.SnapshotTrigger
import com.aashishgodambe.arcana.core.data.repository.CollectibleRepository
import com.aashishgodambe.arcana.core.domain.model.PortfolioPoint
import com.aashishgodambe.arcana.core.domain.usecase.ComputeWeeklyDeltas
import com.aashishgodambe.arcana.core.domain.usecase.SeedMockHistory
import com.aashishgodambe.arcana.core.domain.usecase.SyncAllPrices
import com.aashishgodambe.arcana.feature.ask.AskSheet
import com.aashishgodambe.arcana.ui.component.ChartRange
import com.aashishgodambe.arcana.ui.component.InferenceBadge
import com.aashishgodambe.arcana.ui.component.RangeSelector
import com.aashishgodambe.arcana.ui.component.StreamingText
import com.aashishgodambe.arcana.ui.component.ValueSparkline
import com.aashishgodambe.arcana.ui.component.withinRange
import com.aashishgodambe.arcana.ui.formatUsd
import com.aashishgodambe.arcana.ui.theme.ArcanaTheme
import com.aashishgodambe.arcana.ui.theme.Mono
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import kotlin.math.abs
import javax.inject.Inject

data class PortfolioUiState(
    val totalValueCents: Int = 0,      // tracked current value (COALESCE(lastKnown, estimate) × qty)
    val itemCount: Int = 0,            // unique entries
    val copyCount: Int = 0,            // entries incl. duplicates (Σ quantity)
    val topGroups: List<CollectionGroup> = emptyList(),
    val points: List<PortfolioPoint> = emptyList(),   // aggregate totals, oldest → newest
) {
    /** Week-over-week change, or null when there aren't yet two points to compare. */
    val weekDeltaCents: Int?
        get() = if (points.size >= 2) points.last().totalValueCents - points[points.size - 2].totalValueCents else null

    val lastSyncedAt: Instant? get() = points.lastOrNull()?.at

    val hasHistory: Boolean get() = points.size >= 2
}

/** The on-device "what moved" summary card's state — streams in, then settles with a badge. */
data class SummaryUiState(
    val streaming: String? = null,     // partial text while generating
    val text: String? = null,          // final summary
    val location: InferenceLocation? = null,
    val error: String? = null,
) {
    val isGenerating: Boolean get() = streaming != null && text == null && error == null
}

@HiltViewModel
class PortfolioViewModel @Inject constructor(
    repository: CollectibleRepository,
    seedMockHistory: SeedMockHistory,
    private val computeWeeklyDeltas: ComputeWeeklyDeltas,
    private val summarizer: CollectionSummarizer,
    private val syncAllPrices: SyncAllPrices,
) : ViewModel() {
    val state: StateFlow<PortfolioUiState> = combine(
        repository.observeTotalValueCents(),
        repository.observeCount(),
        repository.observeListBreakdown(),
        repository.observeCopyCount(),
        repository.observePortfolioSeries(),
    ) { value, count, groups, copies, points ->
        PortfolioUiState(
            totalValueCents = value,
            itemCount = count,
            copyCount = copies,
            topGroups = groups.take(8),
            points = points,
        )
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), PortfolioUiState())

    private val _summary = MutableStateFlow(SummaryUiState())
    val summary: StateFlow<SummaryUiState> = _summary.asStateFlow()

    private val _syncing = MutableStateFlow(false)
    val syncing: StateFlow<Boolean> = _syncing.asStateFlow()

    init {
        // Seed once (idempotent), then generate the weekly summary on-device from the resulting deltas.
        // On-device inference is foreground-only (Week-2 finding), so this runs on Portfolio load — not
        // in the background worker.
        viewModelScope.launch {
            seedMockHistory()
            generateSummary()
        }
    }

    /** "Sync now" — the same [SyncAllPrices] path as the weekly worker, on demand; then re-summarize. */
    fun syncNow() {
        if (_syncing.value) return
        _syncing.value = true
        viewModelScope.launch {
            syncAllPrices(SnapshotTrigger.UserRefresh)   // writes fresh snapshots → charts update reactively
            generateSummary()                             // refresh the "what moved" card from new deltas
            _syncing.value = false
        }
    }

    private suspend fun generateSummary() {
        val deltas = computeWeeklyDeltas() ?: return   // thin data → keep the placeholder
        _summary.value = SummaryUiState(streaming = "")
        summarizer.summarize(deltas).collect { result ->
            when (result) {
                is InferenceResult.Streaming ->
                    _summary.update { it.copy(streaming = result.partialText) }
                is InferenceResult.Success ->
                    _summary.update {
                        it.copy(text = result.fullText, streaming = null, location = result.metadata.executedOn)
                    }
                is InferenceResult.Error ->
                    _summary.update { it.copy(error = result.cause.message ?: "Couldn't generate summary", streaming = null) }
            }
        }
    }
}

@Composable
fun PortfolioScreen(
    onGroupClick: (String) -> Unit,
    onItemClick: (Long) -> Unit,
    vm: PortfolioViewModel = hiltViewModel(),
) {
    val c = ArcanaTheme.colors
    val s by vm.state.collectAsStateWithLifecycle()
    val summary by vm.summary.collectAsStateWithLifecycle()
    val syncing by vm.syncing.collectAsStateWithLifecycle()
    var askOpen by remember { mutableStateOf(false) }

    Scaffold(containerColor = c.bg) { padding ->
        Box(Modifier.padding(padding).fillMaxSize()) {
            Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(horizontal = 20.dp)) {
                // app bar
                Row(Modifier.fillMaxWidth().padding(top = 8.dp, bottom = 6.dp), verticalAlignment = Alignment.CenterVertically) {
                    Text("Arcana", fontWeight = FontWeight.ExtraBold, fontSize = 22.sp, color = c.text)
                    Spacer(Modifier.weight(1f))
                    Box(Modifier.size(38.dp).clip(RoundedCornerShape(12.dp)).background(c.surface).border(1.dp, c.hairline, RoundedCornerShape(12.dp)), contentAlignment = Alignment.Center) {
                        Text("⚙", color = c.textDim, fontSize = 17.sp)
                    }
                }

                // value
                Spacer(Modifier.height(6.dp))
                Text(formatUsd(s.totalValueCents), style = MaterialTheme.typography.displayLarge, color = c.text)

                // week-over-week delta
                s.weekDeltaCents?.let { delta ->
                    Spacer(Modifier.height(8.dp))
                    val up = delta >= 0
                    Text(
                        "${if (up) "▲" else "▼"} ${formatUsd(abs(delta))}  this week",
                        fontFamily = Mono, fontSize = 14.sp, fontWeight = FontWeight.Medium,
                        color = if (up) c.up else c.down,
                    )
                }

                Spacer(Modifier.height(12.dp))
                if (s.hasHistory) {
                    var range by rememberSaveable { mutableStateOf(ChartRange.Days90) }
                    val values = remember(s.points, range) {
                        s.points.withinRange(range) { it.at }.map { it.totalValueCents }
                    }
                    Row(Modifier.fillMaxWidth().padding(bottom = 8.dp), horizontalArrangement = Arrangement.End) {
                        RangeSelector(selected = range, onSelect = { range = it })
                    }
                    ValueSparkline(
                        values = values,
                        lineColor = c.iris,
                        fillColor = c.irisSoft,
                        modifier = Modifier.fillMaxWidth().height(60.dp),
                    )
                } else {
                    // Honest thin-data state — no misleading flat line on an empty axis.
                    Box(
                        Modifier.fillMaxWidth().height(60.dp).clip(RoundedCornerShape(14.dp))
                            .background(c.surface).border(1.dp, c.hairline, RoundedCornerShape(14.dp)),
                        contentAlignment = Alignment.Center,
                    ) {
                        Text(
                            "Price tracking started — the sparkline fills in as snapshots accrue",
                            fontFamily = Mono, fontSize = 10.sp, color = c.textFaint,
                            textAlign = TextAlign.Center, modifier = Modifier.padding(horizontal = 16.dp),
                        )
                    }
                }
                Spacer(Modifier.height(10.dp))
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                    Text(
                        s.lastSyncedAt?.let { "Updated ${it.atZone(ZoneId.systemDefault()).format(SYNC_DAY)}" }
                            ?: "Not synced yet",
                        fontFamily = Mono, fontSize = 11.sp, color = c.textFaint,
                    )
                    Text(
                        if (syncing) "Syncing…" else "Sync now",
                        fontFamily = Mono, fontSize = 11.sp, color = c.iris,
                        modifier = Modifier.clickable(enabled = !syncing) { vm.syncNow() },
                    )
                }

                // AI summary card
                Spacer(Modifier.height(18.dp))
                SummaryCard(summary)

                // breakdown
                Spacer(Modifier.height(16.dp))
                Text("COLLECTION", fontFamily = Mono, fontSize = 11.sp, color = c.textFaint, letterSpacing = 1.sp)
                Spacer(Modifier.height(10.dp))

                // Funko Pop — a card like the summary above; expands to the per-list breakdown,
                // collapses to a HobbyDB-style stat block.
                var expanded by rememberSaveable { mutableStateOf(true) }
                Column(
                    Modifier.fillMaxWidth().clip(RoundedCornerShape(18.dp)).background(c.surface)
                        .border(1.dp, c.hairline, RoundedCornerShape(18.dp)),
                ) {
                    Row(
                        Modifier.fillMaxWidth().clickable { expanded = !expanded }.padding(horizontal = 16.dp, vertical = 14.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Text("Funko Pop", fontWeight = FontWeight.SemiBold, fontSize = 15.sp, color = c.text, modifier = Modifier.weight(1f))
                        Text("${s.itemCount} items", fontFamily = Mono, fontSize = 12.sp, color = c.textDim)
                        Spacer(Modifier.width(12.dp))
                        Text(formatUsd(s.totalValueCents), fontFamily = Mono, fontWeight = FontWeight.Bold, fontSize = 14.sp, color = c.text)
                        Spacer(Modifier.width(10.dp))
                        Text(if (expanded) "▾" else "▸", color = c.textFaint, fontSize = 12.sp)
                    }
                    HorizontalDivider(color = c.hairline)
                    if (expanded) {
                        Column(Modifier.padding(horizontal = 16.dp, vertical = 2.dp)) {
                            s.topGroups.forEachIndexed { i, group ->
                                if (i > 0) HorizontalDivider(color = c.hairline)
                                GroupRow(group, onClick = { onGroupClick(group.name) })
                            }
                        }
                    } else {
                        CollectionStats(s)
                    }
                }

                // future category, dimmed
                Spacer(Modifier.height(12.dp))
                Row(
                    Modifier.fillMaxWidth().clip(RoundedCornerShape(18.dp)).background(c.surface)
                        .border(1.dp, c.hairline, RoundedCornerShape(18.dp)).alpha(0.55f)
                        .padding(horizontal = 16.dp, vertical = 16.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text("FigPin", fontWeight = FontWeight.SemiBold, fontSize = 15.sp, color = c.text, modifier = Modifier.weight(1f))
                    Text("Coming soon", fontFamily = Mono, fontSize = 12.sp, color = c.textFaint)
                }

                Spacer(Modifier.height(90.dp))
            }

            // FAB + Ask pill
            Column(Modifier.align(Alignment.BottomEnd).padding(20.dp), horizontalAlignment = Alignment.End, verticalArrangement = Arrangement.spacedBy(12.dp)) {
                Row(
                    Modifier.clip(RoundedCornerShape(999.dp)).background(c.surface).border(1.dp, c.hairlineStrong, RoundedCornerShape(999.dp)).clickable { askOpen = true }.padding(horizontal = 16.dp, vertical = 11.dp),
                    verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    Box(Modifier.size(7.dp).clip(RoundedCornerShape(999.dp)).background(c.iris))
                    Text("Ask Arcana", fontWeight = FontWeight.SemiBold, fontSize = 13.sp, color = c.text)
                }
                Box(Modifier.size(58.dp).clip(RoundedCornerShape(20.dp)).background(c.iris), contentAlignment = Alignment.Center) {
                    Text("⊕", color = Color.White, fontSize = 24.sp)
                }
            }
        }
    }

    if (askOpen) {
        AskSheet(
            onDismiss = { askOpen = false },
            onItemClick = { id -> askOpen = false; onItemClick(id) },
        )
    }
}

/**
 * The weekly on-device "what moved" card. Streams token-by-token via [StreamingText] and flips the
 * [InferenceBadge] to on-device/cloud once the answer settles. Falls back to a quiet placeholder before
 * there's a week of history to compare, or if generation fails.
 */
@Composable
private fun SummaryCard(summary: SummaryUiState) {
    val c = ArcanaTheme.colors
    Column(
        Modifier.fillMaxWidth().clip(RoundedCornerShape(18.dp)).background(c.surface)
            .border(1.dp, c.hairline, RoundedCornerShape(18.dp)).padding(16.dp),
    ) {
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            Text(
                "◆ THIS WEEK · ON-DEVICE SUMMARY",
                fontFamily = Mono, fontSize = 11.sp, color = c.iris, fontWeight = FontWeight.Medium,
                modifier = Modifier.weight(1f),
            )
            if (summary.location != null) InferenceBadge(summary.location)
        }
        Spacer(Modifier.height(9.dp))
        when {
            summary.text != null ->
                StreamingText(summary.text, streaming = false, color = c.text)
            summary.streaming != null ->
                StreamingText(summary.streaming, streaming = true, color = c.text)
            summary.error != null ->
                Text(
                    "Couldn't generate this week's summary on-device — it'll retry after your next sync.",
                    color = c.textDim, fontSize = 14.sp, lineHeight = 21.sp,
                )
            else ->
                Text(
                    "Your weekly summary appears here once there's a week of price history to compare.",
                    color = c.textFaint, fontSize = 14.sp, lineHeight = 21.sp,
                )
        }
    }
}

/** Collapsed-state collection summary — entries, copies, valued split, total (à la HobbyDB). */
@Composable
private fun CollectionStats(s: PortfolioUiState) {
    val c = ArcanaTheme.colors
    Column(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp)) {
        StatRow("Collectible entries", "${s.itemCount}")
        StatRow("Incl. duplicates", "${s.copyCount}")
        Spacer(Modifier.height(4.dp))
        HorizontalDivider(color = c.hairline)
        Spacer(Modifier.height(4.dp))
        StatRow("Total estimated value", formatUsd(s.totalValueCents), emphasize = true)
    }
}

@Composable
private fun StatRow(label: String, value: String, emphasize: Boolean = false) {
    val c = ArcanaTheme.colors
    Row(
        Modifier.fillMaxWidth().padding(vertical = 6.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(label, fontSize = 14.sp, color = if (emphasize) c.text else c.textDim, modifier = Modifier.weight(1f))
        Text(
            value, fontFamily = Mono, fontSize = if (emphasize) 14.sp else 13.sp,
            fontWeight = if (emphasize) FontWeight.Bold else FontWeight.Medium,
            color = c.text,
        )
    }
}

private val SYNC_DAY = DateTimeFormatter.ofPattern("EEEE")

@Composable
private fun GroupRow(group: CollectionGroup, onClick: () -> Unit) {
    val c = ArcanaTheme.colors
    Row(
        Modifier.fillMaxWidth().clickable(onClick = onClick).padding(vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(group.name, fontWeight = FontWeight.Medium, fontSize = 14.sp, color = c.textDim, modifier = Modifier.weight(1f))
        Text("${group.itemCount}", fontFamily = Mono, fontSize = 12.sp, color = c.textDim)
        Spacer(Modifier.width(12.dp))
        Text(formatUsd(group.valueCents), fontFamily = Mono, fontWeight = FontWeight.Bold, fontSize = 13.sp, color = c.text)
    }
}
