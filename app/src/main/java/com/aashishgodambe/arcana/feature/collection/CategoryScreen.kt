package com.aashishgodambe.arcana.feature.collection

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
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
import com.aashishgodambe.arcana.ui.component.Hairline
import com.aashishgodambe.arcana.ui.formatUsd
import com.aashishgodambe.arcana.ui.theme.ArcanaTheme
import com.aashishgodambe.arcana.ui.theme.Mono
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.stateIn
import javax.inject.Inject

@HiltViewModel
class CategoryViewModel @Inject constructor(
    repository: CollectibleRepository,
    savedStateHandle: SavedStateHandle,
) : ViewModel() {
    val listName: String = checkNotNull(savedStateHandle["list"])
    val items: StateFlow<List<Collectible>> = repository.observeByList(listName)
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())
}

@Composable
fun CategoryScreen(
    onItemClick: (Long) -> Unit,
    onBack: () -> Unit,
    vm: CategoryViewModel = hiltViewModel(),
) {
    val c = ArcanaTheme.colors
    val items by vm.items.collectAsStateWithLifecycle()

    Scaffold(containerColor = c.bg) { padding ->
        Column(Modifier.padding(padding).fillMaxSize()) {
            Row(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 10.dp), verticalAlignment = Alignment.CenterVertically) {
                Text("‹", color = c.text, fontSize = 26.sp, modifier = Modifier.clickable(onClick = onBack).padding(end = 12.dp))
                Text(vm.listName, style = androidx.compose.material3.MaterialTheme.typography.titleMedium, color = c.text)
                Spacer(Modifier.weight(1f))
                Text("${items.size}", fontFamily = Mono, fontSize = 12.sp, color = c.textFaint)
            }
            Hairline()
            LazyColumn(Modifier.fillMaxSize(), contentPadding = androidx.compose.foundation.layout.PaddingValues(vertical = 6.dp)) {
                items(items, key = { it.localId }) { item ->
                    ItemRow(item, onClick = { onItemClick(item.localId) })
                }
            }
        }
    }
}

@Composable
private fun ItemRow(item: Collectible, onClick: () -> Unit) {
    val c = ArcanaTheme.colors
    Row(
        Modifier.fillMaxWidth().clickable(onClick = onClick).padding(horizontal = 16.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Box(Modifier.size(56.dp).clip(RoundedCornerShape(11.dp)).background(c.surface)) {
            AsyncImage(
                model = item.imageUrl, contentDescription = item.name,
                contentScale = ContentScale.Crop, modifier = Modifier.fillMaxSize().aspectRatio(1f),
            )
        }
        Text(item.name, color = c.text, fontSize = 14.sp, fontWeight = FontWeight.Medium, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f))
        Text(formatUsd(item.estimatedValueCents), fontFamily = Mono, fontWeight = FontWeight.Bold, fontSize = 14.sp, color = c.text)
    }
}
