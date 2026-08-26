package com.jawon.app.ui.theme

import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.sp

/**
 * DESIGN.md §3's "utility/metadata" role: track durations, year, track
 * numbers, elapsed time, and the quality readout — deliberately not a
 * Material Typography slot (see M4 plan decision #3), so it stays a
 * signature detail rather than styling every button in the app. Callers
 * apply `.uppercase()` to the string themselves.
 */
object JawonTextStyle {
    val mono = TextStyle(
        fontFamily = JetBrainsMonoFamily,
        fontWeight = FontWeight.Medium,
        color = LampAmber,
        fontSize = 11.sp,
        letterSpacing = 1.5.sp
    )
}
