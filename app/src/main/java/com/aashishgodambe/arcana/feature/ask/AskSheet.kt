package com.aashishgodambe.arcana.feature.ask

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Text
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.aashishgodambe.arcana.core.ai.model.InferenceLocation
import com.aashishgodambe.arcana.ui.component.InferenceBadge
import com.aashishgodambe.arcana.ui.component.StreamingText
import com.aashishgodambe.arcana.ui.theme.ArcanaTheme
import com.aashishgodambe.arcana.ui.theme.Display
import com.aashishgodambe.arcana.ui.theme.Mono

/**
 * "Ask Arcana" — a [ModalBottomSheet] (not a nav route). Multi-turn hybrid inference; each turn
 * renders inline as question → retrieved chips → answer. The [InferenceBadge] flips on-device/cloud
 * per answer, [StreamingText] animates the tokens. Week 9 upgrades retrieval to RAG over embeddings.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun AskSheet(onDismiss: () -> Unit, onItemClick: (Long) -> Unit, vm: AskViewModel = hiltViewModel()) {
    val c = ArcanaTheme.colors
    val s by vm.state.collectAsStateWithLifecycle()
    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
    // The message list grows with content, then scrolls once it reaches ~72% of the screen.
    val maxListHeight = (LocalConfiguration.current.screenHeightDp * 0.72f).dp

    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = sheetState,
        containerColor = c.sheet,
        dragHandle = { Box(Modifier.padding(top = 6.dp).size(width = 38.dp, height = 4.dp).clip(RoundedCornerShape(999.dp)).background(c.hairlineStrong)) },
    ) {
        // Wrap content: the sheet opens compact and grows in height as the conversation fills in.
        Column(Modifier.fillMaxWidth().padding(horizontal = 18.dp).imePadding()) {
            // head
            Row(Modifier.fillMaxWidth().padding(bottom = 14.dp), verticalAlignment = Alignment.CenterVertically) {
                Text("Ask Arcana", fontFamily = Display, fontWeight = FontWeight.Bold, fontSize = 18.sp, color = c.text, modifier = Modifier.weight(1f))
                InferenceBadge(s.badge)
            }

            // conversation — each turn renders inline: question → retrieved chips → answer
            val listState = rememberLazyListState()
            // Scroll to the newest turn only when one is ADDED — not on every streaming token, which
            // would repeatedly yank the list to the bottom and fight a manual scroll.
            LaunchedEffect(s.turns.size) {
                if (s.turns.isNotEmpty()) listState.animateScrollToItem(s.turns.size - 1)
            }
            LazyColumn(
                state = listState,
                modifier = Modifier.fillMaxWidth().heightIn(max = maxListHeight).weight(1f, fill = false),
                verticalArrangement = Arrangement.spacedBy(14.dp),
                contentPadding = PaddingValues(vertical = 4.dp),
            ) {
                if (s.turns.isEmpty()) {
                    item {
                        Text(
                            "Ask about your collection — answered privately, on-device.",
                            color = c.textFaint, fontSize = 13.sp, modifier = Modifier.padding(vertical = 20.dp),
                        )
                    }
                }
                itemsIndexed(s.turns) { index, turn ->
                    Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                        Bubble(turn.question, fromUser = true, streaming = false)
                        if (turn.grounding.isNotEmpty()) GroundingStrip(turn.grounding, onItemClick)
                        when {
                            turn.error != null -> Text("⚠ ${turn.error}", color = c.down, fontFamily = Mono, fontSize = 12.sp)
                            turn.answer != null -> Bubble(turn.answer, fromUser = false, streaming = false)
                            turn.streamingAnswer != null -> Bubble(turn.streamingAnswer, fromUser = false, streaming = true)
                        }
                        turn.metadata?.let { meta ->
                            Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                                BenchmarkCaption(meta.executedOn, meta.totalLatencyMs, meta.firstTokenLatencyMs, meta.outputTokenCount)
                                if (index == s.turns.lastIndex && !s.isRunning) {
                                    val ranOnCloud = meta.executedOn == InferenceLocation.Cloud
                                    Text(
                                        if (ranOnCloud) "↻ Compare on-device" else "↻ Compare on cloud",
                                        fontFamily = Mono, fontSize = 11.sp, color = if (ranOnCloud) c.iris else c.cloud,
                                        modifier = Modifier.clickable { if (ranOnCloud) vm.compareOnDevice() else vm.compareOnCloud() },
                                    )
                                }
                            }
                        }
                    }
                }
            }

            // suggestion chip (only before the first question)
            if (s.turns.isEmpty() && !s.isRunning) {
                Spacer(Modifier.height(10.dp))
                Text(
                    vm.suggestedQuestion,
                    fontSize = 13.sp, color = c.iris, fontWeight = FontWeight.Medium,
                    modifier = Modifier.clip(RoundedCornerShape(999.dp)).border(1.dp, c.iris, RoundedCornerShape(999.dp))
                        .clickable { vm.ask(vm.suggestedQuestion) }.padding(horizontal = 14.dp, vertical = 9.dp),
                )
            }

            // input
            var field by remember { mutableStateOf("") }
            Row(
                Modifier.fillMaxWidth().padding(top = 12.dp, bottom = 8.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(10.dp),
            ) {
                Box(
                    Modifier.weight(1f).clip(RoundedCornerShape(14.dp)).background(c.surface).border(1.dp, c.hairline, RoundedCornerShape(14.dp)).padding(horizontal = 15.dp, vertical = 13.dp),
                ) {
                    if (field.isEmpty()) Text("Ask about your collection…", color = c.textFaint, fontSize = 14.sp)
                    BasicTextField(
                        value = field,
                        onValueChange = { field = it },
                        singleLine = true,
                        enabled = !s.isRunning,
                        textStyle = TextStyle(color = c.text, fontSize = 14.sp),
                        cursorBrush = SolidColor(c.iris),
                        modifier = Modifier.fillMaxWidth(),
                    )
                }
                val canSend = field.isNotBlank() && !s.isRunning
                Box(
                    Modifier.size(46.dp).clip(RoundedCornerShape(13.dp))
                        .background(if (canSend) c.iris else c.iris.copy(alpha = 0.4f))
                        .clickable(enabled = canSend) { vm.ask(field); field = "" },
                    contentAlignment = Alignment.Center,
                ) {
                    Text("↑", color = Color.White, fontSize = 18.sp, fontWeight = FontWeight.Bold)
                }
            }
        }
    }
}

@Composable
private fun GroundingStrip(grounding: List<GroundingItem>, onItemClick: (Long) -> Unit) {
    val c = ArcanaTheme.colors
    Column(
        Modifier.fillMaxWidth().clip(RoundedCornerShape(12.dp)).background(c.elevated)
            .border(1.dp, c.hairlineStrong, RoundedCornerShape(12.dp)).padding(10.dp, 10.dp),
    ) {
        Text("◆ RETRIEVED FROM YOUR COLLECTION · ${grounding.size} ITEMS", fontFamily = Mono, fontSize = 10.sp, color = c.textFaint, letterSpacing = 0.8.sp)
        Spacer(Modifier.height(7.dp))
        FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            grounding.forEach { g ->
                Text(
                    g.label, fontFamily = Mono, fontSize = 11.sp, color = c.textDim,
                    modifier = Modifier.clip(RoundedCornerShape(7.dp)).background(c.surface).border(1.dp, c.hairline, RoundedCornerShape(7.dp)).clickable { onItemClick(g.localId) }.padding(horizontal = 8.dp, vertical = 4.dp),
                )
            }
        }
    }
}

@Composable
private fun Bubble(text: String, fromUser: Boolean, streaming: Boolean) {
    val c = ArcanaTheme.colors
    Row(Modifier.fillMaxWidth(), horizontalArrangement = if (fromUser) Arrangement.End else Arrangement.Start) {
        Box(
            Modifier.fillMaxWidth(0.86f).clip(
                RoundedCornerShape(
                    topStart = 16.dp, topEnd = 16.dp,
                    bottomEnd = if (fromUser) 5.dp else 16.dp,
                    bottomStart = if (fromUser) 16.dp else 5.dp,
                ),
            ).background(if (fromUser) c.iris else c.surface)
                .then(if (fromUser) Modifier else Modifier.border(1.dp, c.hairline, RoundedCornerShape(topStart = 16.dp, topEnd = 16.dp, bottomEnd = 16.dp, bottomStart = 5.dp)))
                .padding(horizontal = 14.dp, vertical = 12.dp),
        ) {
            if (fromUser) {
                Text(text, color = Color.White, fontSize = 14.sp, lineHeight = 22.sp)
            } else {
                StreamingText(text = text, streaming = streaming, color = c.text)
            }
        }
    }
}

@Composable
private fun BenchmarkCaption(location: InferenceLocation, totalMs: Long, firstTokenMs: Long?, tokens: Int?) {
    val c = ArcanaTheme.colors
    val where = when (location) {
        InferenceLocation.OnDevice -> "on-device"
        InferenceLocation.OnDeviceOwnModel -> "your gemma"
        InferenceLocation.Cloud -> "cloud"
    }
    val parts = buildList {
        add(where)
        add("$totalMs ms")
        firstTokenMs?.let { add("first-token $it ms") }
        tokens?.let { add("$it tok") }
    }
    Text(parts.joinToString("  ·  "), fontFamily = Mono, fontSize = 10.sp, color = c.textFaint)
}
