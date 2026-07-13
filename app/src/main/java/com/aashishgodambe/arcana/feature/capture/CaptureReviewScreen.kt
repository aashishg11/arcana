package com.aashishgodambe.arcana.feature.capture

import androidx.compose.foundation.Image
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
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.aashishgodambe.arcana.core.ai.model.InferenceLocation
import com.aashishgodambe.arcana.ui.component.ArcanaChip
import com.aashishgodambe.arcana.ui.component.ChipStyle
import com.aashishgodambe.arcana.ui.component.InferenceBadge
import com.aashishgodambe.arcana.ui.theme.ArcanaTheme
import com.aashishgodambe.arcana.ui.theme.Mono

/**
 * Capture Review — Day 1 is a minimal, honest render of the [CaptureReviewViewModel] state that proves the
 * camera→cascade pipeline end to end: the frozen frame, the live cascade status, and the settled identity
 * with its on-device/cloud badge. Day 2 replaces this with the animated hero (segmentation outline, #NNN
 * callout, badge flip); Day 3 adds the low-confidence variant; Day 4 adds save-to-collection.
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
            // App bar: close + the on-device/cloud badge (resolves once settled).
            Row(Modifier.fillMaxWidth().padding(top = 8.dp, bottom = 12.dp), verticalAlignment = Alignment.CenterVertically) {
                Box(
                    Modifier.size(38.dp).clip(CircleShape).background(c.surface)
                        .border(1.dp, c.hairline, CircleShape).clickable(onClick = onClose),
                    contentAlignment = Alignment.Center,
                ) { Text("✕", color = c.textDim, fontSize = 16.sp) }
                Spacer(Modifier.weight(1f))
                if (settled != null) InferenceBadge(settled.telemetry.resolvedOn ?: InferenceLocation.OnDevice)
            }

            // The frozen frame (subject mask once segmentation returns).
            val shown = s.subject ?: s.frame
            if (shown != null) {
                Image(
                    bitmap = shown.asImageBitmap(),
                    contentDescription = "captured item",
                    contentScale = ContentScale.Fit,
                    modifier = Modifier.fillMaxWidth().height(300.dp)
                        .clip(RoundedCornerShape(20.dp)).background(c.surface),
                )
                Spacer(Modifier.height(16.dp))
            }

            when {
                settled != null -> SettledBlock(settled)
                s.failure != null -> Text(s.failure!!, color = c.down, fontSize = 15.sp, fontWeight = FontWeight.Medium)
                else -> RunningBlock(s)
            }

            Spacer(Modifier.height(40.dp))
        }
    }
}

@Composable
private fun RunningBlock(s: CaptureReviewUiState) {
    val c = ArcanaTheme.colors
    s.popNumber?.let {
        ArcanaChip("#$it", ChipStyle.Iris)
        Spacer(Modifier.height(10.dp))
    }
    s.statusLines.forEach { line ->
        Text(line, fontFamily = Mono, fontSize = 12.sp, color = c.textDim, lineHeight = 18.sp)
    }
    s.description?.let {
        Spacer(Modifier.height(10.dp))
        Text(it, fontFamily = Mono, fontSize = 12.sp, color = c.textFaint, lineHeight = 18.sp)
    }
}

@Composable
private fun SettledBlock(settled: com.aashishgodambe.arcana.core.ai.cascade.CascadeResult) {
    val c = ArcanaTheme.colors
    val entry = settled.entry
    if (entry == null) {
        Text("Not sure what this is", color = c.text, fontSize = 20.sp, fontWeight = FontWeight.Bold)
        Spacer(Modifier.height(6.dp))
        Text(
            "The cascade couldn't settle on a confident match. Scan the barcode or try another angle.",
            color = c.textDim, fontSize = 14.sp, lineHeight = 20.sp,
        )
        return
    }

    Text(entry.name, color = c.text, fontSize = 24.sp, fontWeight = FontWeight.ExtraBold)
    Spacer(Modifier.height(8.dp))
    Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
        entry.series.firstOrNull()?.let { ArcanaChip(it) }
        entry.number?.let { ArcanaChip("#$it", ChipStyle.Iris) }
        entry.exclusiveTo?.let { ArcanaChip(it, ChipStyle.Gold) }
    }
    Spacer(Modifier.height(8.dp))
    Text(
        "confidence ${(entry.confidence * 100).toInt()}%",
        fontFamily = Mono, fontSize = 12.sp, color = c.textDim,
    )
    if (settled.owned) {
        Spacer(Modifier.height(12.dp))
        Text("You already own this", color = c.iris, fontSize = 14.sp, fontWeight = FontWeight.SemiBold)
    }
    Spacer(Modifier.height(8.dp))
    Text(
        "identified in ${settled.telemetry.totalMs}ms",
        fontFamily = Mono, fontSize = 10.sp, color = c.textFaint,
    )
}
