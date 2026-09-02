package com.jawon.app.ui.theme

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.remember

// Dynamic colour is a DESIGN.md anti-goal, so this is never the phone's wallpaper palette —
// it is whichever mode the owner chose, and all four are dark grounds with no light variant.
//
// **The whole scheme moves now, not only the accent.** It used to be a fixed dark brown with one
// colour swapped in, on the reasoning that the ground and the lettering are what make the app
// legible. That was true and beside the point: a mode is supposed to be a design, and leaving
// every surface the same brown meant choosing one changed the room and not the app.
private fun schemeFor(palette: Palette) = darkColorScheme(
    primary = palette.lampAmber,
    onPrimary = palette.crateBlack,
    secondary = palette.oxideTeal,
    onSecondary = palette.crateBlack,
    background = palette.crateBlack,
    onBackground = palette.labelCream,
    surface = palette.crateRaised,
    onSurface = palette.labelCream,
    surfaceVariant = palette.crateRaised,
    onSurfaceVariant = palette.labelDim,
    outline = palette.crateEdge,
)

/**
 * @param roomScheme where you are standing — the lamp, the wall, the boards.
 *   The deck it contains, and the palette every flat screen uses, come with it — a mode is one
 *   design, not a room plus a set of unrelated decisions.
 */
@Composable
fun JawonTheme(
    roomScheme: RoomScheme = RoomScheme.MIDNIGHT_SHOP,
    content: @Composable () -> Unit
) {
    // The deck belongs to the room rather than being chosen beside it: a plate takes part of its
    // colour from the lamp lighting it, and the window's glow is the same colour the 2D screens
    // use, so one choice settles the whole look.
    val finish = remember(roomScheme) { DeckFinish.of(roomScheme) }
    CompositionLocalProvider(
        LocalPalette provides roomScheme.palette,
        LocalDeckFinish provides finish
    ) {
        MaterialTheme(
            colorScheme = schemeFor(roomScheme.palette),
            typography = Typography,
            content = content
        )
    }
}
