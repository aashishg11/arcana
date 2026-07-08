package com.aashishgodambe.arcana.ui.theme

import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.graphics.Color

/** Arcana palette beyond the M3 scheme (gold/up/down, tiered surfaces, dim/faint text). */
data class ArcanaColors(
    val iris: Color,
    val irisSoft: Color,
    val gold: Color,
    val goldSoft: Color,
    val up: Color,
    val down: Color,
    val cloud: Color,
    val cloudSoft: Color,
    val bg: Color,
    val sheet: Color,
    val surface: Color,
    val elevated: Color,
    val hairline: Color,
    val hairlineStrong: Color,
    val text: Color,
    val textDim: Color,
    val textFaint: Color,
    val isDark: Boolean,
)

val ArcanaDarkColors = ArcanaColors(
    iris = Color(0xFF7B6CF6),
    irisSoft = Color(0x297B6CF6),
    gold = Color(0xFFD9A94A),
    goldSoft = Color(0x29D9A94A),
    up = Color(0xFF46C68A),
    down = Color(0xFFF0726A),
    cloud = Color(0xFF7E8BAE),
    cloudSoft = Color(0x297E8BAE),
    bg = Color(0xFF131019),
    sheet = Color(0xFF08060E), // clearly darker than bg — the Ask sheet is the darkest surface
    surface = Color(0xFF1C1826),
    elevated = Color(0xFF241F30),
    hairline = Color(0x14FFFFFF),
    hairlineStrong = Color(0x24FFFFFF),
    text = Color(0xFFECEAF2),
    textDim = Color(0xFF9A93AD),
    textFaint = Color(0xFF6A6480),
    isDark = true,
)

val ArcanaLightColors = ArcanaColors(
    iris = Color(0xFF5B49E0),
    irisSoft = Color(0x1A5B49E0),
    gold = Color(0xFFD9A94A),
    goldSoft = Color(0x29D9A94A),
    up = Color(0xFF46C68A),
    down = Color(0xFFF0726A),
    cloud = Color(0xFF6F7CA0),
    cloudSoft = Color(0x1F6F7CA0),
    bg = Color(0xFFEFEEF5),
    sheet = Color(0xFFE4E2EC), // a step darker than bg — the Ask sheet is the darkest surface
    surface = Color(0xFFFFFFFF),
    elevated = Color(0xFFFBFAFE),
    hairline = Color(0x141A1626),
    hairlineStrong = Color(0x241A1626),
    text = Color(0xFF1A1626),
    textDim = Color(0xFF5F5878),
    textFaint = Color(0xFF9A93AD),
    isDark = false,
)

val LocalArcanaColors = staticCompositionLocalOf { ArcanaDarkColors }
