package com.aashishgodambe.arcana.feature.portfolio

import androidx.compose.foundation.Canvas
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
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.drawscope.Stroke
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
import com.aashishgodambe.arcana.core.data.worker.WeeklyPriceSyncScheduler
import com.aashishgodambe.arcana.core.data.repository.CollectibleRepository
import com.aashishgodambe.arcana.core.domain.model.PortfolioPoint
import com.aashishgodambe.arcana.core.domain.usecase.ComputeWeeklyDeltas
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
import java.time.Duration
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
    /** Change over the last ~7 days: the latest point vs the newest point at least a week older — a real
     *  time window, so a burst of same-day syncs can't fake a "this week" number. Null until a point that
     *  old exists. */
    val weekDeltaCents: Int?
        get() {
            if (points.isEmpty()) return null
            val latest = points.last()
            val cutoff = latest.at.minus(Duration.ofDays(7))
            val prior = points.lastOrNull { it.at <= cutoff } ?: return null
            return latest.totalValueCents - prior.totalValueCents
        }

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
    private val computeWeeklyDeltas: ComputeWeeklyDeltas,
    private val summarizer: CollectionSummarizer,
    private val syncScheduler: WeeklyPriceSyncScheduler,
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

    /** True while the manual "Sync now" background job (WorkManager) is enqueued or running. */
    val syncing: StateFlow<Boolean> = syncScheduler.syncNowRunning()
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), false)

    init {
        // On-device inference is foreground-only (Week-2 finding), so the weekly summary is generated here,
        // not in a worker: once on Portfolio load, and again whenever a background "Sync now" completes.
        // (Synthetic history seeding was removed: value history now comes only from real eBay syncs.)
        viewModelScope.launch { generateSummary() }
        viewModelScope.launch { syncScheduler.syncNowCompletions().collect { generateSummary() } }
    }

    /** "Sync now" — enqueue a background WorkManager job (survives app backgrounding, screen lock, and
     *  process death), instead of running in this ViewModel's scope. The UI reflects it via [syncing]. */
    fun syncNow() = syncScheduler.syncNow()

    /** Cancel a running manual sync (clean stop — no WorkManager retry). */
    fun cancelSync() = syncScheduler.cancelSyncNow()

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
    onOpenSettings: () -> Unit,
    onOpenCapture: () -> Unit,
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
                    Box(Modifier.size(42.dp).clip(RoundedCornerShape(13.dp)).background(c.surface).border(1.dp, c.hairlineStrong, RoundedCornerShape(13.dp)).clickable(onClick = onOpenSettings), contentAlignment = Alignment.Center) {
                        SlidersIcon()
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
                        if (syncing) "Syncing… ✕ cancel" else "↺ Sync now",
                        fontFamily = Mono, fontSize = 11.sp, color = if (syncing) c.down else c.iris,
                        modifier = Modifier.clickable { if (syncing) vm.cancelSync() else vm.syncNow() },
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

                Spacer(Modifier.height(150.dp))
            }

            // A light scrim: the list fades into the background behind the compose bar rather than
            // clashing with it (replacing the old bottom sheet that hid half the list).
            Box(
                Modifier.align(Alignment.BottomCenter).fillMaxWidth().height(200.dp)
                    .background(Brush.verticalGradient(listOf(Color.Transparent, c.bg))),
            )

            // Ask compose bar + Capture FAB — two distinct affordance classes so they stop competing.
            // Asking is ambient → a persistent, low-chrome compose bar pinned full-width at the bottom.
            // Capture is a deliberate act → the one saturated iris FAB, floating above the bar's end.
            Column(
                Modifier.align(Alignment.BottomCenter).fillMaxWidth().padding(start = 20.dp, end = 20.dp, top = 12.dp, bottom = 26.dp),
                horizontalAlignment = Alignment.End,
                verticalArrangement = Arrangement.spacedBy(14.dp),
            ) {
                // Capture FAB — the one saturated element, lifted off the surface by an iris glow.
                Box(
                    Modifier.size(62.dp)
                        .shadow(18.dp, RoundedCornerShape(20.dp), spotColor = c.iris, ambientColor = c.iris)
                        .clip(RoundedCornerShape(20.dp)).background(c.iris).clickable(onClick = onOpenCapture),
                    contentAlignment = Alignment.Center,
                ) {
                    CameraGlyph()
                }
                // Persistent Ask compose bar — a full pill, low-chrome; tapping opens the Ask flow.
                Row(
                    Modifier.fillMaxWidth().height(54.dp).clip(RoundedCornerShape(27.dp)).background(c.surface)
                        .border(1.dp, c.hairlineStrong, RoundedCornerShape(27.dp)).clickable { askOpen = true }
                        .padding(start = 20.dp, end = 8.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(10.dp),
                ) {
                    Text("Ask about your collection…", color = c.textDim, fontSize = 15.sp, modifier = Modifier.weight(1f))
                    Box(
                        Modifier.size(38.dp).clip(CircleShape).background(c.elevated),
                        contentAlignment = Alignment.Center,
                    ) {
                        Text("↑", fontFamily = Mono, color = c.iris, fontSize = 18.sp, fontWeight = FontWeight.Bold)
                    }
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

/** The Capture FAB's icon — a clean outlined camera (open-bottom viewfinder hump, body, lens), drawn to
 *  match the wireframe rather than a stock glyph, since the app renders all its icons as marks. */
@Composable
private fun CameraGlyph() {
    Canvas(Modifier.size(width = 28.dp, height = 22.dp)) {
        val s = 2.3.dp.toPx()
        val w = size.width
        val h = size.height
        val bodyTop = 5.dp.toPx()
        // Camera body.
        drawRoundRect(
            color = Color.White,
            topLeft = Offset(0f, bodyTop),
            size = Size(w, h - bodyTop),
            cornerRadius = CornerRadius(5.dp.toPx(), 5.dp.toPx()),
            style = Stroke(s),
        )
        // Viewfinder — an open-bottom hump whose ends meet the body's top edge.
        val bl = 7.dp.toPx()
        val br = 16.dp.toPx()
        val r = 2.dp.toPx()
        val bump = Path().apply {
            moveTo(bl, bodyTop)
            lineTo(bl, r)
            quadraticTo(bl, 0f, bl + r, 0f)
            lineTo(br - r, 0f)
            quadraticTo(br, 0f, br, r)
            lineTo(br, bodyTop)
        }
        drawPath(bump, color = Color.White, style = Stroke(s))
        // Lens.
        drawCircle(
            color = Color.White,
            radius = 4.dp.toPx(),
            center = Offset(w / 2f, bodyTop + (h - bodyTop) / 2f),
            style = Stroke(s),
        )
    }
}

/** The Settings entry icon — a sliders/adjustments mark (dot+line, then line+dot), matching the wireframe;
 *  not a gear glyph. */
@Composable
private fun SlidersIcon() {
    val c = ArcanaTheme.colors
    Column(Modifier.width(22.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Box(Modifier.size(6.dp).clip(CircleShape).background(c.textDim))
            Spacer(Modifier.width(3.dp))
            Box(Modifier.weight(1f).height(1.5.dp).clip(RoundedCornerShape(1.dp)).background(c.hairlineStrong))
        }
        Row(verticalAlignment = Alignment.CenterVertically) {
            Box(Modifier.weight(1f).height(1.5.dp).clip(RoundedCornerShape(1.dp)).background(c.hairlineStrong))
            Spacer(Modifier.width(3.dp))
            Box(Modifier.size(6.dp).clip(CircleShape).background(c.textDim))
        }
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
