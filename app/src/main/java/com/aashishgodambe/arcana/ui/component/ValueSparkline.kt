package com.aashishgodambe.arcana.ui.component

import androidx.compose.foundation.Canvas
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.runtime.Composable
import androidx.compose.ui.unit.dp

/**
 * A filled line chart of a value series — the Portfolio sparkline and the Detail value-history chart.
 * Draws a stroked polyline over a soft filled area, matching the wireframe. The caller sizes it via
 * [modifier] (height) and supplies the [lineColor] / [fillColor]; the y-axis auto-scales to the data with
 * a little vertical padding so the line never touches the edges.
 *
 * Renders nothing for fewer than two points — callers show an honest "tracking started" state instead of
 * a misleading flat line on an empty axis.
 */
@Composable
fun ValueSparkline(
    values: List<Int>,
    lineColor: Color,
    fillColor: Color,
    modifier: Modifier = Modifier,
) {
    Canvas(modifier) {
        if (values.size < 2) return@Canvas
        val min = values.min()
        val max = values.max()
        val range = (max - min).coerceAtLeast(1)
        val w = size.width
        val h = size.height
        val padY = h * 0.14f
        val stepX = w / (values.size - 1)

        fun px(i: Int) = stepX * i
        fun py(v: Int) = h - padY - (v - min).toFloat() / range * (h - 2 * padY)

        val line = Path().apply {
            moveTo(0f, py(values.first()))
            for (i in 1 until values.size) lineTo(px(i), py(values[i]))
        }
        val area = Path().apply {
            addPath(line)
            lineTo(w, h)
            lineTo(0f, h)
            close()
        }
        drawPath(area, fillColor)
        drawPath(
            line,
            lineColor,
            style = Stroke(width = 2.5.dp.toPx(), cap = StrokeCap.Round, join = StrokeJoin.Round),
        )
    }
}
