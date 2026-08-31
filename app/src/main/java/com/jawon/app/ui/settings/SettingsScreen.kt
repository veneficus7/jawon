package com.jawon.app.ui.settings

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.Slider
import androidx.compose.material3.SliderDefaults
import androidx.compose.material3.Switch
import androidx.compose.material3.SwitchDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.jawon.app.R
import com.jawon.app.data.playback.EqBand
import com.jawon.app.playback.Crossfade
import com.jawon.app.ui.theme.ArchivoFamily
import com.jawon.app.ui.theme.CrateBlack
import com.jawon.app.ui.theme.CrateEdge
import com.jawon.app.ui.theme.JawonTextStyle
import com.jawon.app.ui.theme.JetBrainsMonoFamily
import com.jawon.app.ui.theme.LabelCream
import com.jawon.app.ui.theme.LabelDim
import com.jawon.app.ui.theme.LampAmber
import com.jawon.app.ui.theme.RoomScheme

/**
 * The room's colour, as three swatches rather than three names in a list.
 *
 * Each one is painted in its own scheme — the board colour behind, the lamp colour as the
 * light on it — so the choice is made by looking at the thing rather than by reading
 * "After Hours" and guessing. That matters more here than in most pickers: the difference
 * between these is entirely visual.
 */
@Composable
private fun SchemePicker(current: RoomScheme, onPick: (RoomScheme) -> Unit) {
    Column(
        modifier = Modifier.fillMaxWidth(),
        verticalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        Text(
            text = "ROOM COLOUR",
            fontFamily = JetBrainsMonoFamily,
            fontWeight = FontWeight.Bold,
            fontSize = 11.sp,
            letterSpacing = 2.sp,
            color = LabelCream
        )
        Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            RoomScheme.entries.forEach { scheme ->
                val selected = scheme == current
                Column(
                    modifier = Modifier
                        .weight(1f)
                        .border(
                            width = if (selected) 2.dp else 1.dp,
                            color = if (selected) scheme.accent else CrateEdge
                        )
                        .clickable { onPick(scheme) }
                        .padding(1.dp),
                    horizontalAlignment = Alignment.CenterHorizontally
                ) {
                    // The board colour, with the lamp's own light pooled on it — a shrunk
                    // version of what the shelf actually looks like in that scheme.
                    Box(
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(56.dp)
                            .background(scheme.board)
                    ) {
                        Box(
                            modifier = Modifier
                                .fillMaxWidth()
                                .height(3.dp)
                                .align(Alignment.BottomCenter)
                                .background(
                                    Brush.horizontalGradient(
                                        0f to Color.Transparent,
                                        0.5f to scheme.accent,
                                        1f to Color.Transparent
                                    )
                                )
                        )
                    }
                    Text(
                        text = scheme.displayName.uppercase(),
                        style = JawonTextStyle.mono.copy(
                            color = if (selected) scheme.accent else LabelDim,
                            fontSize = 9.sp
                        ),
                        textAlign = TextAlign.Center,
                        modifier = Modifier.padding(vertical = 8.dp, horizontal = 2.dp)
                    )
                }
            }
        }
        Text(
            // Said plainly rather than left to be discovered. The lamp and the shelving
            // change as soon as you tap; the room's ambient fill is a cubemap baked before
            // the first frame, and rebuilding it mid-session means destroying Filament
            // resources in the one part of this app where that has repeatedly gone wrong.
            text = "The lamp and shelves change straight away. The room's ambient light " +
                "follows next time you open Jawon.",
            style = JawonTextStyle.mono.copy(color = LabelDim, fontSize = 10.sp),
            lineHeight = 16.sp
        )
    }
}

/**
 * The equaliser: a switch, the platform's own presets, and one slider per band.
 *
 * **Everything about its shape comes from the device.** How many bands there are, where they
 * sit and how far they swing are decided by the platform's effect implementation — five bands
 * is usual and not guaranteed — so this draws whatever it is told about rather than assuming.
 * On a device with no working equaliser it says so plainly instead of showing dead controls.
 */
@Composable
private fun EqualizerSection(
    available: Boolean,
    enabled: Boolean,
    bands: List<EqBand>,
    rangeMb: Pair<Int, Int>,
    presets: List<String>,
    onEnabledChange: (Boolean) -> Unit,
    onBandChange: (Int, Int) -> Unit,
    onPreset: (Int) -> Unit,
    onReset: () -> Unit
) {
    var presetsOpen by remember { mutableStateOf(false) }

    Column(
        modifier = Modifier.fillMaxWidth(),
        verticalArrangement = Arrangement.spacedBy(14.dp)
    ) {
        SettingRow(
            title = "EQUALISER",
            // Three states, not two. "Unavailable" and "nothing has played yet" look the
            // same from here and mean opposite things — the first version said the phone had
            // no equaliser to anyone who opened Settings before playing anything, which was
            // both wrong and unfixable-looking.
            detail = when {
                available ->
                    "Shapes the sound coming out. Off leaves the audio exactly as it was " +
                        "recorded."
                else ->
                    "Play something first — the equaliser attaches to the audio and there " +
                        "is nothing to attach to yet."
            },
            checked = enabled && available,
            onCheckedChange = onEnabledChange,
            enabled = available
        )

        if (available && enabled && bands.isNotEmpty()) {
            val (minMb, maxMb) = rangeMb

            if (presets.isNotEmpty()) {
                Box {
                    Row(
                        modifier = Modifier
                            .border(1.dp, CrateEdge)
                            .clickable { presetsOpen = true }
                            .padding(horizontal = 14.dp, vertical = 9.dp),
                        horizontalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        Text(
                            "PRESETS",
                            style = JawonTextStyle.mono.copy(fontSize = 10.sp)
                        )
                    }
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

            bands.forEach { band ->
                Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween
                    ) {
                        Text(
                            text = formatHz(band.centerHz),
                            style = JawonTextStyle.mono.copy(color = LabelDim, fontSize = 10.sp)
                        )
                        // Signed, and in whole dB. Millibels are the platform's unit, not a
                        // unit anyone reading a slider thinks in.
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

            Row(
                modifier = Modifier
                    .border(1.dp, CrateEdge)
                    .clickable(onClick = onReset)
                    .padding(horizontal = 14.dp, vertical = 9.dp)
            ) {
                Text("FLAT", style = JawonTextStyle.mono.copy(fontSize = 10.sp))
            }
        }
    }
}

/** 1000 Hz reads worse than 1 kHz on a control this small. */
private fun formatHz(hz: Int): String =
    if (hz >= 1000) "${(hz / 100) / 10f} kHz".replace(".0 ", " ") else "$hz Hz"

/** One switchable setting: what it is, what it actually does, and the switch. */
@Composable
private fun SettingRow(
    title: String,
    detail: String,
    checked: Boolean,
    onCheckedChange: (Boolean) -> Unit,
    enabled: Boolean = true
) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(16.dp)
    ) {
        Column(
            modifier = Modifier.weight(1f),
            verticalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            Text(
                text = title,
                fontFamily = JetBrainsMonoFamily,
                fontWeight = FontWeight.Bold,
                fontSize = 11.sp,
                letterSpacing = 2.sp,
                color = LabelCream
            )
            Text(
                text = detail,
                style = JawonTextStyle.mono.copy(color = LabelDim, fontSize = 10.sp),
                lineHeight = 16.sp
            )
        }
        Switch(
            checked = checked,
            onCheckedChange = onCheckedChange,
            enabled = enabled,
            colors = SwitchDefaults.colors(
                checkedThumbColor = CrateBlack,
                checkedTrackColor = LampAmber,
                uncheckedThumbColor = LabelDim,
                uncheckedTrackColor = CrateBlack,
                uncheckedBorderColor = CrateEdge
            )
        )
    }
}

@Composable
fun SettingsScreen(
    onBack: () -> Unit,
    viewModel: SettingsViewModel = hiltViewModel()
) {
    val context = LocalContext.current
    val fetchCoverArt by viewModel.fetchCoverArt.collectAsStateWithLifecycle()
    val roomScheme by viewModel.roomScheme.collectAsStateWithLifecycle()
    val eqAvailable by viewModel.eqAvailable.collectAsStateWithLifecycle()
    val eqEnabled by viewModel.eqEnabled.collectAsStateWithLifecycle()
    val eqBands by viewModel.eqBands.collectAsStateWithLifecycle()
    val eqRangeMb by viewModel.eqRangeMb.collectAsStateWithLifecycle()
    val eqPresets by viewModel.eqPresets.collectAsStateWithLifecycle()
    val crossfadeMs by viewModel.crossfadeMs.collectAsStateWithLifecycle()
    // Read from the package rather than hardcoded, so it can only ever say what was actually
    // installed. `versionName` is set in app/build.gradle.kts.
    val versionName = remember(context) {
        runCatching {
            context.packageManager.getPackageInfo(context.packageName, 0).versionName
        }.getOrNull().orEmpty()
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(CrateBlack)
            .statusBarsPadding()
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(start = 8.dp, end = 8.dp, top = 6.dp, bottom = 10.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            IconButton(onClick = onBack) {
                Icon(
                    Icons.AutoMirrored.Filled.ArrowBack,
                    contentDescription = "Back to the room",
                    tint = LabelDim
                )
            }
            Text(
                text = "SETTINGS",
                fontFamily = JetBrainsMonoFamily,
                fontWeight = FontWeight.Bold,
                fontSize = 12.sp,
                letterSpacing = 3.sp,
                color = LampAmber
            )
        }
        Box(
            Modifier
                .fillMaxWidth()
                .height(1.dp)
                .background(CrateEdge)
        )

        Column(
            modifier = Modifier
                .fillMaxSize()
                .verticalScroll(rememberScrollState())
                .navigationBarsPadding()
                .padding(horizontal = 24.dp),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            Column(
                modifier = Modifier.padding(top = 64.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.spacedBy(14.dp)
            ) {
                Text(
                    text = stringResource(R.string.app_name).uppercase(),
                    fontFamily = ArchivoFamily,
                    fontWeight = FontWeight.ExtraBold,
                    fontSize = 44.sp,
                    letterSpacing = 3.sp,
                    color = LabelCream
                )
                if (versionName.isNotEmpty()) {
                    // Boxed like a stamp on a sleeve rather than set as a line of body text —
                    // on a beta build the version is the single most useful thing on this
                    // screen, because it is what a tester quotes back with a bug report.
                    Box(
                        Modifier
                            .background(LampAmber.copy(alpha = 0.10f))
                            .padding(horizontal = 14.dp, vertical = 6.dp)
                    ) {
                        Text(
                            text = "VERSION ${versionName.uppercase()}",
                            style = JawonTextStyle.mono
                        )
                    }
                }
            }

            Box(
                Modifier
                    .padding(vertical = 40.dp)
                    .width(60.dp)
                    .height(1.dp)
                    .background(CrateEdge)
            )

            SchemePicker(
                current = roomScheme,
                onPick = viewModel::onRoomSchemeChanged
            )

            Box(
                Modifier
                    .padding(vertical = 32.dp)
                    .width(60.dp)
                    .height(1.dp)
                    .background(CrateEdge)
            )

            SettingRow(
                title = "LOOK UP COVER ART",
                // States exactly what leaves the device. A privacy switch that says
                // "improve your experience" is not a switch anyone can make a decision with.
                detail = "When an album's own files carry no artwork, send its title and " +
                    "artist to MusicBrainz to find a cover. Nothing else is sent — no " +
                    "account, no device id, no list of what you own. This is the only " +
                    "thing Jawon sends anywhere.",
                checked = fetchCoverArt,
                onCheckedChange = viewModel::onFetchCoverArtChanged
            )

            Box(
                Modifier
                    .padding(vertical = 32.dp)
                    .width(60.dp)
                    .height(1.dp)
                    .background(CrateEdge)
            )

            EqualizerSection(
                available = eqAvailable,
                enabled = eqEnabled,
                bands = eqBands,
                rangeMb = eqRangeMb,
                presets = eqPresets,
                onEnabledChange = viewModel::onEqEnabledChanged,
                onBandChange = viewModel::onEqBandChanged,
                onPreset = viewModel::onEqPreset,
                onReset = viewModel::onEqReset
            )

            Box(
                Modifier
                    .padding(vertical = 32.dp)
                    .width(60.dp)
                    .height(1.dp)
                    .background(CrateEdge)
            )

            Column(
                modifier = Modifier.fillMaxWidth(),
                verticalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween
                ) {
                    Text(
                        text = "CROSSFADE",
                        fontFamily = JetBrainsMonoFamily,
                        fontWeight = FontWeight.Bold,
                        fontSize = 11.sp,
                        letterSpacing = 2.sp,
                        color = LabelCream
                    )
                    Text(
                        text = if (crossfadeMs < Crossfade.MIN_MS) "OFF"
                        else "%.1f s".format(crossfadeMs / 1000f),
                        style = JawonTextStyle.mono.copy(fontSize = 10.sp)
                    )
                }
                Text(
                    // Says what it does to an album, because that is the part worth knowing.
                    text = "Overlaps the end of one track with the start of the next. Off by " +
                        "default: plenty of records run straight from one track into the " +
                        "next on purpose, and a crossfade undoes that.",
                    style = JawonTextStyle.mono.copy(color = LabelDim, fontSize = 10.sp),
                    lineHeight = 16.sp
                )
                Slider(
                    value = crossfadeMs.toFloat(),
                    onValueChange = { viewModel.onCrossfadeChanged(it.toInt()) },
                    // From zero, so "off" is on the same control rather than a separate
                    // switch — the slider's left end already means what a switch would say.
                    valueRange = 0f..Crossfade.MAX_MS.toFloat(),
                    colors = SliderDefaults.colors(
                        thumbColor = LampAmber,
                        activeTrackColor = LampAmber.copy(alpha = 0.7f),
                        inactiveTrackColor = CrateEdge
                    )
                )
            }

            Box(
                Modifier
                    .padding(vertical = 32.dp)
                    .width(60.dp)
                    .height(1.dp)
                    .background(CrateEdge)
            )

            Text(
                text = "More to come as it is built.",
                style = JawonTextStyle.mono.copy(color = LabelDim),
                lineHeight = 19.sp,
                modifier = Modifier.padding(bottom = 48.dp)
            )
        }
    }
}
