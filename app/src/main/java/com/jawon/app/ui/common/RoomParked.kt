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
