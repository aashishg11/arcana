package com.aashishgodambe.arcana.ui.component

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.aashishgodambe.arcana.ui.theme.ArcanaTheme
import com.aashishgodambe.arcana.ui.theme.Mono
import java.time.Duration
import java.time.Instant

/** Selectable window for a value chart. [days] is the look-back from the newest point. */
enum class ChartRange(val days: Long, val label: String) {
    Days30(30, "30d"),
    Days60(60, "60d"),
    Days90(90, "90d"),
}

/**
 * Keeps the points within [range] of the series' newest point (anchored to the data, not the wall clock,
 * so the latest snapshot is always included). [at] extracts each item's instant.
 */
fun <T> List<T>.withinRange(range: ChartRange, at: (T) -> Instant): List<T> {
    val newest = maxOfOrNull { at(it) } ?: return emptyList()
    val cutoff = newest.minus(Duration.ofDays(range.days))
    return filter { at(it) >= cutoff }.sortedBy { at(it) }
}

/** Compact segmented `30d · 60d · 90d` control — the timeline for a sparkline / value chart. */
@Composable
fun RangeSelector(
    selected: ChartRange,
    onSelect: (ChartRange) -> Unit,
    modifier: Modifier = Modifier,
) {
    val c = ArcanaTheme.colors
    Row(modifier, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
        ChartRange.entries.forEach { range ->
            val on = range == selected
            Text(
                range.label,
                fontFamily = Mono, fontSize = 11.sp, fontWeight = FontWeight.Medium,
                color = if (on) Color.White else c.textDim,
                modifier = Modifier
                    .clip(RoundedCornerShape(8.dp))
                    .then(
                        if (on) Modifier.background(c.iris)
                        else Modifier.border(1.dp, c.hairline, RoundedCornerShape(8.dp)),
                    )
                    .clickable { onSelect(range) }
                    .padding(horizontal = 10.dp, vertical = 5.dp),
            )
        }
    }
}
