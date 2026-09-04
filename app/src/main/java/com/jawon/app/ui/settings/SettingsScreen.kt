package com.jawon.app.ui.settings

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.provider.Settings
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
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
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.Slider
import androidx.compose.material3.SliderDefaults
import androidx.compose.material3.Switch
import androidx.compose.material3.SwitchDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
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
import com.jawon.app.data.update.AvailableUpdate
import com.jawon.app.data.update.UpdateState
import com.jawon.app.playback.Crossfade
import com.jawon.app.playback.ReplayGain
import com.jawon.app.ui.theme.ArchivoFamily
import com.jawon.app.ui.theme.CrateBlack
import com.jawon.app.ui.theme.CrateEdge
import com.jawon.app.ui.theme.JawonTextStyle
import com.jawon.app.ui.theme.JetBrainsMonoFamily
import com.jawon.app.ui.theme.LabelCream
import com.jawon.app.ui.theme.LabelDim
import com.jawon.app.ui.theme.LampAmber
import com.jawon.app.ui.theme.DeckFinish
import com.jawon.app.ui.theme.RoomScheme
import java.util.Locale

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
                    "VERSION ${state.update.versionName.uppercase(Locale.ROOT)} IS AVAILABLE",
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
 * The four looks, each previewed as what it actually is: a lit shelf with a machine under it.
 *
 * **One picker, because they are one decision.** The deck used to be chosen separately, and the
 * result was a cold grey front standing in a warm shop — a machine that did not belong to its own
 * room. A room and the thing you play records on in it are a single design, so the swatch shows
 * both: the board with the lamp pooled on it, and the deck's window glowing underneath.
 *
 * That matters more here than in most pickers, because the difference between these is entirely
 * visual — no amount of naming tells you what "Workshop" looks like.
 */
@Composable
private fun SchemePicker(current: RoomScheme, onPick: (RoomScheme) -> Unit) {
    Column(
        modifier = Modifier.fillMaxWidth(),
        verticalArrangement = Arrangement.spacedBy(10.dp)
    ) {
        RoomScheme.entries.chunked(2).forEach { row ->
            Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                row.forEach { scheme ->
                    val finish = DeckFinish.of(scheme)
                    val selected = scheme == current
                    Column(
                        modifier = Modifier
                            .weight(1f)
                            .border(
                                width = if (selected) 2.dp else 1.dp,
                                color = if (selected) scheme.accent else CrateEdge
                            )
                            .clickable { onPick(scheme) }
                            .padding(1.dp)
                    ) {
                        // The room: the wall, a shelf board, and the lamp's own light pooled
                        // along it — a shrunk version of what you are actually looking at.
                        Box(
                            modifier = Modifier
                                .fillMaxWidth()
                                .height(52.dp)
                                .background(scheme.wall)
                        ) {
                            Box(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .height(10.dp)
                                    .align(Alignment.BottomCenter)
                                    .background(scheme.board)
                            )
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

                        // The deck under it, drawn over the room's own dark so the plate's
                        // translucency reads here too.
                        Box(
                            modifier = Modifier
                                .fillMaxWidth()
                                .height(46.dp)
                                .background(scheme.wall)
                                .background(
                                    Brush.verticalGradient(
                                        listOf(
                                            finish.plate.copy(alpha = finish.plateOpacity),
                                            finish.plateLow.copy(alpha = finish.plateOpacity)
                                        )
                                    )
                                ),
                            contentAlignment = Alignment.Center
                        ) {
                            Box(
                                modifier = Modifier
                                    .fillMaxWidth(0.8f)
                                    .height(26.dp)
                                    .background(finish.window)
                                    .border(1.dp, finish.windowEdge),
                                contentAlignment = Alignment.Center
                            ) {
                                Text(
                                    text = "88:88",
                                    fontFamily = finish.display,
                                    fontSize = finish.displaySize,
                                    color = finish.displayInk
                                )
                            }
                        }

                        Text(
                            text = scheme.displayName.uppercase(Locale.ROOT),
                            style = JawonTextStyle.mono.copy(
                                color = if (selected) scheme.accent else LabelCream,
                                fontSize = 10.sp
                            ),
                            modifier = Modifier.padding(start = 8.dp, top = 7.dp, bottom = 8.dp)
                        )
                    }
                }
            }
        }
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
/**
 * What the room is actually managing on this hardware.
 *
 * **This exists to be photographed.** Three of the first five testers said the room was "not
 * that smooth", every performance figure in the project comes from one Galaxy S25 Ultra, and
 * the quality tiers written in response have never been seen doing anything. Buying those
 * phones costs money; asking the people already holding them for a screenshot costs this.
 *
 * So it is written for someone who did not build the app: the tier is named in words, the
 * frame time is given as fps as well as milliseconds, and the line that actually matters —
 * whether the room stepped itself down — says so in a sentence rather than by two values
 * differing.
 */
@Composable
private fun DiagnosticsSection(
    device: String,
    snapshot: com.jawon.app.ui.wallscene.RoomDiagnostics.Snapshot,
    onReset: () -> Unit
) {
    Column(
        modifier = Modifier.fillMaxWidth(),
        verticalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        Text(
            text = device.uppercase(Locale.ROOT),
            style = JawonTextStyle.mono.copy(color = LabelCream, fontSize = 10.sp),
            lineHeight = 15.sp
        )

        if (!snapshot.hasData) {
            Text(
                text = "Browse the room for a few seconds, then come back — there is not " +
                    "enough here to measure yet.",
                style = JawonTextStyle.mono.copy(color = LabelDim, fontSize = 10.sp),
                lineHeight = 16.sp
            )
            return@Column
        }

        DiagnosticRow("QUALITY", snapshot.currentTier?.name ?: "—")
        DiagnosticRow("CHOSEN AT START", snapshot.detectedTier?.name ?: "—")
        DiagnosticRow("AVERAGE", "${snapshot.averageFps} FPS  ·  %.1f MS".format(snapshot.averageMs))
        DiagnosticRow("WORST FRAME", "%.1f MS".format(Locale.US, snapshot.worstMs))
        DiagnosticRow("SLOW FRAMES", "%.1f%%".format(Locale.US, snapshot.slowPercent))
        // What the room actually rasterised, against what it was offered. See RoomCadence:
        // the room draws at the rate the fastest thing on screen justifies, so a figure well
        // under 100% while nothing is being touched is the saving working.
        DiagnosticRow(
            "DRAWN",
            "${snapshot.drawnFps} FPS  ·  %.0f%%".format(snapshot.drawnPercent)
        )
        // The room's own render target, as a fraction of the screen — Filament moves this on
        // its own to hold a frame time. Sitting at the tier's ceiling means the device is
        // comfortable; below it means it is scaling down to keep up. The 2D interface is
        // unaffected either way; see QualityTier.maxRenderScale.
        DiagnosticRow("ROOM SCALE", "%.0f%%".format(Locale.US, snapshot.renderScale * 100f))
        DiagnosticRow("FRAMES SEEN", snapshot.frames.toString())

        Text(
            text = if (snapshot.stepDowns > 0) {
                "Jawon lowered its own quality ${snapshot.stepDowns} " +
                    "time${if (snapshot.stepDowns == 1) "" else "s"} because frames were " +
                    "arriving late. That is the room protecting itself, and it is worth " +
                    "knowing about."
            } else {
                "The room has held the quality it started at."
            },
            style = JawonTextStyle.mono.copy(
                color = if (snapshot.stepDowns > 0) LampAmber else LabelDim,
                fontSize = 10.sp
            ),
            lineHeight = 16.sp,
            modifier = Modifier.padding(top = 4.dp)
        )

        Box(
            modifier = Modifier
                .padding(top = 6.dp)
                .border(1.dp, CrateEdge)
                .clickable(onClick = onReset)
                .padding(horizontal = 16.dp, vertical = 10.dp)
        ) {
            Text(
                text = "MEASURE AGAIN",
                fontFamily = JetBrainsMonoFamily,
                fontWeight = FontWeight.Bold,
                fontSize = 10.sp,
                letterSpacing = 1.5.sp,
                color = LabelDim
            )
        }
    }
}

/**
 * Hands the crash log to whatever the user wants to send it with.
 *
 * `ACTION_SEND` rather than anything Jawon owns: the app has no server, no account and no
 * analytics, so the only route from a tester's phone to the owner is one the user picks and
 * can see the contents of. Through the same FileProvider the updater uses — `file_paths.xml`
 * exposes `diagnostics/` and nothing else under filesDir.
 */
private fun shareCrashReport(context: Context, uri: android.net.Uri) {
    val intent = Intent(Intent.ACTION_SEND).apply {
        type = "text/plain"
        putExtra(Intent.EXTRA_STREAM, uri)
        putExtra(Intent.EXTRA_SUBJECT, "Jawon crash report")
        addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
    }
    runCatching { context.startActivity(Intent.createChooser(intent, "Send crash report")) }
}

/**
 * What went wrong, and a way to send it.
 *
 * **This section exists because the app has no other way of telling anyone.** No analytics by
 * design, and logcat is unreachable on a tester's phone — see trap 11. Without this, a crash on
 * someone else's device is simply never heard about, which is roughly where the project has
 * been for fifteen-odd installs.
 *
 * The report is shown in full rather than only offered for sending. Asking someone to send a
 * file they cannot read would be a strange thing to do in an app whose entire pitch is that
 * nothing leaves the phone.
 */
@Composable
private fun CrashReportSection(
    report: String?,
    onShare: () -> Unit,
    onClear: () -> Unit
) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(top = 26.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        Text(
            text = "PROBLEMS",
            fontFamily = JetBrainsMonoFamily,
            fontWeight = FontWeight.Bold,
            fontSize = 11.sp,
            letterSpacing = 2.sp,
            color = LabelCream
        )

        if (report == null) {
            // The overwhelmingly common case, and it should read as reassurance rather than as
            // an empty panel that looks broken.
            Text(
                text = "Nothing has gone wrong since you last cleared this.",
                style = JawonTextStyle.mono.copy(color = LabelDim, fontSize = 10.sp),
                lineHeight = 16.sp
            )
            return@Column
        }

        Text(
            text = "Jawon recorded something that went wrong. Sending this helps it get " +
                "fixed — it holds the fault, the version and the phone model, and nothing " +
                "about you or your library.",
            style = JawonTextStyle.mono.copy(color = LampAmber, fontSize = 10.sp),
            lineHeight = 16.sp
        )

        // The log itself, in a fixed-height scroller. Height-capped because a stack trace is
        // taller than a phone and this section must not push the rest of Settings off screen.
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .padding(top = 4.dp)
                .border(1.dp, CrateEdge)
                .height(180.dp)
                .verticalScroll(rememberScrollState())
                .padding(10.dp)
        ) {
            Text(
                text = report,
                style = JawonTextStyle.mono.copy(color = LabelDim, fontSize = 9.sp),
                lineHeight = 13.sp
            )
        }

        Row(
            modifier = Modifier.padding(top = 6.dp),
            horizontalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            TextChip(text = "SEND", onClick = onShare)
            TextChip(text = "CLEAR", onClick = onClear)
        }
    }
}

@Composable
private fun DiagnosticRow(label: String, value: String) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.SpaceBetween
    ) {
        Text(
            text = label,
            fontFamily = JetBrainsMonoFamily,
            fontSize = 10.sp,
            letterSpacing = 1.4.sp,
            color = LabelDim
        )
        Text(
            text = value,
            fontFamily = JetBrainsMonoFamily,
            fontWeight = FontWeight.Bold,
            fontSize = 10.sp,
            letterSpacing = 1.sp,
            color = LabelCream
        )
    }
}

/**
 * Which ReplayGain tags to level by.
 *
 * Three states on one row rather than a switch plus a sub-option, because the middle choice
 * is the default and burying it under a toggle would make the app's own answer the hidden one.
 */
@Composable
private fun ReplayGainSection(
    mode: ReplayGain.Mode,
    onPick: (ReplayGain.Mode) -> Unit
) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(top = 26.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        Text(
            text = "VOLUME LEVELLING",
            fontFamily = JetBrainsMonoFamily,
            fontWeight = FontWeight.Bold,
            fontSize = 11.sp,
            letterSpacing = 2.sp,
            color = LabelCream
        )
        Text(
            text = "Uses the ReplayGain tags already in your files, if they have them, so a " +
                "quiet old master and a loud remaster sit at the same level. By album keeps " +
                "the loud and quiet moments the record was sequenced with; by track flattens " +
                "them so every song is equally loud.",
            style = JawonTextStyle.mono.copy(color = LabelDim, fontSize = 10.sp),
            lineHeight = 16.sp
        )
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            ReplayGain.Mode.entries.forEach { option ->
                val label = when (option) {
                    ReplayGain.Mode.OFF -> "OFF"
                    ReplayGain.Mode.ALBUM -> "BY ALBUM"
                    ReplayGain.Mode.TRACK -> "BY TRACK"
                }
                val selected = option == mode
                Box(
                    modifier = Modifier
                        .border(1.dp, if (selected) LampAmber else CrateEdge)
                        .clickable { onPick(option) }
                        .padding(horizontal = 14.dp, vertical = 9.dp)
                ) {
                    Text(
                        text = label,
                        fontFamily = JetBrainsMonoFamily,
                        fontWeight = FontWeight.Bold,
                        fontSize = 10.sp,
                        letterSpacing = 1.4.sp,
                        color = if (selected) LampAmber else LabelDim
                    )
                }
            }
        }
    }
}

/**
 * Writing the collection out to a file, and reading it back.
 *
 * The copy is careful about one thing in particular. What this saves is the *curation* — the
 * shelves, the order, the corrected titles, the artwork, the listening history — and not the
 * music, which Jawon never had. And a restore onto a different phone brings the shelves back
 * but may not bring the audio, because a content URI belongs to the device that issued it.
 * Someone who finds that out after a factory reset has been misled by a button labelled
 * "restore", so it says so before they need it.
 */
@Composable
private fun BackupSection(
    busy: Boolean,
    message: String?,
    onWrite: () -> Unit,
    onRead: () -> Unit
) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(top = 26.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        Text(
            text = "BACK UP YOUR SHELVES",
            fontFamily = JetBrainsMonoFamily,
            fontWeight = FontWeight.Bold,
            fontSize = 11.sp,
            letterSpacing = 2.sp,
            color = LabelCream
        )
        Text(
            text = "Saves which records are on which shelf, what the shelves are called and " +
                "how they glow, the titles and artwork you fixed, and every hour of " +
                "listening the case wear is drawn from. Your audio files are not copied — " +
                "they are already on your phone and Jawon never touches them.",
            style = JawonTextStyle.mono.copy(color = LabelDim, fontSize = 10.sp),
            lineHeight = 16.sp
        )
        Text(
            text = "Restoring onto the same phone brings everything back. Onto a different " +
                "one, the shelves and artwork come back but the tracks may need importing " +
                "again — Android ties a file's address to the device that issued it.",
            style = JawonTextStyle.mono.copy(color = LabelDim, fontSize = 10.sp),
            lineHeight = 16.sp
        )

        Row(
            modifier = Modifier.padding(top = 4.dp),
            horizontalArrangement = Arrangement.spacedBy(10.dp)
        ) {
            BackupButton(text = "SAVE A COPY", enabled = !busy, onClick = onWrite)
            BackupButton(text = "RESTORE", enabled = !busy, onClick = onRead)
        }

        if (busy) {
            Text(
                text = "WORKING…",
                style = JawonTextStyle.mono.copy(fontSize = 10.sp)
            )
        }
        message?.let {
            Text(
                text = it,
                style = JawonTextStyle.mono.copy(color = LabelCream, fontSize = 10.sp),
                lineHeight = 16.sp
            )
        }
    }
}

@Composable
private fun BackupButton(text: String, enabled: Boolean, onClick: () -> Unit) {
    Box(
        modifier = Modifier
            .border(1.dp, if (enabled) LampAmber.copy(alpha = 0.55f) else CrateEdge)
            .clickable(enabled = enabled, onClick = onClick)
            .padding(horizontal = 18.dp, vertical = 11.dp)
    ) {
        Text(
            text = text,
            fontFamily = JetBrainsMonoFamily,
            fontWeight = FontWeight.Bold,
            fontSize = 10.sp,
            letterSpacing = 1.6.sp,
            color = if (enabled) LampAmber else LabelDim
        )
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
    val fetchLyrics by viewModel.fetchLyrics.collectAsStateWithLifecycle()
    val roomScheme by viewModel.roomScheme.collectAsStateWithLifecycle()
    val crossfadeMs by viewModel.crossfadeMs.collectAsStateWithLifecycle()
    val updateState by viewModel.updateState.collectAsStateWithLifecycle()
    val caseSpin by viewModel.caseSpin.collectAsStateWithLifecycle()
    val replayGainMode by viewModel.replayGainMode.collectAsStateWithLifecycle()
    val backupBusy by viewModel.backupBusy.collectAsStateWithLifecycle()
    val backupMessage by viewModel.backupMessage.collectAsStateWithLifecycle()

    // SAF both ways, for the same reason the folder import uses it: the file belongs to the
    // user, in a place they chose and can find again, not in this app's private storage where
    // an uninstall would take the backup along with the thing it was backing up.
    val writeBackupLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.CreateDocument("application/zip")
    ) { uri -> uri?.let { viewModel.onWriteBackup(context, it) } }

    val readBackupLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.OpenDocument()
    ) { uri -> uri?.let { viewModel.onReadBackup(context, it) } }
    // Read once when the screen opens, and held so "CLEAR" can empty the section without a
    // second trip to disk. A crash cannot happen while this screen is in front of you — the
    // process would be gone — so there is nothing to keep watching for.
    //
    // **In a LaunchedEffect, not in the `remember` initialiser.** Reading it there put a
    // `readText` of up to 32,000 characters on the main thread, on the frame Settings opens —
    // architecture rule 5, which has no exception for "only once" or "only a small file".
    // The section renders its empty state for one frame and then fills in, which is correct:
    // for almost every user the answer really is "nothing to report".
    var crashReport by remember { mutableStateOf<String?>(null) }
    LaunchedEffect(Unit) { crashReport = viewModel.crashReport() }

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
                    text = stringResource(R.string.app_name).uppercase(Locale.ROOT),
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
                            text = "VERSION ${versionName.uppercase(Locale.ROOT)}",
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
                            else -> "%.0f%%".format(Locale.US, caseSpin * 100f)
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
                        else "%.1f s".format(Locale.US, crossfadeMs / 1000f),
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

            ReplayGainSection(
                mode = ReplayGain.Mode.fromName(replayGainMode),
                onPick = viewModel::onReplayGainModeChanged
            )

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

            SettingRow(
                title = "LOOK UP LYRICS",
                // Same rule as above: name the service, name exactly what is sent, and say
                // when. "Enhanced metadata" is not something anyone can decide about.
                detail = "When a track carries no lyrics of its own, send its title, artist, " +
                    "album and length to LRCLIB to look them up. Asked only when you open " +
                    "the lyrics page, and only for tracks that have none — the answer is " +
                    "kept so the same track is never looked up twice. Nothing else is sent.",
                checked = fetchLyrics,
                onCheckedChange = viewModel::onFetchLyricsChanged
            )

            BackupSection(
                busy = backupBusy,
                message = backupMessage,
                onWrite = { writeBackupLauncher.launch(viewModel.suggestedBackupName()) },
                onRead = { readBackupLauncher.launch(arrayOf("application/zip", "*/*")) }
            )

            SectionHeader("HOW IT IS RUNNING")

            DiagnosticsSection(
                device = viewModel.deviceLine(),
                snapshot = viewModel.diagnosticsSnapshot(),
                onReset = viewModel::onResetDiagnostics
            )

            CrashReportSection(
                report = crashReport,
                onShare = {
                    viewModel.crashReportUri()?.let { shareCrashReport(context, it) }
                },
                onClear = {
                    viewModel.onClearCrashReport()
                    crashReport = null
                }
            )

            Text(
                text = "Everything Jawon knows lives on this phone.",
                style = JawonTextStyle.mono.copy(color = LabelDim, fontSize = 10.sp),
                modifier = Modifier.padding(top = 48.dp, bottom = 48.dp)
            )
        }
    }
}

