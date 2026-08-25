package com.jawon.app.ui.theme

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable

// Midnight Shop is the only room (ENGINEERING.md locked decision) and dynamic
// color is a DESIGN.md anti-goal, so this is a single fixed dark scheme —
// no light variant, no dynamicDarkColorScheme/dynamicLightColorScheme.
private val JavonColorScheme = darkColorScheme(
    primary = LampAmber,
    onPrimary = CrateBlack,
    secondary = OxideTeal,
    onSecondary = CrateBlack,
    background = CrateBlack,
    onBackground = LabelCream,
    surface = CrateRaised,
    onSurface = LabelCream,
    surfaceVariant = CrateRaised,
    onSurfaceVariant = LabelDim,
    outline = CrateEdge,
)

@Composable
fun JavonTheme(content: @Composable () -> Unit) {
    MaterialTheme(
        colorScheme = JavonColorScheme,
        typography = Typography,
        content = content
    )
}
