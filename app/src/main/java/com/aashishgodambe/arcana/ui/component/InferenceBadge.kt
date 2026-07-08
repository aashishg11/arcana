package com.aashishgodambe.arcana.ui.component

import androidx.compose.animation.animateColorAsState
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.aashishgodambe.arcana.core.ai.model.InferenceLocation
import com.aashishgodambe.arcana.ui.theme.ArcanaTheme
import com.aashishgodambe.arcana.ui.theme.Mono

/**
 * The portfolio money-shot: a dot + label that flips between on-device and cloud per
 * [InferenceMetadata.executedOn][com.aashishgodambe.arcana.core.ai.model.InferenceMetadata]. Shared
 * across "Ask Arcana" and (later) Capture Review. [location] is null before the first answer resolves.
 */
@Composable
fun InferenceBadge(location: InferenceLocation?, modifier: Modifier = Modifier) {
    val c = ArcanaTheme.colors
    val accent: Color
    val bg: Color
    val label: String
    when (location) {
        InferenceLocation.OnDevice -> { accent = c.iris; bg = c.irisSoft; label = "On-device" }
        InferenceLocation.Cloud -> { accent = c.cloud; bg = c.cloudSoft; label = "Cloud" }
        null -> { accent = c.textFaint; bg = Color.Transparent; label = "Ready" }
    }
    // Animate the flip so a reviewer watching a demo *sees* the on-device→cloud fallback happen.
    val dotColor by animateColorAsState(accent, label = "badgeDot")

    Row(
        modifier.clip(CircleShape).background(bg).padding(horizontal = 11.dp, vertical = 5.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        Box(Modifier.size(7.dp).clip(CircleShape).background(dotColor))
        Text(label, color = accent, fontFamily = Mono, fontSize = 11.sp, fontWeight = FontWeight.Medium)
    }
}
