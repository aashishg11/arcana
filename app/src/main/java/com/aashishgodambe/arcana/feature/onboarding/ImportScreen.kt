package com.aashishgodambe.arcana.feature.onboarding

import android.net.Uri
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import com.aashishgodambe.arcana.core.data.importer.CollectionImporter
import com.aashishgodambe.arcana.core.data.importer.model.ImportResult
import com.aashishgodambe.arcana.core.data.repository.CollectibleRepository
import com.aashishgodambe.arcana.ui.component.GhostButton
import com.aashishgodambe.arcana.ui.component.Hairline
import com.aashishgodambe.arcana.ui.formatUsd
import com.aashishgodambe.arcana.ui.theme.ArcanaTheme
import com.aashishgodambe.arcana.ui.theme.Mono
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import javax.inject.Inject

data class FeedLine(val ok: Boolean, val text: String)

sealed interface ImportUiState {
    data object Parsing : ImportUiState
    data class Writing(val written: Int, val total: Int, val feed: List<FeedLine>) : ImportUiState
    data class Complete(val count: Int, val skipped: Int) : ImportUiState
    data class Failed(val message: String) : ImportUiState
}

@HiltViewModel
class ImportViewModel @Inject constructor(
    private val importer: CollectionImporter,
    private val repository: CollectibleRepository,
    savedStateHandle: SavedStateHandle,
) : ViewModel() {

    private val uri: Uri = Uri.parse(checkNotNull(savedStateHandle["uri"]))
    private val _state = MutableStateFlow<ImportUiState>(ImportUiState.Parsing)
    val state: StateFlow<ImportUiState> = _state.asStateFlow()

    init { runImport() }

    private fun runImport() {
        viewModelScope.launch {
            when (val result = importer.parse(uri)) {
                is ImportResult.Failed -> _state.value = ImportUiState.Failed(result.message)
                is ImportResult.Success -> {
                    val total = result.items.size
                    val feed = ArrayDeque<FeedLine>()
                    _state.value = ImportUiState.Writing(0, total, emptyList())
                    val count = repository.importFrom(result.items) { written, item ->
                        val value = item.estimatedValueCents?.let { " · ${formatUsd(it)}" } ?: ""
                        feed.addLast(FeedLine(true, "${item.name}$value"))
                        while (feed.size > 5) feed.removeFirst()
                        _state.value = ImportUiState.Writing(written, total, feed.toList())
                    }
                    _state.value = ImportUiState.Complete(count, result.itemsSkipped)
                }
            }
        }
    }
}

@Composable
fun ImportScreen(
    onComplete: () -> Unit,
    onBack: () -> Unit,
    vm: ImportViewModel = hiltViewModel(),
) {
    val c = ArcanaTheme.colors
    val state by vm.state.collectAsStateWithLifecycle()

    LaunchedEffect(state) { if (state is ImportUiState.Complete) onComplete() }

    Scaffold(containerColor = c.bg) { padding ->
        Box(Modifier.padding(padding).fillMaxSize()) {
            Column(
                Modifier.fillMaxSize().padding(horizontal = 30.dp),
                verticalArrangement = Arrangement.Center,
                horizontalAlignment = Alignment.CenterHorizontally,
            ) {
                when (val s = state) {
                    is ImportUiState.Parsing -> {
                        CircularProgressIndicator(color = c.iris, trackColor = c.hairline)
                        Spacer(Modifier.height(20.dp))
                        Text("Reading your collection…", color = c.text, fontWeight = FontWeight.Medium)
                    }
                    is ImportUiState.Writing -> {
                        val pct = if (s.total > 0) s.written.toFloat() / s.total else 0f
                        Box(Modifier.size(96.dp), contentAlignment = Alignment.Center) {
                            CircularProgressIndicator(
                                progress = { pct }, modifier = Modifier.size(96.dp),
                                color = c.iris, trackColor = c.hairline, strokeWidth = 6.dp,
                            )
                            Text("${(pct * 100).toInt()}%", fontFamily = Mono, fontWeight = FontWeight.Bold, color = c.text)
                        }
                        Spacer(Modifier.height(26.dp))
                        Text("Adding your collection…", style = androidx.compose.material3.MaterialTheme.typography.titleLarge, color = c.text)
                        Spacer(Modifier.height(8.dp))
                        Text("${s.written} of ${s.total} items", fontFamily = Mono, fontSize = 14.sp, color = c.iris)
                        Spacer(Modifier.height(24.dp))
                        Column(Modifier.fillMaxWidth()) {
                            Hairline()
                            Spacer(Modifier.height(12.dp))
                            s.feed.forEach { line ->
                                Text(
                                    "${if (line.ok) "✓" else "⚠"}  ${line.text}",
                                    fontFamily = Mono, fontSize = 11.sp,
                                    color = if (line.ok) c.up else c.gold,
                                    modifier = Modifier.padding(vertical = 2.dp),
                                )
                            }
                        }
                    }
                    is ImportUiState.Complete -> {
                        Text("✓", color = c.up, fontSize = 40.sp)
                        Spacer(Modifier.height(10.dp))
                        Text("${s.count} items added.", style = androidx.compose.material3.MaterialTheme.typography.titleLarge, color = c.text)
                    }
                    is ImportUiState.Failed -> {
                        Text("Import failed", style = androidx.compose.material3.MaterialTheme.typography.titleLarge, color = c.text)
                        Spacer(Modifier.height(8.dp))
                        Text(s.message, color = c.textDim, textAlign = TextAlign.Center)
                        Spacer(Modifier.height(18.dp))
                        GhostButton("Go back", onBack, Modifier.fillMaxWidth(0.6f))
                    }
                }
            }
            if (state is ImportUiState.Writing || state is ImportUiState.Parsing) {
                Text(
                    "Keep Arcana open — closing now stops the import partway.",
                    color = c.textFaint, fontSize = 12.sp, textAlign = TextAlign.Center,
                    modifier = Modifier.align(Alignment.BottomCenter).padding(horizontal = 30.dp, vertical = 26.dp),
                )
            }
        }
    }
}
