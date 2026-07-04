@file:OptIn(ExperimentalTextApi::class)

package com.aashishgodambe.arcana.ui.theme

import androidx.compose.material3.Typography
import androidx.compose.ui.text.ExperimentalTextApi
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.Font
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontVariation
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.sp
import com.aashishgodambe.arcana.R

// Bundled OFL variable fonts (res/font). Each weight maps to the variable `wght` axis.
private fun vf(resId: Int, weight: FontWeight, w: Int) =
    Font(resId, weight = weight, variationSettings = FontVariation.Settings(FontVariation.weight(w)))

val Display = FontFamily(
    vf(R.font.bricolage_grotesque, FontWeight.SemiBold, 600),
    vf(R.font.bricolage_grotesque, FontWeight.Bold, 700),
    vf(R.font.bricolage_grotesque, FontWeight.ExtraBold, 800),
)

val Body = FontFamily(
    vf(R.font.inter, FontWeight.Normal, 400),
    vf(R.font.inter, FontWeight.Medium, 500),
    vf(R.font.inter, FontWeight.SemiBold, 600),
)

val Mono = FontFamily(
    vf(R.font.jetbrains_mono, FontWeight.Normal, 400),
    vf(R.font.jetbrains_mono, FontWeight.Medium, 500),
    vf(R.font.jetbrains_mono, FontWeight.Bold, 700),
)

val ArcanaTypography = Typography(
    displayLarge = TextStyle(fontFamily = Display, fontWeight = FontWeight.ExtraBold, fontSize = 46.sp, letterSpacing = (-1.2).sp, lineHeight = 48.sp),
    headlineLarge = TextStyle(fontFamily = Display, fontWeight = FontWeight.ExtraBold, fontSize = 34.sp, letterSpacing = (-1).sp, lineHeight = 36.sp),
    headlineMedium = TextStyle(fontFamily = Display, fontWeight = FontWeight.Bold, fontSize = 28.sp, letterSpacing = (-0.5).sp),
    headlineSmall = TextStyle(fontFamily = Display, fontWeight = FontWeight.Bold, fontSize = 23.sp),
    titleLarge = TextStyle(fontFamily = Display, fontWeight = FontWeight.Bold, fontSize = 21.sp),
    titleMedium = TextStyle(fontFamily = Display, fontWeight = FontWeight.SemiBold, fontSize = 17.sp),
    bodyLarge = TextStyle(fontFamily = Body, fontWeight = FontWeight.Normal, fontSize = 15.sp, lineHeight = 22.sp),
    bodyMedium = TextStyle(fontFamily = Body, fontWeight = FontWeight.Normal, fontSize = 14.sp, lineHeight = 20.sp),
    bodySmall = TextStyle(fontFamily = Body, fontWeight = FontWeight.Normal, fontSize = 13.sp, lineHeight = 18.sp),
    labelLarge = TextStyle(fontFamily = Mono, fontWeight = FontWeight.Medium, fontSize = 13.sp),
    labelMedium = TextStyle(fontFamily = Mono, fontWeight = FontWeight.Medium, fontSize = 12.sp),
    labelSmall = TextStyle(fontFamily = Mono, fontWeight = FontWeight.Normal, fontSize = 11.sp),
)
