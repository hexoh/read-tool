package com.readtool.app.ui

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color

// 色板取自 docs/ui-design.md §4.1：不用纯白做底，选中色避开朱砂红（书页本身有朱批印章）
val Bg = Color(0xFFFAF8F4)
val Paper = Color(0xFFF6F1E4)
val Primary = Color(0xFF185FA5)
val PrimaryDim = Color(0xFFE6F1FB)
val Ink = Color(0xFF2C2C2A)
val Ink2 = Color(0xFF5F5E5A)
val Ink3 = Color(0xFF8C8778)
val LineC = Color(0xFFE3DAC6)
val Warn = Color(0xFFBA7517)

private val scheme = lightColorScheme(
    primary = Primary,
    onPrimary = Color.White,
    primaryContainer = PrimaryDim,
    onPrimaryContainer = Primary,
    background = Bg,
    onBackground = Ink,
    surface = Color.White,
    onSurface = Ink,
    surfaceVariant = Paper,
    onSurfaceVariant = Ink2,
    outline = LineC,
    error = Warn,
)

@Composable
fun ReadToolTheme(content: @Composable () -> Unit) {
    MaterialTheme(colorScheme = scheme, content = content)
}
