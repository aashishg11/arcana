package com.aashishgodambe.arcana.feature.collection

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
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
import com.aashishgodambe.arcana.core.domain.model.currentValueCents
import com.aashishgodambe.arcana.ui.component.Hairline
import com.aashishgodambe.arcana.ui.component.QuantityBadge
import com.aashishgodambe.arcana.ui.formatUsd
import com.aashishgodambe.arcana.ui.theme.ArcanaTheme
import com.aashishgodambe.arcana.ui.theme.Mono
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import java.time.Duration
import java.time.Instant
import javax.inject.Inject
import kotlin.math.abs

/** The sort applied to a list — value-first by default, matching the portfolio's value-first logic. */
enum class ItemSort(val label: String) { Value("Value"), Name("Name") }

/** One list row: the collectible plus its value change over the trailing window (null when history is thin). */
data class CategoryRow(val item: Collectible, val deltaCents: Int?)

data class CategoryUiState(
    val listName: String = "",
    val subtitle: String = "",
    val totalValueCents: Int = 0,
    val listDeltaCents: Int? = null,   // the list's value change over the trailing window
    val rows: List<CategoryRow> = emptyList(),
    val sort: ItemSort = ItemSort.Value,
)

/** The trailing window for the "▲ $312 · 30d" list delta and the per-row deltas. */
private const val DELTA_DAYS = 30L

@HiltViewModel
class CategoryViewModel @Inject constructor(
    private val repository: CollectibleRepository,
    savedStateHandle: SavedStateHandle,
) : ViewModel() {
    val listName: String = checkNotNull(savedStateHandle["list"])

    private val sortFlow = MutableStateFlow(ItemSort.Value)

    // Shared so the item stream is collected once across the three derived flows below.
    private val itemsFlow: StateFlow<List<Collectible>> =
        repository.observeByList(listName)
            .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    // Per-item 30-day deltas — a one-shot history read per item, recomputed when the item set changes.
    private val deltas: Flow<Map<Long, Int?>> = itemsFlow.map { items ->
        items.associate { it.localId to itemDelta(it.localId) }
    }

    // The list's 30-day delta, from the per-list aggregate value series.
    private val listDelta: Flow<Int?> = itemsFlow.map {
        deltaOverWindow(repository.listValueSeries()[listName].orEmpty().map { p -> p.totalValueCents to p.at })
    }

    val state: StateFlow<CategoryUiState> =
        combine(itemsFlow, deltas, listDelta, sortFlow) { items, itemDeltas, ld, sort ->
            val sorted = when (sort) {
                ItemSort.Value -> items.sortedByDescending { it.currentValueCents }
                ItemSort.Name -> items.sortedBy { it.name.lowercase() }
            }
            CategoryUiState(
                listName = listName,
                subtitle = subtitleFor(items),
                totalValueCents = items.sumOf { it.currentValueCents * it.quantity },
                listDeltaCents = ld,
                rows = sorted.map { CategoryRow(it, itemDeltas[it.localId]) },
                sort = sort,
            )
        }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), CategoryUiState(listName = listName))

    fun setSort(sort: ItemSort) { sortFlow.value = sort }

    private suspend fun itemDelta(id: Long): Int? =
        deltaOverWindow(repository.observeValueHistory(id).first().map { it.valueCents to it.at })

    /** "Pop! Television · 24 items" — the most common series across the list, plus the count. */
    private fun subtitleFor(items: List<Collectible>): String {
        val counts = LinkedHashMap<String, Int>()   // insertion-ordered so ties resolve to the first seen
        for (item in items) for (s in item.series) if (s.isNotBlank()) counts[s] = (counts[s] ?: 0) + 1
        val topSeries = counts.maxByOrNull { it.value }?.key
        return listOfNotNull(topSeries, "${items.size} items").joinToString(" · ")
    }
}

/** Value change over the trailing [DELTA_DAYS] from a (value, at) series; null under two points. */
private fun deltaOverWindow(points: List<Pair<Int, Instant>>): Int? {
    if (points.size < 2) return null
    val sorted = points.sortedBy { it.second }
    val latest = sorted.last()
    val cutoff = latest.second.minus(Duration.ofDays(DELTA_DAYS))
    val ref = sorted.lastOrNull { it.second <= cutoff } ?: sorted.first()
    return if (ref === latest) null else latest.first - ref.first
}

@Composable
fun CategoryScreen(
    onItemClick: (Long) -> Unit,
    onBack: () -> Unit,
    vm: CategoryViewModel = hiltViewModel(),
) {
    val c = ArcanaTheme.colors
    val s by vm.state.collectAsStateWithLifecycle()

    Scaffold(containerColor = c.bg) { padding ->
        Column(Modifier.padding(padding).fillMaxSize()) {
            CategoryHeader(s, onBack = onBack, onSort = vm::setSort)
            Hairline()
            LazyColumn(Modifier.fillMaxSize(), contentPadding = PaddingValues(vertical = 4.dp)) {
                items(s.rows, key = { it.item.localId }) { row ->
                    ItemRow(row, onClick = { onItemClick(row.item.localId) })
                    Hairline(Modifier.padding(horizontal = 16.dp))
                }
            }
        }
    }
}

/** The sticky header: title + series/count subtitle + sort, then the list total and its trailing delta. */
@Composable
private fun CategoryHeader(s: CategoryUiState, onBack: () -> Unit, onSort: (ItemSort) -> Unit) {
    val c = ArcanaTheme.colors
    Column(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 12.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text("‹", color = c.text, fontSize = 26.sp, modifier = Modifier.clickable(onClick = onBack).padding(end = 12.dp))
            Column(Modifier.weight(1f)) {
                Text(s.listName, style = MaterialTheme.typography.titleMedium, color = c.text)
                if (s.subtitle.isNotBlank()) {
                    Text(s.subtitle, fontFamily = Mono, fontSize = 12.sp, color = c.textFaint)
                }
            }
            SortPill(s.sort, onSort)
        }
        Spacer(Modifier.height(12.dp))
        Row(verticalAlignment = Alignment.Bottom, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            Text(formatUsd(s.totalValueCents), fontFamily = Mono, fontWeight = FontWeight.Bold, fontSize = 22.sp, color = c.text)
            s.listDeltaCents?.let { DeltaLabel(it, suffix = " · 30d") }
        }
    }
}

@Composable
private fun SortPill(sort: ItemSort, onSort: (ItemSort) -> Unit) {
    val c = ArcanaTheme.colors
    var open by remember { mutableStateOf(false) }
    Box {
        Row(
            Modifier.clip(RoundedCornerShape(999.dp)).border(1.dp, c.hairlineStrong, RoundedCornerShape(999.dp))
                .clickable { open = true }.padding(horizontal = 12.dp, vertical = 6.dp),
            verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(4.dp),
        ) {
            Text(sort.label, fontFamily = Mono, fontSize = 12.sp, color = c.textDim)
            Text("▾", color = c.textFaint, fontSize = 10.sp)
        }
        DropdownMenu(expanded = open, onDismissRequest = { open = false }, containerColor = c.surface) {
            ItemSort.entries.forEach { option ->
                DropdownMenuItem(
                    text = { Text(option.label, color = if (option == sort) c.iris else c.text, fontSize = 14.sp) },
                    onClick = { onSort(option); open = false },
                )
            }
        }
    }
}

/** A small gold badge for an exclusivity — NFT redemption or a retailer exclusive (Amazon, Target, …). */
@Composable
private fun ExclusiveBadge(label: String) {
    val c = ArcanaTheme.colors
    Text(
        label,
        fontFamily = Mono, fontSize = 9.sp, fontWeight = FontWeight.Medium, color = c.gold, maxLines = 1,
        modifier = Modifier.clip(RoundedCornerShape(5.dp)).background(c.goldSoft).padding(horizontal = 6.dp, vertical = 2.dp),
    )
}

// Droppp (droppp.io) is Funko's NFT marketplace — every NFT drop comes through it — so HobbyDB's "Droppp",
// "droppp.io", and "NFT Redeemable" all denote the same thing: an NFT. They collapse to the single ◆ NFT
// badge and never surface as a separate retailer.
private val NFT_EXCLUSIVE_TOKENS = setOf("nft redeemable", "droppp", "droppp.io")

/** An item reads as NFT if the strict catalog flag is set OR its "Exclusive To" names an NFT channel
 *  (Droppp). Display-only — it does NOT touch [FunkoPop.isNftRedeemable] or the catalog NFT count. */
private fun isNftExclusive(item: FunkoPop?): Boolean {
    if (item == null) return false
    if (item.isNftRedeemable) return true
    return item.exclusiveTo?.split(",")?.any { it.trim().lowercase() in NFT_EXCLUSIVE_TOKENS } == true
}

/** The primary exclusive channel from HobbyDB's comma-joined "Exclusive To", dropping NFT tokens (the
 *  ◆ NFT badge carries those): "Funko Shop, FunKon 2021" → "Funko Shop"; "Droppp" / "NFT Redeemable" → null. */
private fun exclusiveRetailer(exclusiveTo: String?): String? {
    if (exclusiveTo.isNullOrBlank()) return null
    return exclusiveTo.split(",").map { it.trim() }
        .firstOrNull { it.isNotBlank() && it.lowercase() !in NFT_EXCLUSIVE_TOKENS }
}

/** A colored ▲/▼ change indicator; green up, red down, with an optional "· 30d" suffix. */
@Composable
private fun DeltaLabel(deltaCents: Int, suffix: String = "") {
    val c = ArcanaTheme.colors
    val up = deltaCents >= 0
    Text(
        "${if (up) "▲" else "▼"} ${formatUsd(abs(deltaCents))}$suffix",
        fontFamily = Mono, fontSize = 12.sp, fontWeight = FontWeight.Medium,
        color = if (up) c.up else c.down,
    )
}

@Composable
private fun ItemRow(row: CategoryRow, onClick: () -> Unit) {
    val c = ArcanaTheme.colors
    val item = row.item
    val funko = item as? FunkoPop
    Row(
        Modifier.fillMaxWidth().clickable(onClick = onClick).padding(horizontal = 16.dp, vertical = 11.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Box(Modifier.size(56.dp).clip(RoundedCornerShape(11.dp)).background(c.surface)) {
            AsyncImage(
                model = item.imageUrl, contentDescription = item.name,
                contentScale = ContentScale.Crop, modifier = Modifier.fillMaxSize().aspectRatio(1f),
            )
        }
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(3.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                Text(
                    item.name, color = c.text, fontSize = 14.sp, fontWeight = FontWeight.Medium,
                    maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f, fill = false),
                )
                QuantityBadge(item.quantity)
            }
            // #number + exclusivity badges, beside the number: NFT and retailer exclusives (Amazon, Funko
            // Shop, Target, …) all get the same gold badge — NFT is just one exclusive among many.
            val retailer = exclusiveRetailer(funko?.exclusiveTo)
            val isNft = isNftExclusive(funko)
            if (funko?.popNumber != null || isNft || retailer != null) {
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    funko?.popNumber?.let { Text("#$it", fontFamily = Mono, fontSize = 11.sp, color = c.textFaint) }
                    if (isNft) ExclusiveBadge("◆ NFT")
                    retailer?.let { ExclusiveBadge(it) }
                }
            }
        }
        Column(horizontalAlignment = Alignment.End, verticalArrangement = Arrangement.spacedBy(2.dp)) {
            Text(formatUsd(item.currentValueCents), fontFamily = Mono, fontWeight = FontWeight.Bold, fontSize = 14.sp, color = c.text)
            row.deltaCents?.let { DeltaLabel(it) }
        }
    }
}
