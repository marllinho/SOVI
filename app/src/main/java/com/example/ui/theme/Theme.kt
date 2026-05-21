package com.example.ui.theme

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable

private val StudioColorScheme = darkColorScheme(
    primary = CyberCyan,
    secondary = CyberGreen,
    tertiary = NeonPink,
    background = DarkObsidian,
    surface = SlateGraphite,
    surfaceVariant = SlateMedium,
    onBackground = TextBright,
    onSurface = TextBright,
    onPrimary = DarkObsidian,
    error = NeonPink
)

@Composable
fun MyApplicationTheme(
    content: @Composable () -> Unit
) {
    MaterialTheme(
        colorScheme = StudioColorScheme,
        typography = Typography,
        content = content
    )
}
