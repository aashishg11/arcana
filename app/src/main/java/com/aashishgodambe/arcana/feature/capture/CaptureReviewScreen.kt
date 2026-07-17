package com.aashishgodambe.arcana.feature.capture

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.aashishgodambe.arcana.core.ai.model.InferenceLocation
import com.aashishgodambe.arcana.ui.component.ArcanaChip
import com.aashishgodambe.arcana.ui.component.ChipStyle
import com.aashishgodambe.arcana.ui.component.GhostButton
import com.aashishgodambe.arcana.ui.component.InferenceBadge
import com.aashishgodambe.arcana.ui.component.MarketSection
import com.aashishgodambe.arcana.ui.component.PillButton
import com.aashishgodambe.arcana.ui.theme.ArcanaTheme
import com.aashishgodambe.arcana.ui.theme.Mono

/**
 * Capture Review — the hero. Renders the Week-8 `Flow<CascadeState>` as designed beats **in the order they
 * actually resolve** (Week-9 measurements, not the wireframe's fiction): the segmentation outline + scanline
 * on the frozen frame, the `#NNN` OCR callout, the catalog-chain status lines, and the on-device→cloud
 * badge flip — settling into the identity. A fast local hit settles in ~1.6s and simply looks fast; the
 * on-device description is a **late, optional** line (it usually lands after Settled, or never on a safety
 * refusal), so its absence is designed to be invisible.
 *
 * Pure rendering: every beat is a field of [CaptureReviewUiState]; no cascade logic lives here.
 */
@Composable
fun CaptureReviewScreen(
    onClose: () -> Unit,
    onRescan: () -> Unit,
    onSaved: (Long) -> Unit,
    vm: CaptureReviewViewModel = hiltViewModel(),
) {
    val c = ArcanaTheme.colors
    val s by vm.state.collectAsStateWithLifecycle()
    val settled = s.settled
    var pickerOpen by remember { mutableStateOf(false) }

    // Once saved, land on the item's Detail (the canonical post-capture destination).
    LaunchedEffect(s.savedId) { s.savedId?.let(onSaved) }

    Scaffold(containerColor = c.bg) { padding ->
        Column(
            Modifier.padding(padding).fillMaxSize().verticalScroll(rememberScrollState()).padding(horizontal = 20.dp),
        ) {
            // App bar: close + the on-device/cloud badge (On-device while the on-device stages run; flips
            // to Cloud only if the catalog escalates — the privacy money-shot).
            Row(Modifier.fillMaxWidth().padding(top = 8.dp, bottom = 12.dp), verticalAlignment = Alignment.CenterVertically) {
                Box(
                    Modifier.size(38.dp).clip(CircleShape).background(c.surface)
                        .border(1.dp, c.hairline, CircleShape).clickable(onClick = onClose),
                    contentAlignment = Alignment.Center,
                ) { Text("✕", color = c.textDim, fontSize = 16.sp) }
                Spacer(Modifier.weight(1f))
                InferenceBadge(settled?.telemetry?.resolvedOn ?: InferenceLocation.OnDevice)
            }

            HeroPhoto(s)
            Spacer(Modifier.height(16.dp))

            when {
                s.failure != null -> FailureBlock(s.failure!!, onRescan)
                settled != null -> SettledIdentity(
                    s = s,
                    onRescan = onRescan,
                    onAddAnother = vm::addAnother,
                    onSaveClick = { pickerOpen = true },
                )
                else -> RunningBeats(s)
            }

            Spacer(Modifier.height(40.dp))
        }
    }

    if (pickerOpen) {
        ListPickerDialog(
            lists = s.availableLists,
            suggested = suggestedList(s),
            onDismiss = { pickerOpen = false },
            onPick = { listName -> pickerOpen = false; vm.save(listName) },
        )
    }
}

/** The frozen frame with the live cascade overlays: scanline, segmentation outline, phase chip, #NNN callout. */
@Composable
private fun HeroPhoto(s: CaptureReviewUiState) {
    val c = ArcanaTheme.colors
    val shown = s.subject ?: s.frame
    val inset by animateFloatAsState(if (s.subjectReady && s.subject != null) 26f else 0f, tween(400), label = "inset")

    Box(
        Modifier.fillMaxWidth().height(320.dp).clip(RoundedCornerShape(20.dp))
            .background(
                Brush.linearGradient(listOf(c.elevated, c.surface)),
            ),
        contentAlignment = Alignment.Center,
    ) {
        if (shown != null) {
            Image(
                bitmap = shown.asImageBitmap(),
                contentDescription = "captured item",
                contentScale = ContentScale.Fit,
                modifier = Modifier.fillMaxSize().padding(inset.dp),
            )
        }

        if (s.running && !s.barcodePath) ScanLine(c.iris)
        if (s.subjectReady && s.subject != null) SegmentOutline(c.iris)

        // Phase chip, top-left.
        phaseLabel(s)?.let { label ->
            Box(Modifier.align(Alignment.TopStart).padding(14.dp)) {
                ArcanaChip(label, ChipStyle.Iris)
            }
        }

        // #NNN OCR callout, bottom-right, with a leader line — mono, because it's machine-read data.
        AnimatedVisibility(
            visible = s.popNumber != null,
            enter = fadeIn(),
            modifier = Modifier.align(Alignment.BottomEnd).padding(18.dp),
        ) {
            s.popNumber?.let { OcrCallout(it) }
        }
    }
}

@Composable
private fun BoxScope.ScanLine(color: Color) {
    val t = rememberInfiniteTransition(label = "scan")
    val y by t.animateFloat(
        initialValue = 0.16f,
        targetValue = 0.84f,
        animationSpec = infiniteRepeatable(tween(1800, easing = FastOutSlowInEasing), RepeatMode.Reverse),
        label = "scanY",
    )
    Canvas(Modifier.matchParentSize()) {
        val yy = size.height * y
        drawRect(
            brush = Brush.horizontalGradient(listOf(Color.Transparent, color.copy(alpha = 0.85f), Color.Transparent)),
            topLeft = Offset(0f, yy - 1.5f),
            size = Size(size.width, 3f),
        )
    }
}

@Composable
private fun BoxScope.SegmentOutline(color: Color) {
    val t = rememberInfiniteTransition(label = "dash")
    val phase by t.animateFloat(
        initialValue = 0f,
        targetValue = 26f,
        animationSpec = infiniteRepeatable(tween(650, easing = LinearEasing), RepeatMode.Restart),
        label = "dashPhase",
    )
    Canvas(Modifier.matchParentSize().padding(18.dp)) {
        drawRoundRect(
            color = color.copy(alpha = 0.9f),
            cornerRadius = CornerRadius(48f, 48f),
            style = Stroke(
                width = 2.dp.toPx(),
                pathEffect = PathEffect.dashPathEffect(floatArrayOf(15f, 11f), phase),
            ),
        )
    }
}

@Composable
private fun OcrCallout(number: String) {
    val c = ArcanaTheme.colors
    Row(verticalAlignment = Alignment.CenterVertically) {
        Box(Modifier.width(30.dp).height(1.dp).background(c.text.copy(alpha = 0.5f)))
        Spacer(Modifier.width(6.dp))
        Text(
            "#$number",
            fontFamily = Mono, fontWeight = FontWeight.Bold, fontSize = 14.sp, color = c.bg,
            modifier = Modifier.clip(RoundedCornerShape(8.dp)).background(c.text).padding(horizontal = 9.dp, vertical = 5.dp),
        )
    }
}

/** The catalog-chain status lines, appearing as each beat resolves — in real resolution order. */
@Composable
private fun RunningBeats(s: CaptureReviewUiState) {
    Column(verticalArrangement = Arrangement.spacedBy(9.dp)) {
        if (s.popNumber != null) StatusLine(done = true, label = "Box number read", detail = "OCR → #${s.popNumber}")
        if (s.subjectReady) StatusLine(done = true, label = "Subject isolated", detail = "ML Kit segmentation")
        if (s.matching) StatusLine(done = false, label = "Checking your collection…", detail = "local · eBay")
        Spacer(Modifier.height(2.dp))
        ElapsedLine()
    }
}

/** A live "cascade running · N.Ns elapsed" ticker — makes the on-device work read as actively in progress. */
@Composable
private fun ElapsedLine() {
    val c = ArcanaTheme.colors
    var elapsedMs by remember { mutableStateOf(0L) }
    LaunchedEffect(Unit) {
        val start = System.nanoTime()
        while (true) {
            elapsedMs = (System.nanoTime() - start) / 1_000_000
            kotlinx.coroutines.delay(100)
        }
    }
    Text(
        "cascade running · ${"%.1f".format(elapsedMs / 1000.0)}s elapsed",
        fontFamily = Mono, fontSize = 10.sp, color = c.textFaint,
    )
}

@Composable
private fun StatusLine(done: Boolean, label: String, detail: String) {
    val c = ArcanaTheme.colors
    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
        if (done) {
            Text("✓", color = c.iris, fontSize = 13.sp, fontWeight = FontWeight.Bold)
        } else {
            // A running spinner dot for the in-flight beat.
            val t = rememberInfiniteTransition(label = "spin")
            val a by t.animateFloat(0.3f, 1f, infiniteRepeatable(tween(600), RepeatMode.Reverse), label = "spinA")
            Box(Modifier.size(9.dp).clip(CircleShape).background(c.iris.copy(alpha = a)))
        }
        Text(label, fontFamily = Mono, fontSize = 12.sp, color = c.textDim)
        Text(detail, fontFamily = Mono, fontSize = 11.sp, color = c.textFaint)
    }
}

/** The settled result: identity + source + confidence + ownership + the live market. Reweights for low
 *  confidence (heading softens, barcode promotes to a button). Save lands in Day 4. */
@Composable
private fun SettledIdentity(
    s: CaptureReviewUiState,
    onRescan: () -> Unit,
    onAddAnother: () -> Unit,
    onSaveClick: () -> Unit,
) {
    val c = ArcanaTheme.colors
    val settled = s.settled ?: return
    val entry = settled.entry
    if (entry == null) {
        UnresolvedBlock(onRescan)
        return
    }
    val lowConfidence = !settled.confident

    if (lowConfidence) {
        Text("Not sure — this might be", color = c.textDim, fontSize = 15.sp, fontWeight = FontWeight.Medium)
        Spacer(Modifier.height(4.dp))
    }
    Text(entry.name, color = c.text, fontSize = 26.sp, fontWeight = FontWeight.ExtraBold)
    Spacer(Modifier.height(10.dp))
    Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
        entry.series.firstOrNull()?.let { ArcanaChip(it) }
        entry.number?.let { ArcanaChip("#$it", ChipStyle.Iris) }
        entry.exclusiveTo?.let { ArcanaChip(it, ChipStyle.Gold) }
    }
    Spacer(Modifier.height(12.dp))
    Text("confidence ${(entry.confidence * 100).toInt()}%", fontFamily = Mono, fontSize = 12.sp, color = c.textDim)
    Spacer(Modifier.height(6.dp))
    ConfidenceBar(entry.confidence)

    if (settled.owned) {
        Spacer(Modifier.height(14.dp))
        OwnedCallout(s.ownedQuantity)
    }

    // Nano's on-device look at the box: what the figure *looks like*, never what it *is* (its identity
    // labels are unreliable). Trails Settled and may never arrive at all on a safety refusal — its absence
    // is designed to be invisible.
    s.description?.takeIf { it.isNotBlank() }?.let { description ->
        Spacer(Modifier.height(14.dp))
        NanoDescription(description)
    }

    s.market?.let { market ->
        Spacer(Modifier.height(16.dp))
        MarketSection(market, s.buyUrl.orEmpty())
    }

    // Primary action — the user is always in charge, even on a low-confidence read.
    Spacer(Modifier.height(16.dp))
    if (settled.owned) {
        PillButton("Add another to my collection", onClick = onAddAnother, enabled = !s.saving)
    } else {
        PillButton(if (s.saving) "Saving…" else "Save to collection", onClick = onSaveClick, enabled = !s.saving)
    }

    Spacer(Modifier.height(12.dp))
    BarcodeAffordance(promoted = lowConfidence, onRescan = onRescan)

    Spacer(Modifier.height(12.dp))
    Text(
        "identified in ${settled.telemetry.totalMs}ms",
        fontFamily = Mono, fontSize = 10.sp, color = c.textFaint, modifier = Modifier.fillMaxWidth(),
        textAlign = TextAlign.Center,
    )
}

/** "You already own this" — with a ×N count once the owned copy count is loaded. */
@Composable
private fun OwnedCallout(quantity: Int?) {
    val c = ArcanaTheme.colors
    val label = if ((quantity ?: 1) > 1) "You already own ×$quantity of these" else "You already own this"
    Row(
        Modifier.fillMaxWidth().clip(RoundedCornerShape(12.dp)).background(c.irisSoft).padding(14.dp),
        verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        Text("◆", color = c.iris, fontSize = 13.sp)
        Text(label, color = c.iris, fontSize = 14.sp, fontWeight = FontWeight.SemiBold)
    }
}

/** Barcode fallback — a tertiary link when confident, promoted to a button when the read is uncertain. */
@Composable
private fun BarcodeAffordance(promoted: Boolean, onRescan: () -> Unit) {
    val c = ArcanaTheme.colors
    if (promoted) {
        GhostButton("Scan barcode instead", onClick = onRescan, accent = true)
    } else {
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.Center) {
            Text("Wrong? ", color = c.textDim, fontSize = 13.sp)
            Text(
                "Scan barcode", color = c.iris, fontSize = 13.sp, fontWeight = FontWeight.SemiBold,
                modifier = Modifier.clickable(onClick = onRescan),
            )
        }
    }
}

/** Nothing resolved — the honest no-match state; the barcode is the surest way in. */
@Composable
private fun UnresolvedBlock(onRescan: () -> Unit) {
    val c = ArcanaTheme.colors
    Text("Not sure what this is", color = c.text, fontSize = 22.sp, fontWeight = FontWeight.Bold)
    Spacer(Modifier.height(6.dp))
    Text(
        "The cascade couldn't settle on a confident match. A barcode is the surest way in.",
        color = c.textDim, fontSize = 14.sp, lineHeight = 20.sp,
    )
    Spacer(Modifier.height(16.dp))
    GhostButton("Scan barcode", onClick = onRescan, accent = true)
}

@Composable
private fun ConfidenceBar(confidence: Float) {
    val c = ArcanaTheme.colors
    val width by animateFloatAsState(confidence.coerceIn(0f, 1f), tween(500), label = "conf")
    Box(Modifier.fillMaxWidth().height(6.dp).clip(RoundedCornerShape(3.dp)).background(c.hairlineStrong)) {
        Box(Modifier.fillMaxWidth(width).height(6.dp).clip(RoundedCornerShape(3.dp)).background(c.iris))
    }
}

/**
 * Gemini Nano's on-device **visual description** of the figure — appearance only ("a masked figure in a red
 * suit holding a can of spinach"). Nano's identity *labels* are unreliable (it swaps character and
 * franchise), and since the description no longer feeds identification, this surfaces what it genuinely
 * sees rather than what it would guess — the honest form of the design's "the AI is describing the item"
 * beat. Best-effort: on a refusal it simply never appears.
 */
@Composable
private fun NanoDescription(text: String) {
    val c = ArcanaTheme.colors
    Row(
        Modifier.fillMaxWidth().clip(RoundedCornerShape(14.dp)).background(c.surface)
            .border(1.dp, c.hairline, RoundedCornerShape(14.dp)).padding(13.dp),
        verticalAlignment = Alignment.Top, horizontalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        Text("◊", color = c.iris, fontSize = 12.sp)
        Column {
            Text("SEEN ON-DEVICE", fontFamily = Mono, fontSize = 9.sp, color = c.textFaint, letterSpacing = 0.8.sp)
            Spacer(Modifier.height(3.dp))
            Text(text, fontSize = 13.sp, color = c.textDim, lineHeight = 18.sp)
        }
    }
}

@Composable
private fun FailureBlock(message: String, onRescan: () -> Unit) {
    val c = ArcanaTheme.colors
    Text(message, color = c.down, fontSize = 16.sp, fontWeight = FontWeight.SemiBold)
    Spacer(Modifier.height(6.dp))
    Text("Try another angle, or use the barcode fallback.", color = c.textDim, fontSize = 14.sp)
    Spacer(Modifier.height(16.dp))
    GhostButton("Scan barcode", onClick = onRescan, accent = true)
}

/**
 * A captured pop carries no list, but Portfolio groups by it — so save must ask which list to add it to.
 * Pick an existing list or type a new one; the best-matching existing list is flagged as suggested.
 */
@Composable
private fun ListPickerDialog(
    lists: List<String>,
    suggested: String?,
    onDismiss: () -> Unit,
    onPick: (String) -> Unit,
) {
    val c = ArcanaTheme.colors
    var newList by remember { mutableStateOf("") }
    AlertDialog(
        onDismissRequest = onDismiss,
        containerColor = c.surface,
        title = { Text("Save to which list?", color = c.text, fontWeight = FontWeight.Bold) },
        text = {
            Column(Modifier.fillMaxWidth().heightIn(max = 340.dp).verticalScroll(rememberScrollState())) {
                lists.forEach { name ->
                    val isSuggested = name == suggested
                    Text(
                        if (isSuggested) "$name  · suggested" else name,
                        color = if (isSuggested) c.iris else c.text, fontSize = 15.sp,
                        fontWeight = if (isSuggested) FontWeight.SemiBold else FontWeight.Normal,
                        modifier = Modifier.fillMaxWidth().clickable { onPick(name) }.padding(vertical = 12.dp),
                    )
                    HorizontalDivider(color = c.hairline)
                }
                Spacer(Modifier.height(12.dp))
                OutlinedTextField(
                    value = newList, onValueChange = { newList = it },
                    label = { Text("Or create a new list") }, singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                )
            }
        },
        confirmButton = {
            TextButton(onClick = { onPick(newList) }, enabled = newList.isNotBlank()) {
                Text("Create & save", color = if (newList.isNotBlank()) c.iris else c.textFaint)
            }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel", color = c.textDim) } },
    )
}

/** A sensible default: the existing list whose name shares a token with the pop's series/exclusive. */
private fun suggestedList(s: CaptureReviewUiState): String? {
    val entry = s.settled?.entry ?: return null
    fun tokens(str: String) = str.lowercase().split(Regex("[^a-z0-9]+")).filter { it.length >= 3 }.toSet()
    val hints = (entry.series + listOfNotNull(entry.exclusiveTo)).flatMap(::tokens).toSet()
    return s.availableLists.firstOrNull { list -> tokens(list).any { it in hints } }
}

/** The current in-flight phase for the top-left chip, or null once settled/failed. */
private fun phaseLabel(s: CaptureReviewUiState): String? = when {
    !s.running -> null
    s.barcodePath -> "Decoding barcode…"
    s.popNumber == null -> "Reading box…"
    !s.subjectReady -> "Segmenting subject…"
    else -> "Matching…"
}
