package com.example.optimalx.ui.theme

import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.graphics.Color

data class OptimalXColors(
    val background: Color,
    val surface: Color,
    val surface2: Color,
    val border: Color,
    val borderSoft: Color,
    val accent: Color,
    val accentDim: Color,
    val accentBorder: Color,
    val textPrimary: Color,
    val textMid: Color,
    val textDim: Color,
    val badgePdfText: Color,
    val badgePdfBg: Color,
    val badgeDocText: Color,
    val badgeDocBg: Color,
    val badgeImgText: Color,
    val badgeImgBg: Color,
    val sheetBackground: Color,
    val sheetBorder: Color,
    val messageBubbleUser: Color,
    val messageBubbleEidosBorder: Color,
)

val DarkColors = OptimalXColors(
    background = Color(0xFF0E0E0F),
    surface = Color(0xFF161618),
    surface2 = Color(0xFF1E1E21),
    border = Color(0xFF2A2A2E),
    borderSoft = Color(0xFF2A2A2E),
    accent = Color(0xFFC8FB5E),
    accentDim = Color(0x1AC8FB5E),
    accentBorder = Color(0x40C8FB5E),
    textPrimary = Color(0xFFF0F0EE),
    textMid = Color(0xFF9A9A96),
    textDim = Color(0xFF52524E),
    badgePdfText = Color(0xFFFF503C),
    badgePdfBg = Color(0x1FFF503C),
    badgeDocText = Color(0xFF3C78FF),
    badgeDocBg = Color(0x1F3C78FF),
    badgeImgText = Color(0xFFC8FB5E),
    badgeImgBg = Color(0x1AC8FB5E),
    sheetBackground = Color(0xFF121214),
    sheetBorder = Color(0xFF2E2E32),
    messageBubbleUser = Color(0xFF1E1E21),
    messageBubbleEidosBorder = Color(0x2EC8FB5E),
)

val LightColors = OptimalXColors(
    background = Color(0xFFF5F2ED),
    surface = Color(0xFFEDE9E2),
    surface2 = Color(0xFFE4DFD6),
    border = Color(0xFFD4CFC7),
    borderSoft = Color(0xFFE0DBD3),
    accent = Color(0xFF4A6741),
    accentDim = Color(0x1A4A6741),
    accentBorder = Color(0x404A6741),
    textPrimary = Color(0xFF1C1C1A),
    textMid = Color(0xFF6B6860),
    textDim = Color(0xFFA8A49E),
    badgePdfText = Color(0xFFB84030),
    badgePdfBg = Color(0x1AB84030),
    badgeDocText = Color(0xFF3A5AB8),
    badgeDocBg = Color(0x1A3A5AB8),
    badgeImgText = Color(0xFF4A6741),
    badgeImgBg = Color(0x1A4A6741),
    sheetBackground = Color(0xFFF0ECE5),
    sheetBorder = Color(0xFFD4CFC7),
    messageBubbleUser = Color(0xFFE4DFD6),
    messageBubbleEidosBorder = Color(0x264A6741),
)

val LocalOptimalXColors = staticCompositionLocalOf { DarkColors }
