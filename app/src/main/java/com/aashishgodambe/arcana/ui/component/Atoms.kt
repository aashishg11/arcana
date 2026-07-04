package com.aashishgodambe.arcana.ui.component

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.aashishgodambe.arcana.ui.theme.ArcanaTheme
import com.aashishgodambe.arcana.ui.theme.Mono

enum class ChipStyle { Plain, Iris, Gold, Cloud }

@Composable
fun ArcanaChip(text: String, style: ChipStyle = ChipStyle.Plain, modifier: Modifier = Modifier) {
    val c = ArcanaTheme.colors
    val bg: Color
    val fg: Color
    when (style) {
        ChipStyle.Iris -> { bg = c.irisSoft; fg = c.iris }
        ChipStyle.Gold -> { bg = c.goldSoft; fg = c.gold }
        ChipStyle.Cloud -> { bg = c.cloudSoft; fg = c.cloud }
        ChipStyle.Plain -> { bg = Color.Transparent; fg = c.textDim }
    }
    Box(
        modifier
            .clip(CircleShape)
            .background(bg)
            .then(if (style == ChipStyle.Plain) Modifier.border(1.dp, c.hairline, CircleShape) else Modifier)
            .padding(horizontal = 11.dp, vertical = 5.dp),
    ) {
        Text(text, color = fg, fontFamily = Mono, fontSize = 11.sp, fontWeight = FontWeight.Medium)
    }
}

@Composable
fun PillButton(text: String, onClick: () -> Unit, modifier: Modifier = Modifier, enabled: Boolean = true) {
    val c = ArcanaTheme.colors
    Box(
        modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(15.dp))
            .background(if (enabled) c.iris else c.iris.copy(alpha = 0.5f))
            .clickable(enabled = enabled, onClick = onClick)
            .padding(vertical = 15.dp),
        contentAlignment = Alignment.Center,
    ) {
        Text(text, color = Color.White, fontWeight = FontWeight.SemiBold, fontSize = 15.sp)
    }
}

@Composable
fun GhostButton(text: String, onClick: () -> Unit, modifier: Modifier = Modifier, accent: Boolean = false) {
    val c = ArcanaTheme.colors
    Box(
        modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(13.dp))
            .border(1.dp, if (accent) c.iris else c.hairlineStrong, RoundedCornerShape(13.dp))
            .clickable(onClick = onClick)
            .padding(vertical = 13.dp),
        contentAlignment = Alignment.Center,
    ) {
        Text(text, color = if (accent) c.iris else c.textDim, fontWeight = FontWeight.Medium, fontSize = 14.sp)
    }
}

@Composable
fun Hairline(modifier: Modifier = Modifier) {
    Box(modifier.fillMaxWidth().height(1.dp).background(ArcanaTheme.colors.hairline))
}

/** A designed stub for sections whose data lands in a later week (market, value history, AI summary). */
@Composable
fun PlaceholderCard(title: String, hint: String, tag: String? = null, modifier: Modifier = Modifier) {
    val c = ArcanaTheme.colors
    Column(
        modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(18.dp))
            .background(c.surface)
            .border(1.dp, c.hairline, RoundedCornerShape(18.dp))
            .padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(7.dp),
    ) {
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
            Text(title, color = c.textDim, fontSize = 13.sp, fontWeight = FontWeight.Medium)
            tag?.let { Text(it, color = c.textFaint, fontFamily = Mono, fontSize = 10.sp) }
        }
        Text(hint, color = c.textFaint, fontFamily = Mono, fontSize = 11.sp)
    }
}
