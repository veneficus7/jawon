package com.jawon.app.ui.common

import androidx.compose.runtime.compositionLocalOf

/**
 * True while the opening card stands whole over the stage, and the room under it need not draw.
 *
 * Not `covered`: a covered room is out of reach — its Back, its hints and its sensors all stand
 * down — where a parked one is only resting its renderer for the two or three seconds a black card
 * hides it, and is drawing again before the card opens (`IntroOverlay`). A lit room drawn behind
 * an opaque card is heat for nothing, and it was taking frames from the card.
 */
val LocalRoomParked = compositionLocalOf { false }

/**
 * True while the Rooms card is open over the stage. Every room's Back stands down for it, so the
 * card's own Back always closes the card. Before (to 0.8), Back went to whichever handler was set
 * up last, and a room built for the first time behind the open card set its own up after the
 * card's — Back then left the room for the showroom and the card stayed up over it. Keying the
 * card's handler on the room (0.7.2) could not win that race; standing the rooms down cannot lose it.
 */
val LocalRoomsCardOpen = compositionLocalOf { false }
