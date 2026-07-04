package com.aashishgodambe.arcana.ui.theme

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Shapes
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp

val ArcanaShapes = Shapes(
    extraSmall = RoundedCornerShape(8.dp),
    small = RoundedCornerShape(12.dp),
    medium = RoundedCornerShape(16.dp),
    large = RoundedCornerShape(20.dp),
    extraLarge = RoundedCornerShape(28.dp),
)

/** Convenient access to the Arcana palette inside composables: `ArcanaTheme.colors`. */
object ArcanaTheme {
    val colors: ArcanaColors
        @Composable get() = LocalArcanaColors.current
}

@Composable
fun ArcanaTheme(
    darkTheme: Boolean = isSystemInDarkTheme(), // both palettes designed; follows the system setting
    content: @Composable () -> Unit,
) {
    val colors = if (darkTheme) ArcanaDarkColors else ArcanaLightColors
    val scheme = if (darkTheme) {
        darkColorScheme(
            primary = colors.iris, onPrimary = Color.White,
            background = colors.bg, onBackground = colors.text,
            surface = colors.surface, onSurface = colors.text,
            surfaceVariant = colors.elevated, onSurfaceVariant = colors.textDim,
            outline = colors.hairline, outlineVariant = colors.hairline,
            error = colors.down, onError = Color.White,
        )
    } else {
        lightColorScheme(
            primary = colors.iris, onPrimary = Color.White,
            background = colors.bg, onBackground = colors.text,
            surface = colors.surface, onSurface = colors.text,
            surfaceVariant = colors.elevated, onSurfaceVariant = colors.textDim,
            outline = colors.hairline, outlineVariant = colors.hairline,
            error = colors.down, onError = Color.White,
        )
    }
    CompositionLocalProvider(LocalArcanaColors provides colors) {
        MaterialTheme(colorScheme = scheme, typography = ArcanaTypography, shapes = ArcanaShapes, content = content)
    }
}
