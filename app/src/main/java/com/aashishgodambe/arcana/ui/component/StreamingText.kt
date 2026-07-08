package com.aashishgodambe.arcana.ui.component

import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.sp
import androidx.compose.material3.Text
import com.aashishgodambe.arcana.ui.theme.ArcanaTheme

/**
 * Renders [text] with a blinking caret while [streaming]. The caret is inline in the same
 * [Text] as the answer, so promotion from streaming to final text is a caret removal — no layout
 * reflow, no recomposition flicker. Same component the capture cascade will use for Nano's guess.
 */
@Composable
fun StreamingText(
    text: String,
    streaming: Boolean,
    modifier: Modifier = Modifier,
    color: Color = ArcanaTheme.colors.text,
) {
    val caretAlpha by if (streaming) {
        val transition = rememberInfiniteTransition(label = "caret")
        transition.animateFloat(
            initialValue = 1f,
            targetValue = 0f,
            animationSpec = infiniteRepeatable(tween(600), RepeatMode.Reverse),
            label = "caretAlpha",
        )
    } else {
        androidx.compose.runtime.rememberUpdatedState(0f)
    }

    val iris = ArcanaTheme.colors.iris
    Text(
        text = buildAnnotatedString {
            append(text)
            if (streaming) {
                withStyle(SpanStyle(color = iris.copy(alpha = caretAlpha))) { append(" ▌") }
            }
        },
        modifier = modifier,
        color = color,
        fontSize = 14.sp,
        lineHeight = 22.sp,
    )
}
