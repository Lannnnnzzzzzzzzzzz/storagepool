package com.example.ui.theme

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color

private val CosmicDarkColorScheme = darkColorScheme(
    primary = ElectricCyan,
    onPrimary = Color(0xFF381E72), // Professional Polish Contrast color
    secondary = CyberTeal,
    onSecondary = ObsidianBg,
    tertiary = AmberWarning,
    background = ObsidianBg,
    onBackground = TextPrimary,
    surface = DeepGreySurface,
    onSurface = TextPrimary,
    surfaceVariant = CardGreySurface,
    onSurfaceVariant = TextPrimary,
    outline = BorderDark,
    error = NeonPink
)

@Composable
fun MyApplicationTheme(
    content: @Composable () -> Unit
) {
    // Force Cosmic Dark to fulfill the "Minimalist Dark Mode" system constraint
    MaterialTheme(
        colorScheme = CosmicDarkColorScheme,
        typography = Typography,
        content = content
    )
}
