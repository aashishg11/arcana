package com.aashishgodambe.arcana.feature.detail

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import coil3.compose.AsyncImage
import com.aashishgodambe.arcana.core.data.repository.CollectibleRepository
import com.aashishgodambe.arcana.core.domain.model.Collectible
import com.aashishgodambe.arcana.core.domain.model.FunkoPop
import com.aashishgodambe.arcana.ui.component.ArcanaChip
import com.aashishgodambe.arcana.ui.component.ChipStyle
import com.aashishgodambe.arcana.ui.component.GhostButton
import com.aashishgodambe.arcana.ui.component.PlaceholderCard
import com.aashishgodambe.arcana.ui.formatUsd
import com.aashishgodambe.arcana.ui.theme.ArcanaTheme
import com.aashishgodambe.arcana.ui.theme.Mono
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import java.time.format.DateTimeFormatter
import javax.inject.Inject

@HiltViewModel
class DetailViewModel @Inject constructor(
    repository: CollectibleRepository,
    savedStateHandle: SavedStateHandle,
) : ViewModel() {
    private val localId: Long = checkNotNull(savedStateHandle["localId"])
    private val _collectible = MutableStateFlow<Collectible?>(null)
    val collectible: StateFlow<Collectible?> = _collectible.asStateFlow()

    init { viewModelScope.launch { _collectible.value = repository.getById(localId) } }
}

@Composable
fun DetailScreen(onBack: () -> Unit, vm: DetailViewModel = hiltViewModel()) {
    val collectible by vm.collectible.collectAsStateWithLifecycle()
    // Dispatch through the sealed domain type — adding FigPin/PokemonCard later breaks this `when`.
    when (val item = collectible) {
        null -> Box(Modifier.fillMaxSize().background(ArcanaTheme.colors.bg), contentAlignment = Alignment.Center) {
            Text("Loading…", color = ArcanaTheme.colors.textDim)
        }
        is FunkoPop -> FunkoDetail(item, onBack)
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun FunkoDetail(pop: FunkoPop, onBack: () -> Unit) {
    val c = ArcanaTheme.colors
    Scaffold(containerColor = c.bg) { padding ->
        Column(
            Modifier.padding(padding).fillMaxSize().verticalScroll(rememberScrollState()).padding(horizontal = 18.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Row(Modifier.fillMaxWidth().padding(top = 8.dp), verticalAlignment = Alignment.CenterVertically) {
                Text("‹", color = c.text, fontSize = 26.sp, modifier = Modifier.clickable(onClick = onBack))
                Spacer(Modifier.weight(1f))
                Text("⋯", color = c.textDim, fontSize = 22.sp)
            }
            Box(Modifier.fillMaxWidth().aspectRatio(1.2f).clip(RoundedCornerShape(20.dp)).background(c.surface), contentAlignment = Alignment.Center) {
                AsyncImage(model = pop.imageUrl, contentDescription = pop.name, contentScale = ContentScale.Fit, modifier = Modifier.fillMaxSize().padding(8.dp))
            }
            Text(pop.name, style = MaterialTheme.typography.headlineSmall, color = c.text)
            FlowRow(horizontalArrangement = Arrangement.spacedBy(7.dp), verticalArrangement = Arrangement.spacedBy(7.dp)) {
                pop.series.forEach { ArcanaChip(it, ChipStyle.Plain) }
                pop.popNumber?.let { ArcanaChip("#$it", ChipStyle.Plain) }
                if (pop.isNftRedeemable) ArcanaChip("⬡ NFT Redemption Pop", ChipStyle.Gold)
            }
            Row(
                Modifier.fillMaxWidth().clip(RoundedCornerShape(12.dp)).background(c.irisSoft).padding(horizontal = 13.dp, vertical = 11.dp),
                verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(9.dp),
            ) {
                Text("×${pop.quantity}", fontFamily = Mono, fontWeight = FontWeight.Bold, color = c.iris)
                Text("In your collection · added ${pop.dateAdded.format(MONTH_YEAR)}", color = c.text, fontSize = 13.sp)
            }
            Column {
                Text(formatUsd(pop.estimatedValueCents), style = MaterialTheme.typography.displayLarge.copy(fontSize = 34.sp), color = c.text)
                pop.exclusiveTo?.let { Text("Exclusive · $it", fontFamily = Mono, fontSize = 11.sp, color = c.textDim) }
            }
            PlaceholderCard("Median active listing", "Live eBay market — median active price + top listings — lands in Week 3.", "eBay Browse")
            GhostButton("⊹ Snapshot today's price", onClick = {}, accent = true)
            PlaceholderCard("Value history", "Tracking started — the 90-day chart fills in after the first weekly price sync.", "90 days")
            GhostButton("✎ Edit details", onClick = {})
            Row(Modifier.fillMaxWidth().padding(vertical = 8.dp), horizontalArrangement = Arrangement.Center, verticalAlignment = Alignment.CenterVertically) {
                Text("Add another", color = c.textDim, fontSize = 13.sp)
                Text("  ·  ", color = c.textFaint, fontSize = 13.sp)
                Text("Delete from collection", color = c.down, fontSize = 13.sp)
            }
            Spacer(Modifier.height(14.dp))
        }
    }
}

private val MONTH_YEAR = DateTimeFormatter.ofPattern("MMM yyyy")
