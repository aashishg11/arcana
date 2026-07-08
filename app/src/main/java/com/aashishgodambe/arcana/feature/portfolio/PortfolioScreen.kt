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
import com.aashishgodambe.arcana.core.data.database.entity.CollectionGroup
import com.aashishgodambe.arcana.core.data.repository.CollectibleRepository
import com.aashishgodambe.arcana.feature.ask.AskSheet
import com.aashishgodambe.arcana.ui.formatUsd
import com.aashishgodambe.arcana.ui.theme.ArcanaTheme
import com.aashishgodambe.arcana.ui.theme.Mono
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import javax.inject.Inject

data class PortfolioUiState(
    val totalValueCents: Int = 0,
    val itemCount: Int = 0,       // unique entries
    val copyCount: Int = 0,       // entries incl. duplicates (Σ quantity)
    val topGroups: List<CollectionGroup> = emptyList(),
)

@HiltViewModel
class PortfolioViewModel @Inject constructor(repository: CollectibleRepository) : ViewModel() {
    val state: StateFlow<PortfolioUiState> = combine(
        repository.observeTotalValueCents(),
        repository.observeCount(),
        repository.observeListBreakdown(),
        repository.observeCopyCount(),
    ) { value, count, groups, copies ->
        PortfolioUiState(value, count, copies, groups.take(8))
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), PortfolioUiState())
}

@Composable
fun PortfolioScreen(
    onGroupClick: (String) -> Unit,
    onItemClick: (Long) -> Unit,
    vm: PortfolioViewModel = hiltViewModel(),
) {
    val c = ArcanaTheme.colors
    val s by vm.state.collectAsStateWithLifecycle()
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
                Spacer(Modifier.height(12.dp))
                // Value sparkline placeholder — the real chart needs a value time-series (Week 3-4 price sync).
                Box(
                    Modifier.fillMaxWidth().height(60.dp).clip(RoundedCornerShape(14.dp))
                        .background(c.surface).border(1.dp, c.hairline, RoundedCornerShape(14.dp)),
                    contentAlignment = Alignment.Center,
                ) {
                    Text(
                        "Value history — the sparkline fills in after your first weekly price sync",
                        fontFamily = Mono, fontSize = 10.sp, color = c.textFaint,
                        textAlign = TextAlign.Center, modifier = Modifier.padding(horizontal = 16.dp),
                    )
                }
                Spacer(Modifier.height(10.dp))
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                    Text("Not synced yet", fontFamily = Mono, fontSize = 11.sp, color = c.textFaint)
                    Text("Sync now", fontFamily = Mono, fontSize = 11.sp, color = c.iris)
                }

                // AI summary card
                Spacer(Modifier.height(18.dp))
                Column(
                    Modifier.fillMaxWidth().clip(RoundedCornerShape(18.dp)).background(c.surface)
                        .border(1.dp, c.hairline, RoundedCornerShape(18.dp)).padding(16.dp),
                ) {
                    Text("◆ THIS WEEK · ON-DEVICE SUMMARY", fontFamily = Mono, fontSize = 11.sp, color = c.iris, fontWeight = FontWeight.Medium)
                    Spacer(Modifier.height(9.dp))
                    Text(
                        "Your weekly AI summary — what moved and why — is generated on-device once price sync lands (Week 3–4).",
                        color = c.text, fontSize = 14.sp, lineHeight = 21.sp,
                    )
                }

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
