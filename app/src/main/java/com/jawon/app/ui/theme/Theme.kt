package com.jawon.app.ui.theme

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.remember

// Midnight Shop is the only room (ENGINEERING.md locked decision) and dynamic
// color is a DESIGN.md anti-goal, so this is a single fixed dark scheme —
// no light variant, no dynamicDarkColorScheme/dynamicLightColorScheme.
//
// The one thing that moves is the accent, which follows the room's colour scheme so the
// 2D screens and the room agree about what colour the app is. Everything else — the dark
// ground, the cream lettering — stays put in every scheme: those are what make the app
// legible, and recolouring them would be retheming rather than changing a bulb.
private fun schemeFor(accent: androidx.compose.ui.graphics.Color) = darkColorScheme(
    primary = accent,
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

/**
 * @param roomScheme where you are standing — the lamp, the wall, the boards.
 * @param deckStyle what you are playing the disc on. Independent of the room on purpose: the
 *   two multiply rather than compete, and a brushed aluminium component belongs in a midnight
 *   shop just as happily as under a cold striplight.
 */
@Composable
fun JawonTheme(
    roomScheme: RoomScheme = RoomScheme.MIDNIGHT_SHOP,
    deckStyle: DeckStyle = DeckStyle.SHOP,
    content: @Composable () -> Unit
) {
    // Resolved here rather than stored: a plate takes part of its colour from the lamp that is
    // lighting it, so the finish cannot be known until the room is.
    val finish = remember(deckStyle, roomScheme) { DeckFinish.of(deckStyle, roomScheme) }
    CompositionLocalProvider(LocalDeckFinish provides finish) {
        MaterialTheme(
            colorScheme = schemeFor(roomScheme.accent),
            typography = Typography,
            content = content
        )
    }
}
