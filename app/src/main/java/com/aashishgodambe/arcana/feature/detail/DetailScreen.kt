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
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
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
import com.aashishgodambe.arcana.core.ai.model.MarketContext
import com.aashishgodambe.arcana.core.ai.model.PriceResult
import com.aashishgodambe.arcana.core.ai.pricing.EbaySearch
import com.aashishgodambe.arcana.core.ai.pricing.PriceProviderChain
import com.aashishgodambe.arcana.core.data.repository.CollectibleRepository
import com.aashishgodambe.arcana.core.domain.model.Collectible
import com.aashishgodambe.arcana.core.domain.model.FunkoPop
import com.aashishgodambe.arcana.core.domain.model.ValueSnapshot
import com.aashishgodambe.arcana.core.domain.model.currentValueCents
import com.aashishgodambe.arcana.core.domain.usecase.SnapshotItemPrice
import com.aashishgodambe.arcana.ui.component.ArcanaChip
import com.aashishgodambe.arcana.ui.component.ChartRange
import com.aashishgodambe.arcana.ui.component.ChipStyle
import com.aashishgodambe.arcana.ui.component.GhostButton
import com.aashishgodambe.arcana.ui.component.MarketSection
import com.aashishgodambe.arcana.ui.component.PlaceholderCard
import com.aashishgodambe.arcana.ui.component.RangeSelector
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
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import java.time.Duration
import java.time.Instant
import java.time.format.DateTimeFormatter
import javax.inject.Inject

/** Live market state for the Detail market card. */
data class DetailMarketState(
    val loading: Boolean = true,
    val market: MarketContext? = null,
    val buyUrl: String = "",
)

@HiltViewModel
class DetailViewModel @Inject constructor(
    private val repository: CollectibleRepository,
    private val priceChain: PriceProviderChain,
    private val snapshotItemPrice: SnapshotItemPrice,
    savedStateHandle: SavedStateHandle,
) : ViewModel() {
    private val localId: Long = checkNotNull(savedStateHandle["localId"])
    private val _collectible = MutableStateFlow<Collectible?>(null)
    val collectible: StateFlow<Collectible?> = _collectible.asStateFlow()

    /** Per-item value history over the default 90-day window, oldest first — backs the Detail chart. */
    val history: StateFlow<List<ValueSnapshot>> = repository.observeValueHistory(localId)
        .map { snaps -> val cutoff = Instant.now().minus(Duration.ofDays(90)); snaps.filter { it.at >= cutoff } }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    private val _market = MutableStateFlow(DetailMarketState())
    val market: StateFlow<DetailMarketState> = _market.asStateFlow()

    private val _snapshotting = MutableStateFlow(false)
    val snapshotting: StateFlow<Boolean> = _snapshotting.asStateFlow()

    /** One-shot user feedback for the "Snapshot today's price" action; cleared once shown. */
    private val _snapshotMessage = MutableStateFlow<String?>(null)
    val snapshotMessage: StateFlow<String?> = _snapshotMessage.asStateFlow()

    init {
        viewModelScope.launch {
            val item = repository.getById(localId)
            _collectible.value = item
            if (item != null) {
                // Market is live-on-render (like the wireframe) — no "refresh price" button.
                val result = priceChain.fetchPrice(item)
                _market.value = DetailMarketState(
                    loading = false,
                    market = (result as? PriceResult.Success)?.marketContext,
                    buyUrl = EbaySearch.url(item),
                )
            }
        }
    }

    /** "Snapshot today's price" — commits a UserRefresh point (debounced) and refreshes the shown value. */
    fun snapshotPrice() {
        if (_snapshotting.value) return
        _snapshotting.value = true
        viewModelScope.launch {
            _snapshotMessage.value = when (val r = snapshotItemPrice(localId)) {
                is SnapshotItemPrice.Result.Snapshotted -> "Snapshot saved · ${formatUsd(r.valueCents)}"
                is SnapshotItemPrice.Result.AlreadyUpToDate -> "Already up to date — snapped today at ${formatUsd(r.valueCents)}"
                is SnapshotItemPrice.Result.Unavailable -> r.reason
            }
            _collectible.value = repository.getById(localId)   // reflect the new lastKnownValue
            _snapshotting.value = false
        }
    }

    fun consumeSnapshotMessage() { _snapshotMessage.value = null }

    private val _deleted = MutableStateFlow(false)
    val deleted: StateFlow<Boolean> = _deleted.asStateFlow()

    /** Delete this collectible from the collection; the screen navigates back once it's gone. */
    fun delete() {
        viewModelScope.launch {
            repository.delete(localId)
            _deleted.value = true
        }
    }
}

@Composable
fun DetailScreen(onBack: () -> Unit, vm: DetailViewModel = hiltViewModel()) {
    val collectible by vm.collectible.collectAsStateWithLifecycle()
    val history by vm.history.collectAsStateWithLifecycle()
    val market by vm.market.collectAsStateWithLifecycle()
    val snapshotting by vm.snapshotting.collectAsStateWithLifecycle()
    val snapshotMessage by vm.snapshotMessage.collectAsStateWithLifecycle()
    val deleted by vm.deleted.collectAsStateWithLifecycle()

    // Once deleted, leave Detail — the item no longer exists.
    LaunchedEffect(deleted) { if (deleted) onBack() }

    // Dispatch through the sealed domain type — adding FigPin/PokemonCard later breaks this `when`.
    when (val item = collectible) {
        null -> Box(Modifier.fillMaxSize().background(ArcanaTheme.colors.bg), contentAlignment = Alignment.Center) {
            Text("Loading…", color = ArcanaTheme.colors.textDim)
        }
        is FunkoPop -> FunkoDetail(
            pop = item,
            history = history,
            market = market,
            snapshotting = snapshotting,
            snapshotMessage = snapshotMessage,
            onSnapshot = vm::snapshotPrice,
            onSnapshotMessageShown = vm::consumeSnapshotMessage,
            onDelete = vm::delete,
            onBack = onBack,
        )
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun FunkoDetail(
    pop: FunkoPop,
    history: List<ValueSnapshot>,
    market: DetailMarketState,
    snapshotting: Boolean,
    snapshotMessage: String?,
    onSnapshot: () -> Unit,
    onSnapshotMessageShown: () -> Unit,
    onDelete: () -> Unit,
    onBack: () -> Unit,
) {
    val c = ArcanaTheme.colors
    val snackbarState = remember { SnackbarHostState() }
    var confirmDelete by remember { mutableStateOf(false) }
    LaunchedEffect(snapshotMessage) {
        snapshotMessage?.let { snackbarState.showSnackbar(it); onSnapshotMessageShown() }
    }
    Scaffold(containerColor = c.bg, snackbarHost = { SnackbarHost(snackbarState) }) { padding ->
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
                Text(formatUsd(pop.currentValueCents), style = MaterialTheme.typography.displayLarge.copy(fontSize = 34.sp), color = c.text)
                pop.exclusiveTo?.let { Text("Exclusive · $it", fontFamily = Mono, fontSize = 11.sp, color = c.textDim) }
            }
            when {
                market.loading ->
                    PlaceholderCard("Median active listing", "Loading live market…", "eBay Browse")
                market.market != null ->
                    MarketSection(market.market, market.buyUrl)
                else ->
                    PlaceholderCard("Median active listing", "Market data is unavailable right now.", "eBay Browse")
            }
            GhostButton(
                if (snapshotting) "Snapshotting…" else "⊹ Snapshot today's price",
                onClick = { if (!snapshotting) onSnapshot() },
                accent = true,
            )
            ValueHistoryCard(history)
            GhostButton("✎ Edit details", onClick = {})
            Row(Modifier.fillMaxWidth().padding(vertical = 8.dp), horizontalArrangement = Arrangement.Center, verticalAlignment = Alignment.CenterVertically) {
                Text("Add another", color = c.textDim, fontSize = 13.sp)
                Text("  ·  ", color = c.textFaint, fontSize = 13.sp)
                Text(
                    "Delete from collection", color = c.down, fontSize = 13.sp,
                    modifier = Modifier.clickable { confirmDelete = true },
                )
            }
            Spacer(Modifier.height(14.dp))
        }
    }

    if (confirmDelete) {
        AlertDialog(
            onDismissRequest = { confirmDelete = false },
            containerColor = c.surface,
            title = { Text("Delete this pop?", color = c.text, fontWeight = FontWeight.Bold) },
            text = {
                Text(
                    "“${pop.name}” and its value history will be removed from your collection. This can't be undone.",
                    color = c.textDim, fontSize = 14.sp, lineHeight = 20.sp,
                )
            },
            confirmButton = {
                TextButton(onClick = { confirmDelete = false; onDelete() }) {
                    Text("Delete", color = c.down, fontWeight = FontWeight.SemiBold)
                }
            },
            dismissButton = { TextButton(onClick = { confirmDelete = false }) { Text("Cancel", color = c.textDim) } },
        )
    }
}

/**
 * Per-item value history over the 90-day default. Renders the real snapshot series once there are two
 * points; below that, an honest "tracking started" state rather than a flat line on an empty axis.
 */
@Composable
private fun ValueHistoryCard(history: List<ValueSnapshot>) {
    val c = ArcanaTheme.colors
    if (history.size < 2) {
        PlaceholderCard(
            "Value history",
            "Price tracking started — first sync this Sunday. The chart fills in as snapshots accrue.",
            "90 days",
        )
        return
    }
    var range by rememberSaveable { mutableStateOf(ChartRange.Days90) }
    val values = remember(history, range) { history.withinRange(range) { it.at }.map { it.valueCents } }
    Column(
        Modifier.fillMaxWidth().clip(RoundedCornerShape(18.dp)).background(c.surface)
            .border(1.dp, c.hairline, RoundedCornerShape(18.dp)).padding(15.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
            Text("Value history", color = c.text, fontSize = 13.sp, fontWeight = FontWeight.SemiBold)
            RangeSelector(selected = range, onSelect = { range = it })
        }
        ValueSparkline(
            values = values,
            lineColor = c.up,
            fillColor = c.up.copy(alpha = 0.12f),
            modifier = Modifier.fillMaxWidth().height(90.dp),
        )
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
            Text("${formatUsd(values.first())} tracked", color = c.textDim, fontFamily = Mono, fontSize = 11.sp)
            Text("${formatUsd(values.last())} now", color = c.textDim, fontFamily = Mono, fontSize = 11.sp)
        }
    }
}

private val MONTH_YEAR = DateTimeFormatter.ofPattern("MMM yyyy")
