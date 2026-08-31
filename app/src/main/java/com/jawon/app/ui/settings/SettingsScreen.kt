package com.jawon.app.ui.settings

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.provider.Settings
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
import androidx.core.content.FileProvider
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.jawon.app.R
import com.jawon.app.data.playback.EqBand
import com.jawon.app.ui.common.EqualizerPanel
import com.jawon.app.data.update.AvailableUpdate
import com.jawon.app.data.update.UpdateState
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
 * Names a group of settings, with a rule running out to the right of the label.
 *
 * This screen grew a section at a time and ended up a flat list of unrelated controls
 * separated by seven identical centred dividers — which marked *that something changed*
 * without ever saying *what*. A named rule is the same amount of ink doing a useful job, and
 * it is the shape a printed form uses for the same reason.
 */
@Composable
private fun SectionHeader(label: String) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(top = 36.dp, bottom = 16.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        Text(
            text = label,
            fontFamily = JetBrainsMonoFamily,
            fontWeight = FontWeight.Bold,
            fontSize = 10.sp,
            letterSpacing = 3.sp,
            color = LampAmber
        )
        Box(
            Modifier
                .weight(1f)
                .height(1.dp)
                .background(CrateEdge)
        )
    }
}

/**
 * Hands the downloaded APK to the platform's installer.
 *
 * `ACTION_VIEW` on a content URI with the APK mime type, plus a read grant for whoever picks
 * it up — the installer is a different process and cannot open this app's cache directly.
 * The prompt the user then sees is the system's own; nothing here can install anything
 * quietly, by design and by the platform's rules.
 */
private fun installUpdate(context: Context, apk: java.io.File) {
    val uri = FileProvider.getUriForFile(context, "${context.packageName}.updates", apk)
    val intent = Intent(Intent.ACTION_VIEW).apply {
        setDataAndType(uri, "application/vnd.android.package-archive")
        addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
    }
    runCatching { context.startActivity(intent) }
}

/** The "install unknown apps" switch for this app, which lives in system settings. */
private fun openInstallPermission(context: Context) {
    val intent = Intent(
        Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES,
        Uri.parse("package:${context.packageName}")
    ).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
    runCatching { context.startActivity(intent) }
}

/**
 * Whether there is a newer Jawon, and the way to get it.
 *
 * Sits directly under the version stamp because that is the question it answers. Nothing here
 * happens on its own: the check is a button, the download is a button, and the install is the
 * platform's own prompt. An app that silently reaches out and replaces itself is not something
 * to build without being asked to, even when it is allowed to.
 */
@Composable
private fun UpdateSection(
    state: UpdateState,
    canInstall: Boolean,
    onCheck: () -> Unit,
    onDownload: (AvailableUpdate) -> Unit,
    onInstall: (java.io.File) -> Unit,
    onAllowInstalls: () -> Unit
) {
    Column(
        modifier = Modifier.fillMaxWidth(),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(10.dp)
    ) {
        when (state) {
            is UpdateState.Idle, is UpdateState.UpToDate, is UpdateState.Failed -> {
                TextChip(text = "CHECK FOR UPDATES", onClick = onCheck)
                val note = when (state) {
                    is UpdateState.UpToDate -> "This is the newest version."
                    is UpdateState.Failed -> state.reason
                    else -> null
                }
                note?.let {
                    Text(
                        it,
                        style = JawonTextStyle.mono.copy(color = LabelDim, fontSize = 10.sp),
                        textAlign = TextAlign.Center
                    )
                }
            }

            is UpdateState.Checking -> Text(
                "CHECKING…",
                style = JawonTextStyle.mono.copy(color = LabelDim, fontSize = 10.sp)
            )

            is UpdateState.Available -> {
                Text(
                    "VERSION ${state.update.versionName.uppercase()} IS AVAILABLE",
                    style = JawonTextStyle.mono.copy(fontSize = 10.sp),
                    textAlign = TextAlign.Center
                )
                if (state.update.notes.isNotBlank()) {
                    Text(
                        state.update.notes,
                        style = JawonTextStyle.mono.copy(color = LabelDim, fontSize = 10.sp),
                        lineHeight = 16.sp,
                        textAlign = TextAlign.Center
                    )
                }
                if (canInstall) {
                    TextChip(text = "DOWNLOAD", onClick = { onDownload(state.update) })
                } else {
                    // Android will not let an app install another without this, and the
                    // switch is in system settings rather than anywhere Jawon can reach.
                    // Saying so beats a download that ends in a refusal.
                    Text(
                        "Android needs your permission for Jawon to install its own " +
                            "updates. This is the only thing it is used for.",
                        style = JawonTextStyle.mono.copy(color = LabelDim, fontSize = 10.sp),
                        lineHeight = 16.sp,
                        textAlign = TextAlign.Center
                    )
                    TextChip(text = "ALLOW, THEN COME BACK", onClick = onAllowInstalls)
                }
            }

            is UpdateState.Downloading -> Text(
                "DOWNLOADING ${(state.fraction * 100).toInt()}%",
                style = JawonTextStyle.mono.copy(fontSize = 10.sp)
            )

            is UpdateState.Ready -> {
                Text(
                    "READY TO INSTALL",
                    style = JawonTextStyle.mono.copy(fontSize = 10.sp)
                )
                TextChip(text = "INSTALL", onClick = { onInstall(state.apk) })
            }
        }
    }
}

/** A bordered mono label that acts as a button. The house's only button shape. */
@Composable
private fun TextChip(text: String, onClick: () -> Unit) {
    Row(
        modifier = Modifier
            .border(1.dp, CrateEdge)
            .clickable(onClick = onClick)
            .padding(horizontal = 16.dp, vertical = 9.dp)
    ) {
        Text(
            text,
            fontFamily = JetBrainsMonoFamily,
            fontWeight = FontWeight.Bold,
            fontSize = 11.sp,
            letterSpacing = 1.6.sp,
            color = LampAmber
        )
    }
}

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
        // No label of its own: the section rule above already says THE ROOM, and this is the
        // only thing under it. Two headings stacked is how the old flat list read.
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
            EqualizerPanel(
                bands = bands,
                rangeMb = rangeMb,
                presets = presets,
                onBandChange = onBandChange,
                onPreset = onPreset,
                onReset = onReset
            )
        }
    }
}

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
    val updateState by viewModel.updateState.collectAsStateWithLifecycle()
    val caseSpin by viewModel.caseSpin.collectAsStateWithLifecycle()
    // Not remembered: the user can leave to grant the permission and come straight back,
    // and a cached "no" would leave them looking at a button that says to do what they just did.
    val canInstall = viewModel.canRequestInstall()
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
                .padding(horizontal = 24.dp)
        ) {
            // The title card. The one part that stays centred, because it is a masthead
            // rather than a setting — everything below it is a control and reads left.
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(top = 40.dp),
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

            SectionHeader("THIS BUILD")

            UpdateSection(
                state = updateState,
                canInstall = canInstall,
                onCheck = viewModel::onCheckForUpdates,
                onDownload = viewModel::onDownloadUpdate,
                onInstall = { apk -> installUpdate(context, apk) },
                onAllowInstalls = { openInstallPermission(context) }
            )

            SectionHeader("THE ROOM")

            SchemePicker(
                current = roomScheme,
                onPick = viewModel::onRoomSchemeChanged
            )

            Box(Modifier.height(28.dp))

            Column(
                modifier = Modifier.fillMaxWidth(),
                verticalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween
                ) {
                    Text(
                        text = "CASES TURN",
                        fontFamily = JetBrainsMonoFamily,
                        fontWeight = FontWeight.Bold,
                        fontSize = 11.sp,
                        letterSpacing = 2.sp,
                        color = LabelCream
                    )
                    Text(
                        text = when {
                            caseSpin <= 0f -> "OFF"
                            caseSpin >= 1f -> "FULL"
                            else -> "%.0f%%".format(caseSpin * 100f)
                        },
                        style = JawonTextStyle.mono.copy(fontSize = 10.sp)
                    )
                }
                Text(
                    text = "Shelved cases rotate slowly to show their covers, then their " +
                        "spines. Full speed is what the room was tuned at; nothing stops " +
                        "them holding square on. They float either way.",
                    style = JawonTextStyle.mono.copy(color = LabelDim, fontSize = 10.sp),
                    lineHeight = 16.sp
                )
                Slider(
                    value = caseSpin,
                    onValueChange = viewModel::onCaseSpinChanged,
                    valueRange = 0f..1f,
                    colors = SliderDefaults.colors(
                        thumbColor = LampAmber,
                        activeTrackColor = LampAmber.copy(alpha = 0.7f),
                        inactiveTrackColor = CrateEdge
                    )
                )
            }

            SectionHeader("SOUND")

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

            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(top = 26.dp),
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

            SectionHeader("YOUR LIBRARY")

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

            Text(
                text = "Everything Jawon knows lives on this phone.",
                style = JawonTextStyle.mono.copy(color = LabelDim, fontSize = 10.sp),
                modifier = Modifier.padding(top = 48.dp, bottom = 48.dp)
            )
        }
    }
}
