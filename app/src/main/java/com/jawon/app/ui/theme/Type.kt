@file:OptIn(ExperimentalTextApi::class)

package com.jawon.app.ui.theme

import androidx.compose.material3.Typography
import androidx.compose.ui.text.ExperimentalTextApi
import androidx.compose.ui.text.font.Font
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontVariation
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.sp
import com.jawon.app.R

// DESIGN.md §3 — three deliberately distinct roles, real variable-font files
// (bundled, not the Google Fonts downloadable-fonts API, which doesn't expose
// variable-axis control).
val ArchivoFamily = FontFamily(
    Font(
        R.font.archivo_variable,
        weight = FontWeight.ExtraBold,
        variationSettings = FontVariation.Settings(
            FontVariation.weight(800),
            FontVariation.width(115f)
        )
    )
)

val InterFamily = FontFamily(
    Font(
        R.font.inter_variable,
        weight = FontWeight.Normal,
        variationSettings = FontVariation.Settings(FontVariation.weight(400))
    ),
    Font(
        R.font.inter_variable,
        weight = FontWeight.Medium,
        variationSettings = FontVariation.Settings(FontVariation.weight(500))
    )
)

val JetBrainsMonoFamily = FontFamily(
    Font(
        R.font.jetbrains_mono_variable,
        weight = FontWeight.Medium,
        variationSettings = FontVariation.Settings(FontVariation.weight(500))
    )
)

// Material's default Typography() already has a real size/weight ramp
// (display 57sp down to label 11sp) — only the font family (and, for
// display, the weight/letter-spacing DESIGN.md calls for) needs overriding.
private val baseline = Typography()

val Typography = baseline.copy(
    displayLarge = baseline.displayLarge.copy(
        fontFamily = ArchivoFamily,
        fontWeight = FontWeight.ExtraBold,
        letterSpacing = 0.5.sp
    ),
    displayMedium = baseline.displayMedium.copy(
        fontFamily = ArchivoFamily,
        fontWeight = FontWeight.ExtraBold,
        letterSpacing = 0.5.sp
    ),
    displaySmall = baseline.displaySmall.copy(
        fontFamily = ArchivoFamily,
        fontWeight = FontWeight.ExtraBold,
        letterSpacing = 0.3.sp
    ),
    headlineLarge = baseline.headlineLarge.copy(fontFamily = InterFamily),
    headlineMedium = baseline.headlineMedium.copy(fontFamily = InterFamily),
    headlineSmall = baseline.headlineSmall.copy(fontFamily = InterFamily),
    titleLarge = baseline.titleLarge.copy(fontFamily = InterFamily),
    titleMedium = baseline.titleMedium.copy(fontFamily = InterFamily),
    titleSmall = baseline.titleSmall.copy(fontFamily = InterFamily),
    bodyLarge = baseline.bodyLarge.copy(fontFamily = InterFamily),
    bodyMedium = baseline.bodyMedium.copy(fontFamily = InterFamily),
    bodySmall = baseline.bodySmall.copy(fontFamily = InterFamily),
    labelLarge = baseline.labelLarge.copy(fontFamily = InterFamily),
    labelMedium = baseline.labelMedium.copy(fontFamily = InterFamily),
    labelSmall = baseline.labelSmall.copy(fontFamily = InterFamily)
)
