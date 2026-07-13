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
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
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
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.aashishgodambe.arcana.core.ai.cascade.CascadeResult
import com.aashishgodambe.arcana.core.ai.model.InferenceLocation
import com.aashishgodambe.arcana.ui.component.ArcanaChip
import com.aashishgodambe.arcana.ui.component.ChipStyle
import com.aashishgodambe.arcana.ui.component.InferenceBadge
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
    vm: CaptureReviewViewModel = hiltViewModel(),
) {
    val c = ArcanaTheme.colors
    val s by vm.state.collectAsStateWithLifecycle()
    val settled = s.settled

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
                s.failure != null -> FailureBlock(s.failure!!)
                settled != null -> SettledIdentity(settled)
                else -> RunningBeats(s)
            }

            // The late, optional on-device read, reconciled to the authoritative identity. Nano's
            // character/franchise labels swap unreliably (it reads "Popeye" for a Freddy Funko), so we
            // never surface them — only where Nano independently corroborates the OCR/catalog Pop number.
            settled?.entry?.let { entry ->
                val nano = s.description?.let(::nanoNumber)
                if (nano != null && nano == entry.number) {
                    Spacer(Modifier.height(14.dp))
                    NanoCorroboration(nano)
                }
            }

            Spacer(Modifier.height(40.dp))
        }
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
    val c = ArcanaTheme.colors
    Column(verticalArrangement = Arrangement.spacedBy(9.dp)) {
        if (s.popNumber != null) StatusLine(done = true, label = "Box number read", detail = "OCR → #${s.popNumber}")
        if (s.subjectReady) StatusLine(done = true, label = "Subject isolated", detail = "ML Kit segmentation")
        if (s.matching) StatusLine(done = false, label = "Checking your collection…", detail = "local · eBay")
    }
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

/** The settled identity (Day 2: identity + source + confidence + ownership). Market + save land in Day 3–4. */
@Composable
private fun SettledIdentity(settled: CascadeResult) {
    val c = ArcanaTheme.colors
    val entry = settled.entry
    if (entry == null) {
        Text("Not sure what this is", color = c.text, fontSize = 22.sp, fontWeight = FontWeight.Bold)
        Spacer(Modifier.height(6.dp))
        Text(
            "The cascade couldn't settle on a confident match. Scan the barcode or try another angle.",
            color = c.textDim, fontSize = 14.sp, lineHeight = 20.sp,
        )
        return
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
        Row(
            Modifier.fillMaxWidth().clip(RoundedCornerShape(12.dp)).background(c.irisSoft).padding(14.dp),
            verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            Text("◆", color = c.iris, fontSize = 13.sp)
            Text("You already own this", color = c.iris, fontSize = 14.sp, fontWeight = FontWeight.SemiBold)
        }
    }

    Spacer(Modifier.height(10.dp))
    Text("identified in ${settled.telemetry.totalMs}ms", fontFamily = Mono, fontSize = 10.sp, color = c.textFaint)
}

@Composable
private fun ConfidenceBar(confidence: Float) {
    val c = ArcanaTheme.colors
    val width by animateFloatAsState(confidence.coerceIn(0f, 1f), tween(500), label = "conf")
    Box(Modifier.fillMaxWidth().height(6.dp).clip(RoundedCornerShape(3.dp)).background(c.hairlineStrong)) {
        Box(Modifier.fillMaxWidth(width).height(6.dp).clip(RoundedCornerShape(3.dp)).background(c.iris))
    }
}

/** A quiet line showing the on-device model independently corroborated the Pop number — the honest, */
/*  label-free way to surface Nano's multimodal read without asserting its unreliable character/franchise. */
@Composable
private fun NanoCorroboration(number: String) {
    val c = ArcanaTheme.colors
    Row(
        Modifier.fillMaxWidth().clip(RoundedCornerShape(14.dp)).background(c.surface)
            .border(1.dp, c.hairline, RoundedCornerShape(14.dp)).padding(13.dp),
        verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        Text("◊", color = c.iris, fontSize = 12.sp)
        Text(
            "Gemini Nano also read #$number on-device",
            fontFamily = Mono, fontSize = 12.sp, color = c.textDim,
        )
    }
}

/** Nano's structured read is unreliable for labels but its number matches OCR — pull only that out. */
private fun nanoNumber(raw: String): String? =
    Regex("\"number\"\\s*:\\s*\"?(\\d{1,4})").find(raw)?.groupValues?.get(1)

@Composable
private fun FailureBlock(message: String) {
    val c = ArcanaTheme.colors
    Text(message, color = c.down, fontSize = 16.sp, fontWeight = FontWeight.SemiBold)
    Spacer(Modifier.height(6.dp))
    Text("Try another angle, or use the barcode fallback.", color = c.textDim, fontSize = 14.sp)
}

/** The current in-flight phase for the top-left chip, or null once settled/failed. */
private fun phaseLabel(s: CaptureReviewUiState): String? = when {
    !s.running -> null
    s.barcodePath -> "Decoding barcode…"
    s.popNumber == null -> "Reading box…"
    !s.subjectReady -> "Segmenting subject…"
    else -> "Matching…"
}
