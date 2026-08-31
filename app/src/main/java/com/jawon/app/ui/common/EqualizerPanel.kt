package com.jawon.app.ui.common

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Slider
import androidx.compose.material3.SliderDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.jawon.app.data.playback.EqBand
import com.jawon.app.ui.theme.CrateBlack
import com.jawon.app.ui.theme.CrateEdge
import com.jawon.app.ui.theme.JawonTextStyle
import com.jawon.app.ui.theme.JetBrainsMonoFamily
import com.jawon.app.ui.theme.LabelCream
import com.jawon.app.ui.theme.LabelDim
import com.jawon.app.ui.theme.LampAmber

/**
 * The equaliser's bands, presets and reset — the part that is identical wherever it appears.
 *
 * Shared because there are now two places that need it, and they need the *same* one. Settings
 * has it because that is where settings live; the deck has it because an equaliser you can
 * only reach by closing the record you are listening to is an equaliser you cannot use — you
 * adjust it by ear, and the ear needs the music playing.
 *
 * Deliberately no enable switch here: turning the equaliser on is a settings decision, and a
 * panel that appears only when it is already on has nothing to toggle.
 */
@Composable
fun EqualizerPanel(
    bands: List<EqBand>,
    rangeMb: Pair<Int, Int>,
    presets: List<String>,
    onBandChange: (Int, Int) -> Unit,
    onPreset: (Int) -> Unit,
    onReset: () -> Unit,
    modifier: Modifier = Modifier,
    compact: Boolean = false
) {
    var presetsOpen by remember { mutableStateOf(false) }
    val (minMb, maxMb) = rangeMb

    Column(
        modifier = modifier.fillMaxWidth(),
        verticalArrangement = Arrangement.spacedBy(if (compact) 2.dp else 8.dp)
    ) {
        // Always shown: FLAT is available even on a device that offers no presets.
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(10.dp)
        ) {
            if (presets.isNotEmpty()) {
                Box {
                    Chip("PRESETS") { presetsOpen = true }
                    DropdownMenu(
                        expanded = presetsOpen,
                        onDismissRequest = { presetsOpen = false },
                        containerColor = CrateBlack,
                        modifier = Modifier.border(1.dp, CrateEdge)
                    ) {
                        presets.forEachIndexed { index, name ->
                            DropdownMenuItem(
                                text = {
                                    Text(
                                        name.uppercase(),
                                        style = JawonTextStyle.mono.copy(color = LabelCream)
                                    )
                                },
                                onClick = {
                                    onPreset(index)
                                    presetsOpen = false
                                }
                            )
                        }
                    }
                }
            }
            Chip("FLAT", onClick = onReset)
        }

        bands.forEach { band ->
            Column(verticalArrangement = Arrangement.spacedBy(0.dp)) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween
                ) {
                    Text(
                        text = formatHz(band.centerHz),
                        style = JawonTextStyle.mono.copy(color = LabelDim, fontSize = 10.sp)
                    )
                    // Signed, in whole dB. Millibels are the platform's unit, not one anyone
                    // reading a slider thinks in.
                    Text(
                        text = "%+.1f dB".format(band.levelMb / 100f),
                        style = JawonTextStyle.mono.copy(fontSize = 10.sp)
                    )
                }
                Slider(
                    value = band.levelMb.toFloat(),
                    onValueChange = { onBandChange(band.index, it.toInt()) },
                    valueRange = minMb.toFloat()..maxMb.toFloat(),
                    colors = SliderDefaults.colors(
                        thumbColor = LampAmber,
                        activeTrackColor = LampAmber.copy(alpha = 0.7f),
                        inactiveTrackColor = CrateEdge
                    )
                )
            }
        }
    }
}

@Composable
private fun Chip(label: String, onClick: () -> Unit) {
    Row(
        modifier = Modifier
            .border(1.dp, CrateEdge)
            .background(CrateBlack.copy(alpha = 0.4f))
            .clickable(onClick = onClick)
            .padding(horizontal = 12.dp, vertical = 7.dp)
    ) {
        Text(
            label,
            fontFamily = JetBrainsMonoFamily,
            fontWeight = FontWeight.Bold,
            fontSize = 10.sp,
            letterSpacing = 1.4.sp,
            color = LampAmber
        )
    }
}

/** 1000 Hz reads worse than 1 kHz on a control this small. */
private fun formatHz(hz: Int): String =
    if (hz >= 1000) "${(hz / 100) / 10f} kHz".replace(".0 ", " ") else "$hz Hz"
