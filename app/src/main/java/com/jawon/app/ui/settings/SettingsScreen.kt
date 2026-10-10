package com.jawon.app.ui.settings

import androidx.compose.material.icons.rounded.Vibration
import androidx.compose.material.icons.rounded.Bolt
import androidx.compose.material.icons.rounded.Headphones
import androidx.compose.material.icons.rounded.PlayCircle
import com.jawon.app.ui.theme.LocalPalette
import com.jawon.app.ui.theme.InterFamily
import com.jawon.app.ui.common.JawonMark
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.ui.draw.clip
import androidx.compose.material3.Icon
import androidx.compose.material.icons.rounded.Description
import androidx.compose.material.icons.rounded.Lock
import androidx.compose.material.icons.rounded.BugReport
import androidx.compose.material.icons.rounded.Speed
import androidx.compose.material.icons.rounded.Dns
import androidx.compose.material.icons.rounded.Backup
import androidx.compose.material.icons.rounded.CleaningServices
import androidx.compose.material.icons.rounded.Sync
import androidx.compose.material.icons.rounded.Lyrics
import androidx.compose.material.icons.rounded.Image
import androidx.compose.material.icons.rounded.GraphicEq
import androidx.compose.material.icons.rounded.Tune
import androidx.compose.material.icons.rounded.LightMode
import androidx.compose.material.icons.rounded.Album
import androidx.compose.material.icons.automirrored.rounded.KeyboardArrowRight
import androidx.compose.material.icons.Icons
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.unit.IntOffset
import androidx.compose.animation.togetherWith
import androidx.compose.animation.slideOutHorizontally
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.core.tween
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.AnimatedContent
import androidx.activity.compose.BackHandler
import androidx.compose.ui.text.input.ImeAction
import com.jawon.app.ui.common.FieldLabel
import com.jawon.app.ui.common.JawonTextField
import com.jawon.app.ui.theme.JawonType
import com.jawon.app.ui.common.ScreenBar
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
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
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
import com.jawon.app.ui.common.READING_MAX_W
import androidx.compose.ui.unit.sp
import androidx.core.content.FileProvider
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import com.jawon.app.BuildConfig
import com.jawon.app.Features
import com.jawon.app.Format
import com.jawon.app.R
import com.jawon.app.data.remote.ServerProbe
import com.jawon.app.data.remote.SubsonicServer
import com.jawon.app.data.remote.SyncState
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
            fontSize = JawonType.Caption,
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
                        style = JawonTextStyle.mono.copy(color = LabelDim, fontSize = JawonType.Caption),
                        textAlign = TextAlign.Center
                    )
                }
            }

            is UpdateState.Checking -> Text(
                "CHECKING…",
                style = JawonTextStyle.mono.copy(color = LabelDim, fontSize = JawonType.Caption)
            )

            is UpdateState.Available -> {
                Text(
                    "VERSION ${state.update.versionName.uppercase(Locale.ROOT)} IS AVAILABLE",
                    style = JawonTextStyle.mono.copy(fontSize = JawonType.Caption),
                    textAlign = TextAlign.Center
                )
                if (state.update.notes.isNotBlank()) {
                    Text(
                        state.update.notes,
                        style = JawonTextStyle.mono.copy(color = LabelDim, fontSize = JawonType.Caption),
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
                        style = JawonTextStyle.mono.copy(color = LabelDim, fontSize = JawonType.Caption),
                        lineHeight = 16.sp,
                        textAlign = TextAlign.Center
                    )
                    TextChip(text = "ALLOW, THEN COME BACK", onClick = onAllowInstalls)
                }
            }

            is UpdateState.Downloading -> Text(
                "DOWNLOADING ${(state.fraction * 100).toInt()}%",
                style = JawonTextStyle.mono.copy(fontSize = JawonType.Caption)
            )

            is UpdateState.Ready -> {
                Text(
                    "READY TO INSTALL",
                    style = JawonTextStyle.mono.copy(fontSize = JawonType.Caption)
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
            fontSize = JawonType.Body,
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
/**
 * Which room the app opens in — the format, not the light.
 *
 * Plain rows rather than the picture cards below, deliberately: a scheme is a *look* and can
 * only be chosen by looking, while a format is a different room with a different object in it,
 * and its name says more than a 52dp thumbnail could. Unfinished ones are labelled as such
 * instead of hidden, because in a debug build being able to open one is the point.
 */
@Composable
private fun FormatPicker(formats: List<Format>, current: Format, onPick: (Format) -> Unit) {
    Column(
        modifier = Modifier.fillMaxWidth(),
        verticalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        formats.forEach { format ->
            val selected = format == current
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .border(
                        width = if (selected) 2.dp else 1.dp,
                        color = if (selected) LampAmber else CrateEdge
                    )
                    .clickable { onPick(format) }
                    .padding(horizontal = 14.dp, vertical = 12.dp),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    text = format.displayName.uppercase(Locale.ROOT),
                    style = JawonTextStyle.mono.copy(
                        color = if (selected) LampAmber else LabelCream,
                        fontSize = JawonType.Title
                    )
                )
                if (!format.built) {
                    Text(
                        text = "IN PROGRESS",
                        style = JawonTextStyle.mono.copy(color = LabelDim, fontSize = JawonType.Fine)
                    )
                }
            }
        }
    }
}

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
                                fontSize = JawonType.Caption
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
            style = JawonTextStyle.mono.copy(color = LabelCream, fontSize = JawonType.Caption),
            lineHeight = 15.sp
        )

        if (!snapshot.hasData) {
            Text(
                text = "Browse the room for a few seconds, then come back — there is not " +
                    "enough here to measure yet.",
                style = JawonTextStyle.mono.copy(color = LabelDim, fontSize = JawonType.Caption),
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
                "Jawon eased off the detail ${snapshot.stepDowns} " +
                    "time${if (snapshot.stepDowns == 1) "" else "s"} because frames were " +
                    "arriving late. That is the room keeping itself smooth, and it is " +
                    "worth knowing about."
            } else {
                "The room has held the quality it started at."
            },
            style = JawonTextStyle.mono.copy(
                color = if (snapshot.stepDowns > 0) LampAmber else LabelDim,
                fontSize = JawonType.Caption
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
                fontSize = JawonType.Caption,
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
 * design, and logcat is unreachable on a tester's phone — see trap 15. Without this, a crash on
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
            fontSize = JawonType.Body,
            letterSpacing = 2.sp,
            color = LabelCream
        )

        if (report == null) {
            // The overwhelmingly common case, and it should read as reassurance rather than as
            // an empty panel that looks broken.
            Text(
                text = "Nothing has gone wrong since you last cleared this.",
                style = JawonTextStyle.mono.copy(color = LabelDim, fontSize = JawonType.Caption),
                lineHeight = 16.sp
            )
            return@Column
        }

        Text(
            text = "Something went wrong, and Jawon wrote it down. Sending it is what " +
                "gets it fixed: it holds the fault, the app version and the phone model, " +
                "and nothing about you or your music.",
            style = JawonTextStyle.mono.copy(color = LampAmber, fontSize = JawonType.Caption),
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
                style = JawonTextStyle.mono.copy(color = LabelDim, fontSize = JawonType.Fine),
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
            fontSize = JawonType.Caption,
            letterSpacing = 1.4.sp,
            color = LabelDim
        )
        Text(
            text = value,
            fontFamily = JetBrainsMonoFamily,
            fontWeight = FontWeight.Bold,
            fontSize = JawonType.Caption,
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
            fontSize = JawonType.Body,
            letterSpacing = 2.sp,
            color = LabelCream
        )
        MoreText(
            // "ReplayGain" is the name of the tag, not a thing anyone needs to know to
            // decide this. What they need to know is that old records are quieter.
            summary = "Stop older records playing quieter than new ones.",
            detail = "Older albums were mastered quieter than modern ones, so a shelf of " +
                "both keeps changing volume on you. This evens them out using loudness " +
                "figures your files already carry, and re-encodes nothing.\n\n" +
                "BY ALBUM keeps a record's own quiet and loud moments, the way it was " +
                "sequenced. BY TRACK makes every song equally loud."
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
                        fontSize = JawonType.Caption,
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
    onRead: () -> Unit,
    /** False on its own page, whose bar already names it. */
    showTitle: Boolean = true
) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(top = 26.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        if (showTitle) Text(
            text = "BACK UP YOUR SHELVES",
            fontFamily = JetBrainsMonoFamily,
            fontWeight = FontWeight.Bold,
            fontSize = JawonType.Body,
            letterSpacing = 2.sp,
            color = LabelCream
        )
        MoreText(
            summary = "Your shelves and your listening — not the music itself.",
            detail = "Saves which records sit on which shelf, what you named the shelves " +
                "and how they glow, the covers and titles you fixed, and all the " +
                "listening the wear on the cases is drawn from. Your music files are not " +
                "copied: they are already on this phone, and Jawon never moves them.\n\n" +
                "Restore onto the same phone and everything comes back. Onto a new " +
                "phone, the shelves and covers come back but you may have to import your " +
                "music again, because Android does not let one phone's file addresses " +
                "work on another."
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
                style = JawonTextStyle.mono.copy(fontSize = JawonType.Caption)
            )
        }
        message?.let {
            Text(
                text = it,
                style = JawonTextStyle.mono.copy(color = LabelCream, fontSize = JawonType.Caption),
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
            fontSize = JawonType.Caption,
            letterSpacing = 1.6.sp,
            color = if (enabled) LampAmber else LabelDim
        )
    }
}

/**
 * The music server, for a library too big to keep on the phone.
 *
 * **The one section in Settings that changes what leaves the device**, so it says so in plain
 * words rather than making the user infer it. Everything else here is local; this sends a
 * username and a password to an address the user chose, and then streams audio from it.
 *
 * ## Closed by default, and two different sections inside
 *
 * This was four paragraphs, three text fields, five buttons and a cache readout, all on screen
 * at once, all the time — the single largest block on a page that had just been cut down for
 * exactly that reason. Two things fix it.
 *
 * It is a [Disclosure], so the page carries one line about it until someone wants it, and that
 * line is the *state* — how many albums, from where — which is the only thing a person coming
 * back here wants to know. The three paragraphs on why a server and not a streaming service
 * are still here, still worth having, behind a `+`.
 *
 * And it stops showing setup to people who are set up. Filling in an address is a thing you do
 * once; syncing and sharing are things you do repeatedly. So a configured server leads with what
 * it can do now and folds the credentials away behind CHANGE THE DETAILS, while an unconfigured
 * one leads with the paste-a-link box, which is the route almost everybody should take.
 */
@Composable
private fun MusicServerSection(
    server: SubsonicServer,
    probe: ServerProbe?,
    probing: Boolean,
    syncState: SyncState,
    albumCount: Int,
    cacheUsedMb: Int,
    cacheLimitMb: Int,
    bundledServer: Boolean,
    inviteMessage: String?,
    onServerChanged: (SubsonicServer) -> Unit,
    onInvitePasted: (String) -> Unit,
    onShareInvite: () -> Unit,
    onTest: () -> Unit,
    onSync: () -> Unit,
    onForget: () -> Unit,
    onClearCache: () -> Unit,
    /** On a page of its own (Settings, 0.7.2): the content without the disclosure round it. */
    asPage: Boolean = false
) {
    val subtitle = when {
            !server.isConfigured ->
                "Not set up. Play a library that is too big for this phone."
            albumCount > 0 -> "$albumCount albums from ${server.displayHost}."
            else -> "${server.displayHost} — nothing synced yet."
        }
    val body: @Composable () -> Unit = {
        Column(
            modifier = Modifier.fillMaxWidth().padding(top = 10.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            MoreText(
                // "Point Jawon at your own Subsonic server" assumed the reader knew what a
                // Subsonic server is. What they know is that their music does not fit.
                summary = "Keep your music on a computer at home and play it here, without " +
                    "filling up your phone.",
                detail = "You run the server yourself. Navidrome, Airsonic, Gonic and " +
                    "Ampache are the usual ones and Jawon speaks to all of them. Only the " +
                    "list of albums lives on this phone: titles, tracks and one cover " +
                    "each. The music arrives as you play it, and is kept afterwards so " +
                    "the disc still scrubs the instant you touch it.\n\n" +
                    "This is still your own library. Jawon has none of its own to sell " +
                    "you, and the streaming services cannot be added: every one of them " +
                    "forbids an app from touching the sound, and touching the sound is " +
                    "the whole of what the disc does."
            )

            if (server.isConfigured) {
                ServerInUse(
                    server = server,
                    probing = probing,
                    syncState = syncState,
                    bundledServer = bundledServer,
                    inviteMessage = inviteMessage,
                    onServerChanged = onServerChanged,
                    onInvitePasted = onInvitePasted,
                    onShareInvite = onShareInvite,
                    onTest = onTest,
                    onSync = onSync,
                    onForget = onForget
                )
            } else {
                ServerSetup(
                    server = server,
                    probing = probing,
                    bundledServer = bundledServer,
                    inviteMessage = inviteMessage,
                    onServerChanged = onServerChanged,
                    onInvitePasted = onInvitePasted,
                    onTest = onTest
                )
            }

            // Refused and unreachable are reported apart, because they call for opposite fixes:
            // check the password, versus check the address.
            when (probe) {
                is ServerProbe.Reachable -> StatusLine(
                    "Connected · ${probe.serverType} ${probe.version}",
                    LampAmber
                )
                is ServerProbe.Refused -> StatusLine("Refused · ${probe.reason}", LabelCream)
                is ServerProbe.Unreachable -> StatusLine("No answer · ${probe.reason}", LabelCream)
                null -> Unit
            }

            when (syncState) {
                is SyncState.Running -> StatusLine(
                    "Reading ${syncState.done + 1} of ${syncState.total} — ${syncState.album}",
                    LabelCream
                )
                is SyncState.Done -> StatusLine(
                    "${syncState.added} new, ${syncState.updated} updated, " +
                        "${syncState.removed} gone from the server",
                    LampAmber
                )
                is SyncState.Failed -> StatusLine("Sync failed · ${syncState.reason}", LabelCream)
                SyncState.Idle -> Unit
            }

            OfflineStoreRow(
                usedMb = cacheUsedMb,
                limitMb = cacheLimitMb,
                onClear = onClearCache
            )
        }
    }
    if (asPage) {
        Box(Modifier.padding(top = 16.dp)) { body() }
    } else {
        Disclosure(title = "A MUSIC SERVER OF YOUR OWN", subtitle = subtitle) { body() }
    }
}

/**
 * A server that is already set up: what it can do, and its details tucked behind a tap.
 */
@Composable
private fun ServerInUse(
    server: SubsonicServer,
    probing: Boolean,
    syncState: SyncState,
    bundledServer: Boolean,
    inviteMessage: String?,
    onServerChanged: (SubsonicServer) -> Unit,
    onInvitePasted: (String) -> Unit,
    onShareInvite: () -> Unit,
    onTest: () -> Unit,
    onSync: () -> Unit,
    onForget: () -> Unit
) {
    Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
        Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            BackupButton(
                text = "SYNC LIBRARY",
                enabled = syncState !is SyncState.Running,
                onClick = onSync
            )
            BackupButton(
                text = if (probing) "TESTING…" else "TEST",
                enabled = !probing,
                onClick = onTest
            )
        }
        Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            BackupButton(text = "SHARE A LINK", enabled = true, onClick = onShareInvite)
            BackupButton(text = "FORGET", enabled = true, onClick = onForget)
        }
        MoreText(
            summary = "One link carries the address and the sign-in, so whoever you " +
                "send it to pastes one thing and is listening.",
            detail = "Treat the link as the password inside it: anyone holding it can " +
                "reach your server, and the only way to take that back is to change the " +
                "password there and send a new link.\n\n" +
                "FORGET clears the sign-in and leaves the albums on the wall, where they " +
                "stop playing rather than disappearing. Removing them is a separate and " +
                "deliberate step."
        )

        Disclosure(
            title = "CHANGE THE DETAILS",
            subtitle = "${server.username} at ${server.displayHost}"
        ) {
            ServerCredentials(
                server = server,
                bundledServer = bundledServer,
                inviteMessage = inviteMessage,
                onServerChanged = onServerChanged,
                onInvitePasted = onInvitePasted
            )
        }
    }
}

/**
 * No server yet: the one-field route first, because it is the one most people should use.
 *
 * Three fields is three chances to mistype and a reason to give up before hearing anything. A
 * link carries all of it, and whoever sent it already knows it works.
 */
@Composable
private fun ServerSetup(
    server: SubsonicServer,
    probing: Boolean,
    bundledServer: Boolean,
    inviteMessage: String?,
    onServerChanged: (SubsonicServer) -> Unit,
    onInvitePasted: (String) -> Unit,
    onTest: () -> Unit
) {
    Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
        ServerCredentials(
            server = server,
            bundledServer = bundledServer,
            inviteMessage = inviteMessage,
            onServerChanged = onServerChanged,
            onInvitePasted = onInvitePasted
        )
        Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            BackupButton(
                text = if (probing) "TESTING…" else "TEST",
                enabled = server.isConfigured && !probing,
                onClick = onTest
            )
        }
    }
}

/**
 * The paste box and the three fields, shared by both states above.
 *
 * One copy, because a credentials form that drifts between the setup path and the edit path is
 * two bugs waiting rather than one form.
 */
@Composable
private fun ServerCredentials(
    server: SubsonicServer,
    bundledServer: Boolean,
    inviteMessage: String?,
    onServerChanged: (SubsonicServer) -> Unit,
    onInvitePasted: (String) -> Unit
) {
    var invite by remember { mutableStateOf("") }
    // Emptied when the server goes, so FORGET does not leave the link that set it up sitting in
    // the box looking like it is still in force.
    LaunchedEffect(server.isConfigured) {
        if (!server.isConfigured) invite = ""
    }

    Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
        Text(
            text = if (bundledServer) {
                "This copy of Jawon came with a library already set up — the details below were " +
                    "filled in for you. Paste a different link to point it somewhere else."
            } else {
                "Been sent a link? Paste it here and the three fields below fill themselves in."
            },
            style = JawonTextStyle.mono.copy(color = LabelDim, fontSize = JawonType.Caption),
            lineHeight = 16.sp
        )
        ServerField(
            label = "PASTE A LINK",
            value = invite,
            placeholder = "jawon://…",
            onValueChange = {
                invite = it
                if (it.isNotBlank()) onInvitePasted(it)
            }
        )
        inviteMessage?.let { StatusLine(it, LabelCream) }

        Text(
            text = "OR FILL IT IN YOURSELF",
            fontFamily = JetBrainsMonoFamily,
            fontWeight = FontWeight.Bold,
            fontSize = JawonType.Fine,
            letterSpacing = 1.6.sp,
            color = LabelDim,
            modifier = Modifier.padding(top = 6.dp)
        )
        ServerField(
            label = "ADDRESS",
            value = server.url,
            placeholder = "https://music.example.net",
            last = false,
            onValueChange = { onServerChanged(server.copy(url = it)) }
        )
        ServerField(
            label = "USERNAME",
            value = server.username,
            last = false,
            onValueChange = { onServerChanged(server.copy(username = it)) }
        )
        ServerField(
            label = "PASSWORD",
            value = server.password,
            password = true,
            onValueChange = { onServerChanged(server.copy(password = it)) }
        )
    }
}

/**
 * What the streamed audio is using on disk.
 *
 * Named "offline store" rather than "cache" because of what it now also holds: an album kept
 * on purpose from the deck lives here too, and calling that a cache invites someone to clear it
 * expecting to lose only a head start. The wording says both.
 */
@Composable
private fun OfflineStoreRow(usedMb: Int, limitMb: Int, onClear: () -> Unit) {
    Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
        Text(
            text = "OFFLINE STORE · $usedMb OF $limitMb MB",
            fontFamily = JetBrainsMonoFamily,
            fontWeight = FontWeight.Bold,
            fontSize = JawonType.Caption,
            letterSpacing = 1.6.sp,
            color = LabelCream,
            modifier = Modifier.padding(top = 6.dp)
        )
        MoreText(
            summary = "Streamed tracks are kept here, which is what lets you turn the disc " +
                "without waiting for the network.",
            detail = "Anything you played is here, and so is anything you chose to KEEP " +
                "OFFLINE from an album's tracklist. When the store is full the oldest goes " +
                "first, so give it more room than the albums you want to keep.\n\n" +
                "Clearing it costs the head start and any kept albums; your shelves and covers " +
                "stay, and everything can be streamed again. A new size takes effect next launch."
        )
        Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            BackupButton(text = "CLEAR IT", enabled = usedMb > 0, onClick = onClear)
        }
    }
}

/**
 * Repairs, for what older builds left in the library.
 *
 * Closed, and worded as a repair rather than a feature — nobody comes to a settings screen
 * wanting to tidy their library; they come because they noticed something wrong. So the
 * subtitle names the symptom, which is how somebody who has seen a track twice finds this at
 * all.
 */
@Composable
private fun TidySection(
    busy: Boolean,
    message: String?,
    onRemoveDuplicates: () -> Unit,
    /** On a page of its own (Settings, 0.7.2): the content without the disclosure round it. */
    asPage: Boolean = false
) {
    val body: @Composable () -> Unit = {
        Column(
            modifier = Modifier.fillMaxWidth().padding(top = 10.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            MoreText(
                summary = "Removes tracks that appear twice on the same album, keeping the " +
                    "first of each.",
                detail = "A duplicate gets in when one record arrives two ways — a folder " +
                    "holding both a FLAC and an MP3, or an album imported by hand and then " +
                    "found again by the device scan. Newer builds refuse those at the door; " +
                    "this clears out the ones that got in before.\n\n" +
                    "Two tracks count as the same when they share a disc, a position and a " +
                    "name. A remix or a live take keeps its own row — those are different " +
                    "recordings, and losing one would be worse than seeing two.\n\n" +
                    "There is no undo. Nothing on disk is touched, so anything removed in " +
                    "error comes back by importing that folder again."
            )
            Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                BackupButton(
                    text = if (busy) "TIDYING…" else "REMOVE DUPLICATES",
                    enabled = !busy,
                    onClick = onRemoveDuplicates
                )
            }
            message?.let { StatusLine(it, LampAmber) }
        }
    }
    if (asPage) {
        Box(Modifier.padding(top = 16.dp)) { body() }
    } else {
        Disclosure(
            title = "TIDY THE LIBRARY",
            subtitle = "Seeing a track twice on a record? Fix it here."
        ) { body() }
    }
}

@Composable
private fun StatusLine(text: String, color: androidx.compose.ui.graphics.Color) {
    Text(
        text = text,
        style = JawonTextStyle.mono.copy(color = color, fontSize = JawonType.Caption),
        lineHeight = 16.sp
    )
}

@Composable
private fun ServerField(
    label: String,
    value: String,
    onValueChange: (String) -> Unit,
    placeholder: String? = null,
    password: Boolean = false,
    last: Boolean = true
) {
    JawonTextField(
        value = value,
        onValueChange = onValueChange,
        label = label,
        placeholder = placeholder,
        visualTransformation = if (password) {
            PasswordVisualTransformation()
        } else {
            VisualTransformation.None
        },
        keyboardOptions = KeyboardOptions(
            keyboardType = if (password) KeyboardType.Password else KeyboardType.Uri,
            // A server address is not a sentence, and autocapitalising the first letter of
            // a URL or a username is how a correct entry becomes a failed login.
            autoCorrectEnabled = false,
            imeAction = if (last) ImeAction.Done else ImeAction.Next
        )
    )
}

/**
 * One switchable setting: what it is, one line on what it does, and the switch.
 *
 * ## Why the long version is hidden rather than shortened
 *
 * This screen used to print the whole explanation under every row — four to six lines apiece,
 * for a dozen rows. Individually each paragraph is worth having; together they turn a settings
 * page into a document, and a page of documentation is one nobody reads, including the parts
 * that matter. The result was a screen that was both exhaustively honest and completely opaque.
 *
 * **Deleting the text was not an option.** Several of these paragraphs say exactly what leaves
 * the device and to whom — that is a promise `docs/privacy.md` makes in the same words, and
 * `ENGINEERING.md` treats it as one. Shortening them would quietly narrow what the app admits to.
 *
 * So nothing is removed and nothing is summarised away: [summary] is the one line a person needs
 * at the moment of deciding, [detail] is the full text unchanged, and the row opens on a tap.
 * Scannable by default, complete on demand, and the honesty is a gesture away rather than a
 * paragraph in the way.
 */
@Composable
private fun SettingRow(
    title: String,
    summary: String,
    detail: String,
    checked: Boolean,
    onCheckedChange: (Boolean) -> Unit,
    enabled: Boolean = true
) {
    var expanded by remember { mutableStateOf(false) }

    Column(modifier = Modifier.fillMaxWidth()) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                // The whole text block is the target, not a small chevron. A row is the natural
                // thing to press and a 12dp glyph is not.
                .clickable { expanded = !expanded }
                .padding(vertical = 4.dp),
            horizontalArrangement = Arrangement.spacedBy(16.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Column(
                modifier = Modifier.weight(1f),
                verticalArrangement = Arrangement.spacedBy(5.dp)
            ) {
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text(
                        text = title,
                        fontFamily = JetBrainsMonoFamily,
                        fontWeight = FontWeight.Bold,
                        fontSize = JawonType.Body,
                        letterSpacing = 2.sp,
                        color = LabelCream
                    )
                    // Understated on purpose: it marks that there is more without competing
                    // with the setting's own name.
                    Text(
                        text = if (expanded) "–" else "+",
                        fontFamily = JetBrainsMonoFamily,
                        fontWeight = FontWeight.Bold,
                        fontSize = JawonType.Body,
                        color = LampAmber.copy(alpha = 0.7f)
                    )
                }
                Text(
                    text = summary,
                    style = JawonTextStyle.mono.copy(color = LabelDim, fontSize = JawonType.Caption),
                    lineHeight = 15.sp
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
        if (expanded) {
            Text(
                text = detail,
                style = JawonTextStyle.mono.copy(color = LabelDim, fontSize = JawonType.Caption),
                lineHeight = 16.sp,
                modifier = Modifier.padding(top = 2.dp, bottom = 6.dp, end = 56.dp)
            )
        }
    }
}

/**
 * A short line, and the long version on a tap.
 *
 * The same idea as [SettingRow]'s disclosure, for the blocks of prose that are not attached to a
 * switch — what a backup contains, why a server and not a streaming service. Those paragraphs
 * are worth having and worth keeping exactly as written; they are simply not worth reading every
 * time somebody comes here to change the crossfade.
 */
@Composable
private fun MoreText(summary: String, detail: String) {
    var open by remember { mutableStateOf(false) }

    Column(
        modifier = Modifier.fillMaxWidth().clickable { open = !open },
        verticalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Text(
                text = summary,
                style = JawonTextStyle.mono.copy(color = LabelDim, fontSize = JawonType.Caption),
                lineHeight = 16.sp,
                modifier = Modifier.weight(1f)
            )
            Text(
                text = if (open) "–" else "+",
                fontFamily = JetBrainsMonoFamily,
                fontWeight = FontWeight.Bold,
                fontSize = JawonType.Body,
                color = LampAmber.copy(alpha = 0.7f)
            )
        }
        if (open) {
            Text(
                text = detail,
                style = JawonTextStyle.mono.copy(color = LabelDim, fontSize = JawonType.Caption),
                lineHeight = 16.sp
            )
        }
    }
}

/**
 * A section that is closed until it is wanted.
 *
 * For the parts of this screen that are reference rather than choice — how the room is
 * performing, what crashed, how to point the app at a server. Someone opens those perhaps twice
 * in the life of the app, and until then they are the bulk of the scrolling.
 */
@Composable
private fun Disclosure(
    title: String,
    subtitle: String,
    content: @Composable () -> Unit
) {
    var open by remember { mutableStateOf(false) }

    Column(modifier = Modifier.fillMaxWidth().padding(top = 26.dp)) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .clickable { open = !open }
                .padding(vertical = 4.dp),
            horizontalArrangement = Arrangement.spacedBy(10.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Column(modifier = Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(5.dp)) {
                Text(
                    text = title,
                    fontFamily = JetBrainsMonoFamily,
                    fontWeight = FontWeight.Bold,
                    fontSize = JawonType.Body,
                    letterSpacing = 2.sp,
                    color = LabelCream
                )
                Text(
                    text = subtitle,
                    style = JawonTextStyle.mono.copy(color = LabelDim, fontSize = JawonType.Caption),
                    lineHeight = 15.sp
                )
            }
            Text(
                text = if (open) "–" else "+",
                fontFamily = JetBrainsMonoFamily,
                fontWeight = FontWeight.Bold,
                fontSize = JawonType.Title,
                color = LampAmber.copy(alpha = 0.7f)
            )
        }
        if (open) content()
    }
}

@Composable
fun SettingsScreen(
    onBack: () -> Unit,
    /**
     * A page to open on instead of the list (0.7.4) — a [SettingsPage] name, such as the pocket
     * player's Guide page, [GUIDE_POCKET_PAGE]. Back from it comes to the list, as a link into
     * iOS's Settings does.
     */
    startPage: String? = null,
    viewModel: SettingsViewModel = hiltViewModel()
) {
    val context = LocalContext.current
    val fetchCoverArt by viewModel.fetchCoverArt.collectAsStateWithLifecycle()
    val fetchLyrics by viewModel.fetchLyrics.collectAsStateWithLifecycle()
    val shakeToSwitch by viewModel.shakeToSwitch.collectAsStateWithLifecycle()
    val keepPlayingOnClose by viewModel.keepPlayingOnClose.collectAsStateWithLifecycle()
    val shortIntro by viewModel.shortIntro.collectAsStateWithLifecycle()
    val resumeWithHeadphones by viewModel.resumeWithHeadphones.collectAsStateWithLifecycle()
    val graphicsName by viewModel.graphics.collectAsStateWithLifecycle()
    val graphics = com.jawon.app.ui.wallscene.Graphics.fromName(graphicsName)
    val preferOnlineLyrics by viewModel.preferOnlineLyrics.collectAsStateWithLifecycle()
    val roomScheme by viewModel.roomScheme.collectAsStateWithLifecycle()
    val format by viewModel.format.collectAsStateWithLifecycle()
    val crossfadeMs by viewModel.crossfadeMs.collectAsStateWithLifecycle()
    val updateState by viewModel.updateState.collectAsStateWithLifecycle()
    val caseSpin by viewModel.caseSpin.collectAsStateWithLifecycle()
    val replayGainMode by viewModel.replayGainMode.collectAsStateWithLifecycle()
    val backupBusy by viewModel.backupBusy.collectAsStateWithLifecycle()
    val backupMessage by viewModel.backupMessage.collectAsStateWithLifecycle()
    val musicServer by viewModel.musicServer.collectAsStateWithLifecycle()
    val probe by viewModel.probe.collectAsStateWithLifecycle()
    val probing by viewModel.probing.collectAsStateWithLifecycle()
    val syncState by viewModel.syncState.collectAsStateWithLifecycle()
    val streamCacheMb by viewModel.streamCacheMb.collectAsStateWithLifecycle()
    val inviteMessage by viewModel.inviteMessage.collectAsStateWithLifecycle()
    val serverAlbumCount by viewModel.serverAlbumCount.collectAsStateWithLifecycle()
    val repairBusy by viewModel.repairBusy.collectAsStateWithLifecycle()
    val repairMessage by viewModel.repairMessage.collectAsStateWithLifecycle()

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

    // **Which page is showing**: the list, or one of its detail pages. Saved, so a rotation or a
    // trip out to grant a permission comes back to the same page.
    var page by rememberSaveable {
        mutableStateOf(startPage?.let { name -> SettingsPage.entries.firstOrNull { it.name == name } })
    }
    // Held above the pages, so coming back from one finds the list where you left it, as iOS does.
    val listScroll = rememberScrollState()
    // Back steps out one page at a time before it leaves Settings, as iOS's does.
    BackHandler(enabled = page != null) { page = null }

    val showUnbuilt = BuildConfig.DEBUG || BuildConfig.SHOW_FORMATS
    val offeredFormats = Format.entries.filter { it.built || showUnbuilt }

    AnimatedContent(
        targetState = page,
        transitionSpec = {
            // A page is pushed in from the right and the list slides a little left under it;
            // going back is the same movement reversed.
            val push = tween<IntOffset>(PAGE_PUSH_MS, easing = FastOutSlowInEasing)
            if (targetState != null) {
                slideInHorizontally(push) { it } togetherWith slideOutHorizontally(push) { -it / 4 }
            } else {
                slideInHorizontally(push) { -it / 4 } togetherWith slideOutHorizontally(push) { it }
            }
        },
        label = "settingsPage"
    ) { shown ->
        when (shown) {
            null -> Column(
                modifier = Modifier
                    .fillMaxSize()
                    .background(CrateBlack)
                    .statusBarsPadding()
            ) {
                ScreenBar(title = "SETTINGS", onBack = onBack, backDescription = "Back to the room")
                Column(
                    modifier = Modifier
                        .fillMaxSize()
                        .verticalScroll(listScroll)
                        .navigationBarsPadding()
                        .widthIn(max = READING_MAX_W)
                        .align(Alignment.CenterHorizontally)
                        .padding(horizontal = 16.dp)
                ) {
                    BrandCard(
                        appName = stringResource(R.string.app_name),
                        versionName = versionName,
                        updateLine = updateLine(updateState),
                        onClick = { page = SettingsPage.UPDATES }
                    )

                    // **The Guide** (0.7.4): how to use the things only Jawon does. Second only to
                    // the brand card, because a gesture nobody knows about is a feature nobody has.
                    SettingsGroup(header = "Guide") {
                        GuideTopic.entries.forEachIndexed { i, topic ->
                            if (i > 0) SettingsDivider()
                            SettingsNavRow(
                                icon = topic.icon,
                                title = topic.title,
                                onClick = { page = guidePageFor(topic) }
                            )
                        }
                    }

                    SettingsGroup(header = "Room") {
                        if (offeredFormats.size > 1) {
                            SettingsNavRow(
                                icon = Icons.Rounded.Album,
                                title = "Room",
                                value = format.displayName,
                                onClick = { page = SettingsPage.ROOM }
                            )
                            SettingsDivider()
                        }
                        SettingsNavRow(
                            icon = Icons.Rounded.LightMode,
                            title = "The CD room's light",
                            value = roomScheme.displayName,
                            onClick = { page = SettingsPage.LIGHT }
                        )
                        SettingsDivider()
                        SettingsNavRow(
                            icon = Icons.Rounded.Speed,
                            title = "Graphics",
                            value = graphicsLabel(graphics),
                            onClick = { page = SettingsPage.GRAPHICS }
                        )
                        SettingsDivider()
                        SettingsSwitchRow(
                            icon = Icons.Rounded.Bolt,
                            title = "Short intro",
                            summary = "Open straight into the room.",
                            detail = "Jawon opens with its sign lighting, its name, and the way in " +
                                "through the hole in the disc. On, the sign lights and the room is " +
                                "there at once. A tap skips the intro either way.",
                            checked = shortIntro,
                            onCheckedChange = viewModel::onShortIntroChanged
                        )
                        if (offeredFormats.size > 1) {
                            SettingsDivider()
                            SettingsSwitchRow(
                                icon = Icons.Rounded.Vibration,
                                title = "Shake to switch rooms",
                                summary = "A shake flips to the next room.",
                                detail = "Two quick shakes back and forth flip Jawon to the next room. " +
                                    "It never happens with the phone in a pocket, under a panel or in the " +
                                    "showroom, and one shake is one room however long you keep at it.",
                                checked = shakeToSwitch,
                                onCheckedChange = viewModel::onShakeToSwitchChanged
                            )
                        }
                    }

                    SettingsGroup(header = "Playback") {
                        SettingsSwitchRow(
                            icon = Icons.Rounded.PlayCircle,
                            title = "Keep playing when a case closes",
                            summary = "Browse the wall while the record plays on.",
                            detail = "Close a case and its record keeps playing: the case sits " +
                                "proud in its slot and the bar at the foot of the wall carries it — " +
                                "tap the bar to open it again, swipe it sideways to change track, " +
                                "swipe it down to put the record away. Off, closing a case pauses " +
                                "the music, as a disc put back in its box would.",
                            checked = keepPlayingOnClose,
                            onCheckedChange = viewModel::onKeepPlayingOnCloseChanged
                        )
                        SettingsDivider()
                        SettingsSwitchRow(
                            icon = Icons.Rounded.Headphones,
                            title = "Resume with headphones",
                            summary = "Headphones and cars may start the music.",
                            detail = "On: if your headphones drop out and come back within ten " +
                                "minutes, the record carries on; and pressing play on a headset, " +
                                "or connecting to a car that asks for music, puts the last record " +
                                "back on even if Jawon was closed. Off, nothing ever starts " +
                                "playing unless you start it on the phone.",
                            checked = resumeWithHeadphones,
                            onCheckedChange = viewModel::onResumeWithHeadphonesChanged
                        )
                        SettingsDivider()
                        SettingsNavRow(
                            icon = Icons.Rounded.Tune,
                            title = "Crossfade",
                            value = crossfadeLabel(crossfadeMs),
                            onClick = { page = SettingsPage.CROSSFADE }
                        )
                        SettingsDivider()
                        SettingsNavRow(
                            icon = Icons.Rounded.GraphicEq,
                            title = "Even out loudness",
                            value = loudnessLabel(ReplayGain.Mode.fromName(replayGainMode)),
                            onClick = { page = SettingsPage.LOUDNESS }
                        )
                    }

                    SettingsGroup(
                        header = "Library",
                        footer = "Lookups send a record's title and artist — never an account, " +
                            "a phone id or a list of what you own."
                    ) {
                        SettingsSwitchRow(
                            icon = Icons.Rounded.Image,
                            title = "Find missing covers",
                            summary = "For albums that arrived without one.",
                            // The full text is a promise docs/privacy.md makes in the same words.
                            detail = "When an album's own files carry no picture, Jawon asks " +
                                "MusicBrainz — a free, public music database — for one, sending " +
                                "the album title and the artist and nothing else. No account, no " +
                                "phone id, no list of what you own. Turn it off and albums without " +
                                "a picture simply stay blank.",
                            checked = fetchCoverArt,
                            onCheckedChange = viewModel::onFetchCoverArtChanged
                        )
                        SettingsDivider()
                        SettingsSwitchRow(
                            icon = Icons.Rounded.Lyrics,
                            title = "Find lyrics",
                            summary = "For songs that carry no words of their own.",
                            detail = "When a song has no words saved in it, Jawon asks LRCLIB — a " +
                                "free lyrics database — sending the title, artist, album and " +
                                "length. Only when you open the lyrics page, only for songs that " +
                                "have none, and the answer is kept so nothing is asked for twice.",
                            checked = fetchLyrics,
                            onCheckedChange = viewModel::onFetchLyricsChanged
                        )
                        SettingsDivider()
                        SettingsSwitchRow(
                            icon = Icons.Rounded.Sync,
                            title = "Prefer online lyrics",
                            summary = "Even for songs that already have words.",
                            detail = "Jawon already prefers words that scroll in time with the " +
                                "music over words that just sit there. Turn this on to take the " +
                                "online copy anyway — worth it if the words saved in your files " +
                                "are wrong or badly timed. One lookup per song, and it does " +
                                "nothing unless Find lyrics is on.",
                            checked = preferOnlineLyrics,
                            onCheckedChange = viewModel::onPreferOnlineLyricsChanged,
                            enabled = fetchLyrics
                        )
                        SettingsDivider()
                        SettingsNavRow(
                            icon = Icons.Rounded.CleaningServices,
                            title = "Fix duplicate tracks",
                            onClick = { page = SettingsPage.TIDY }
                        )
                        SettingsDivider()
                        SettingsNavRow(
                            icon = Icons.Rounded.Backup,
                            title = "Back up and restore",
                            onClick = { page = SettingsPage.BACKUP }
                        )
                    }

                    if (Features.MUSIC_SERVER) {
                        SettingsGroup(header = "Music server") {
                            SettingsNavRow(
                                icon = Icons.Rounded.Dns,
                                title = "Your server",
                                value = when {
                                    !musicServer.isConfigured -> "Not set up"
                                    serverAlbumCount > 0 -> "$serverAlbumCount albums"
                                    else -> "Connected"
                                },
                                onClick = { page = SettingsPage.SERVER }
                            )
                        }
                    }

                    SettingsGroup(header = "About") {
                        SettingsNavRow(
                            icon = Icons.Rounded.Speed,
                            title = "Performance",
                            onClick = { page = SettingsPage.PERFORMANCE }
                        )
                        SettingsDivider()
                        SettingsNavRow(
                            icon = Icons.Rounded.BugReport,
                            title = "Problem reports",
                            value = if (crashReport == null) "None" else "1 report",
                            onClick = { page = SettingsPage.PROBLEMS }
                        )
                        SettingsDivider()
                        SettingsNavRow(
                            icon = Icons.Rounded.Lock,
                            title = "Privacy",
                            onClick = { page = SettingsPage.PRIVACY }
                        )
                    }

                    // Was "Everything Jawon knows lives on this phone", which stopped being true
                    // the moment a music server could be attached. It is still true of everything
                    // the *app* keeps, and that is what it says.
                    Column(
                        Modifier
                            .fillMaxWidth()
                            .padding(top = 40.dp, bottom = 48.dp),
                        horizontalAlignment = Alignment.CenterHorizontally,
                        verticalArrangement = Arrangement.spacedBy(10.dp)
                    ) {
                        JawonMark(size = 22.dp, color = LabelDim, contentDescription = null)
                        Text(
                            text = "Jawon keeps nothing about you, anywhere but this phone.",
                            fontFamily = InterFamily,
                            fontSize = 13.sp,
                            color = LabelDim,
                            textAlign = TextAlign.Center
                        )
                    }
                }
            }

            SettingsPage.UPDATES -> SettingsPage(title = "Updates", onBack = { page = null }) {
                Box(Modifier.fillMaxWidth().padding(top = 32.dp)) {
                    UpdateSection(
                        state = updateState,
                        canInstall = canInstall,
                        onCheck = viewModel::onCheckForUpdates,
                        onDownload = viewModel::onDownloadUpdate,
                        onInstall = { apk -> installUpdate(context, apk) },
                        onAllowInstalls = { openInstallPermission(context) }
                    )
                }
            }

            SettingsPage.ROOM -> SettingsPage(title = "Room", onBack = { page = null }) {
                SettingsGroup(
                    header = "Open Jawon in",
                    footer = "Both rooms play the same library. You can also walk between them: " +
                        "press Back on the wall, or tap the neon sign, to enter the showroom."
                ) {
                    offeredFormats.forEachIndexed { i, option ->
                        if (i > 0) SettingsDivider(inset = false)
                        SettingsChoiceRow(
                            title = option.displayName,
                            summary = formatLine(option),
                            selected = option == format,
                            onClick = { viewModel.onFormatChanged(option) }
                        )
                    }
                }
            }

            SettingsPage.LIGHT -> SettingsPage(title = "The CD room's light", onBack = { page = null }) {
                Box(Modifier.padding(top = 28.dp)) {
                    SchemePicker(current = roomScheme, onPick = viewModel::onRoomSchemeChanged)
                }
                SettingsGroup(
                    header = "Cases",
                    footer = "Cases turn slowly to show you their cover, then their spine. Full " +
                        "is the speed the room was built around; turn it right down and they " +
                        "hold still, facing you. They float either way."
                ) {
                    SliderRow(
                        title = "Turning",
                        value = when {
                            caseSpin <= 0f -> "Off"
                            caseSpin >= 1f -> "Full"
                            else -> "%.0f%%".format(Locale.US, caseSpin * 100f)
                        },
                        position = caseSpin,
                        range = 0f..1f,
                        onChange = viewModel::onCaseSpinChanged
                    )
                }
            }

            SettingsPage.CROSSFADE -> SettingsPage(title = "Crossfade", onBack = { page = null }) {
                SettingsGroup(
                    header = null,
                    footer = "Off to begin with, and that is deliberate: plenty of records run " +
                        "straight from one track into the next on purpose, and a crossfade " +
                        "spoils it."
                ) {
                    // From zero, so "off" is on the same control rather than a separate switch —
                    // the slider's left end already means what a switch would say.
                    SliderRow(
                        title = "Fade one song into the next",
                        value = crossfadeLabel(crossfadeMs),
                        position = crossfadeMs.toFloat(),
                        range = 0f..Crossfade.MAX_MS.toFloat(),
                        onChange = { viewModel.onCrossfadeChanged(it.toInt()) }
                    )
                }
            }

            SettingsPage.LOUDNESS -> SettingsPage(title = "Even out loudness", onBack = { page = null }) {
                val mode = ReplayGain.Mode.fromName(replayGainMode)
                SettingsGroup(
                    header = null,
                    // "ReplayGain" is the name of the tag, not a thing anyone needs to know to
                    // decide this. What they need to know is that old records are quieter.
                    footer = "Older albums were mastered quieter than modern ones, so a shelf of " +
                        "both keeps changing volume on you. This evens them out using loudness " +
                        "figures your files already carry, and re-encodes nothing."
                ) {
                    ReplayGain.Mode.entries.forEachIndexed { i, option ->
                        if (i > 0) SettingsDivider(inset = false)
                        SettingsChoiceRow(
                            title = loudnessLabel(option),
                            summary = loudnessLine(option),
                            selected = option == mode,
                            onClick = { viewModel.onReplayGainModeChanged(option) }
                        )
                    }
                }
            }

            SettingsPage.TIDY -> SettingsPage(title = "Fix duplicate tracks", onBack = { page = null }) {
                TidySection(
                    busy = repairBusy,
                    message = repairMessage,
                    onRemoveDuplicates = viewModel::onRemoveDuplicateTracks,
                    asPage = true
                )
            }

            SettingsPage.BACKUP -> SettingsPage(title = "Back up and restore", onBack = { page = null }) {
                BackupSection(
                    busy = backupBusy,
                    message = backupMessage,
                    onWrite = { writeBackupLauncher.launch(viewModel.suggestedBackupName()) },
                    onRead = { readBackupLauncher.launch(arrayOf("application/zip", "*/*")) },
                    showTitle = false
                )
            }

            SettingsPage.SERVER -> SettingsPage(title = "Your music server", onBack = { page = null }) {
                MusicServerSection(
                    server = musicServer,
                    probe = probe,
                    probing = probing,
                    syncState = syncState,
                    albumCount = serverAlbumCount,
                    // Read on composition rather than collected: the cache size changes as audio
                    // streams, and a flow of it would recompose this screen continuously for a
                    // number nobody is watching change.
                    cacheUsedMb = viewModel.cacheUsedMb(),
                    cacheLimitMb = streamCacheMb,
                    bundledServer = viewModel.hasBundledServer,
                    inviteMessage = inviteMessage,
                    onServerChanged = viewModel::onServerChanged,
                    onInvitePasted = viewModel::onInvitePasted,
                    onShareInvite = {
                        viewModel.inviteForCurrentServer()?.let { link ->
                            context.startActivity(
                                Intent.createChooser(
                                    Intent(Intent.ACTION_SEND)
                                        .setType("text/plain")
                                        .putExtra(Intent.EXTRA_TEXT, link),
                                    "Share this library"
                                )
                            )
                        }
                    },
                    onTest = viewModel::onTestServer,
                    onSync = viewModel::onSyncLibrary,
                    onForget = viewModel::onForgetServer,
                    onClearCache = viewModel::onClearStreamCache,
                    asPage = true
                )
            }

            SettingsPage.GUIDE_CD, SettingsPage.GUIDE_POCKET, SettingsPage.GUIDE_ROOMS, SettingsPage.GUIDE_MUSIC -> {
                val topic = guideTopicOf(shown)
                SettingsPage(title = topic.title, onBack = { page = null }) { GuideTopicContent(topic) }
            }

            SettingsPage.GRAPHICS -> SettingsPage(title = "Graphics", onBack = { page = null }) {
                SettingsGroup(
                    header = null,
                    footer = if (com.jawon.app.ui.wallscene.GraphicsChoice.batterySaver) {
                        "Battery saver is on, so the rooms are using Light until it is off."
                    } else {
                        "The rooms are rebuilt with the new setting as soon as you choose it. " +
                            "Everything you read — the deck, the lists, these pages — stays sharp " +
                            "whichever you pick."
                    }
                ) {
                    com.jawon.app.ui.wallscene.Graphics.entries.forEachIndexed { i, option ->
                        if (i > 0) SettingsDivider(inset = false)
                        SettingsChoiceRow(
                            title = graphicsLabel(option),
                            summary = graphicsLine(option),
                            selected = option == graphics,
                            onClick = {
                                if (option != graphics) {
                                    viewModel.onGraphicsChanged(option.name)
                                    // Rebuilt at once: the tier, the shadows and the light are
                                    // fixed as a room is built, so a room that is not rebuilt
                                    // would go on drawing the old way.
                                    context.findActivityForRecreate()?.recreate()
                                }
                            }
                        )
                    }
                }
            }

            SettingsPage.PERFORMANCE -> SettingsPage(title = "Performance", onBack = { page = null }) {
                DiagnosticsSection(
                    device = viewModel.deviceLine(),
                    snapshot = viewModel.diagnosticsSnapshot(),
                    onReset = viewModel::onResetDiagnostics
                )
            }

            SettingsPage.PROBLEMS -> SettingsPage(title = "Problem reports", onBack = { page = null }) {
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
            }

            SettingsPage.PRIVACY -> SettingsPage(title = "Privacy", onBack = { page = null }) {
                SettingsGroup(
                    header = "What stays on this phone",
                    footer = "Your library, your shelves, your listening and your settings live " +
                        "in Jawon's private storage. There is no account, no analytics, and no " +
                        "server of Jawon's own — nothing is ever sent to the developer."
                ) {
                    SettingsNavRow(
                        icon = Icons.Rounded.Description,
                        title = "Read the privacy policy",
                        onClick = {
                            context.startActivity(
                                Intent(Intent.ACTION_VIEW, Uri.parse(PRIVACY_POLICY_URL))
                            )
                        }
                    )
                }
                SettingsGroup(
                    header = "What goes online",
                    footer = "Each happens only as described. The first two have their own " +
                        "switches under Library."
                ) {
                    Column(
                        Modifier.padding(16.dp),
                        verticalArrangement = Arrangement.spacedBy(10.dp)
                    ) {
                        ONLINE_USES.forEach {
                            Text(
                                text = it,
                                fontFamily = InterFamily,
                                fontSize = 14.sp,
                                lineHeight = 19.sp,
                                color = LabelCream
                            )
                        }
                    }
                }
            }
        }
    }
}

/** The pages under Settings' list. */
private enum class SettingsPage {
    UPDATES, ROOM, LIGHT, GRAPHICS, CROSSFADE, LOUDNESS, TIDY, BACKUP, SERVER, PERFORMANCE, PROBLEMS, PRIVACY,
    GUIDE_CD, GUIDE_POCKET, GUIDE_ROOMS, GUIDE_MUSIC
}

private fun graphicsLabel(g: com.jawon.app.ui.wallscene.Graphics): String = when (g) {
    com.jawon.app.ui.wallscene.Graphics.BEST -> "Best looking"
    com.jawon.app.ui.wallscene.Graphics.BALANCED -> "Balanced"
    com.jawon.app.ui.wallscene.Graphics.LIGHT -> "Light"
}

private fun graphicsLine(g: com.jawon.app.ui.wallscene.Graphics): String = when (g) {
    com.jawon.app.ui.wallscene.Graphics.BEST -> "The rooms at full detail. Your phone works hardest, and gets warmest."
    com.jawon.app.ui.wallscene.Graphics.BALANCED -> "Nearly the same look, much lighter work. Recommended."
    com.jawon.app.ui.wallscene.Graphics.LIGHT -> "Simpler light and shadow, at most 30 frames a second. For older phones, or for battery."
}

private tailrec fun android.content.Context.findActivityForRecreate(): android.app.Activity? = when (this) {
    is android.app.Activity -> this
    is android.content.ContextWrapper -> baseContext.findActivityForRecreate()
    else -> null
}

/** The Guide's pages by name, for the rooms that open Settings on one of them. */
const val GUIDE_POCKET_PAGE = "GUIDE_POCKET"
const val GUIDE_CD_PAGE = "GUIDE_CD"
const val GUIDE_ROOMS_PAGE = "GUIDE_ROOMS"

private fun guidePageFor(topic: GuideTopic): SettingsPage = when (topic) {
    GuideTopic.CD -> SettingsPage.GUIDE_CD
    GuideTopic.POCKET -> SettingsPage.GUIDE_POCKET
    GuideTopic.ROOMS -> SettingsPage.GUIDE_ROOMS
    GuideTopic.MUSIC -> SettingsPage.GUIDE_MUSIC
}

private fun guideTopicOf(page: SettingsPage): GuideTopic = when (page) {
    SettingsPage.GUIDE_POCKET -> GuideTopic.POCKET
    SettingsPage.GUIDE_ROOMS -> GuideTopic.ROOMS
    SettingsPage.GUIDE_MUSIC -> GuideTopic.MUSIC
    else -> GuideTopic.CD
}

private const val PAGE_PUSH_MS = 280

private const val PRIVACY_POLICY_URL =
    "https://github.com/veneficus7/javon-releases/blob/main/PRIVACY.md"

/** `docs/privacy.md`'s six network uses, one line each, in its order. */
private val ONLINE_USES = listOf(
    "Finding missing covers — a title and an artist, to MusicBrainz.",
    "Finding lyrics — a title, artist, album and length, to LRCLIB.",
    "Checking for updates — only when you ask.",
    "Your music server — only if you set one up.",
    "The record shop — your searches, to the Internet Archive.",
    "Ripping a CD — the disc's id, to MusicBrainz."
)

private fun crossfadeLabel(ms: Int): String =
    if (ms < Crossfade.MIN_MS) "Off" else "%.1f s".format(Locale.US, ms / 1000f)

private fun loudnessLabel(mode: ReplayGain.Mode): String = when (mode) {
    ReplayGain.Mode.OFF -> "Off"
    ReplayGain.Mode.ALBUM -> "By album"
    ReplayGain.Mode.TRACK -> "By track"
}

private fun loudnessLine(mode: ReplayGain.Mode): String = when (mode) {
    ReplayGain.Mode.OFF -> "Every file plays at its own level."
    ReplayGain.Mode.ALBUM -> "Keeps a record's own quiet and loud moments."
    ReplayGain.Mode.TRACK -> "Makes every song equally loud."
}

private fun formatLine(format: Format): String = when (format) {
    Format.CD -> "A wall of records you pull out and play."
    Format.POCKET -> "Your phone becomes the player, with a click wheel."
} + if (!format.built) " In progress." else ""

/** The brand card's second line: what the version stands for right now. */
private fun updateLine(state: UpdateState): String = when (state) {
    is UpdateState.Idle -> "Check for updates"
    is UpdateState.Checking -> "Checking…"
    is UpdateState.UpToDate -> "Up to date"
    is UpdateState.Available -> "Version ${state.update.versionName} is available"
    is UpdateState.Downloading -> "Downloading ${(state.fraction * 100).toInt()}%"
    is UpdateState.Ready -> "Ready to install"
    is UpdateState.Failed -> "Couldn't check — tap to try again"
}

/** A labelled slider in a card: the name, its reading at the right, and the track under them. */
@Composable
private fun SliderRow(
    title: String,
    value: String,
    position: Float,
    range: ClosedFloatingPointRange<Float>,
    onChange: (Float) -> Unit
) {
    Column(Modifier.padding(horizontal = 16.dp, vertical = 12.dp)) {
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
            Text(title, fontFamily = InterFamily, fontSize = 16.sp, color = LabelCream)
            Text(value, fontFamily = JetBrainsMonoFamily, fontSize = 13.sp, color = LabelDim)
        }
        Slider(
            value = position,
            onValueChange = onChange,
            valueRange = range,
            colors = SliderDefaults.colors(
                thumbColor = LampAmber,
                activeTrackColor = LampAmber.copy(alpha = 0.7f),
                inactiveTrackColor = CrateEdge
            )
        )
    }
}

/**
 * The top of Settings, where iOS puts your account: Jawon's mark and name, the version, and what
 * that version stands for. A beta tester quotes the version back with every bug report, so it is
 * the first thing on the page; a tap opens the update page.
 */
@Composable
private fun BrandCard(appName: String, versionName: String, updateLine: String, onClick: () -> Unit) {
    val corner = LocalPalette.current.corner
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(top = 20.dp)
            .clip(RoundedCornerShape(corner))
            .background(
                Brush.verticalGradient(
                    listOf(LampAmber.copy(alpha = 0.14f), LampAmber.copy(alpha = 0.04f))
                )
            )
            .clickable(onClick = onClick)
            .padding(18.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(16.dp)
    ) {
        JawonMark(size = 52.dp, contentDescription = null)
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(3.dp)) {
            Text(
                text = appName.uppercase(Locale.ROOT),
                fontFamily = ArchivoFamily,
                fontWeight = FontWeight.ExtraBold,
                fontSize = 24.sp,
                letterSpacing = 2.sp,
                color = LabelCream
            )
            if (versionName.isNotEmpty()) {
                Text(
                    text = versionName,
                    fontFamily = JetBrainsMonoFamily,
                    fontSize = 12.sp,
                    color = LabelDim
                )
            }
            Text(
                text = updateLine,
                fontFamily = InterFamily,
                fontWeight = FontWeight.Medium,
                fontSize = 14.sp,
                color = LampAmber
            )
        }
        Icon(
            Icons.AutoMirrored.Rounded.KeyboardArrowRight,
            contentDescription = null,
            tint = LabelDim.copy(alpha = 0.7f)
        )
    }
}
