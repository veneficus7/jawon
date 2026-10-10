package com.jawon.app.ui.cd

import com.jawon.app.ui.theme.JawonType
import com.jawon.app.ui.common.ScreenBar
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.jawon.app.data.cd.CdRip
import com.jawon.app.ui.common.READING_MAX_W
import com.jawon.app.ui.theme.ArchivoFamily
import com.jawon.app.ui.theme.CrateBlack
import com.jawon.app.ui.theme.CrateEdge
import com.jawon.app.ui.theme.CrateRaised
import com.jawon.app.ui.theme.JetBrainsMonoFamily
import com.jawon.app.ui.theme.LabelCream
import com.jawon.app.ui.theme.LabelDim
import com.jawon.app.ui.theme.LampAmber
import java.util.Locale

/**
 * Bringing a CD in off a USB drive.
 *
 * ## What this screen is really for
 *
 * The rip itself is a progress bar; the screen exists for everything around it. Plugging an
 * optical drive into a phone fails in four different ways that all look the same from the sofa —
 * nothing attached, attached but not permitted, permitted but empty, spinning up — and a screen
 * that answered "no drive" to all four would be untestable by the person holding the cable. So
 * each state gets its own sentence and, where there is one, its own thing to press.
 *
 * ## Where the files go, and why that is the user's choice
 *
 * A CD is about 600MB as WAV. That is not something to drop into app-private storage on a phone,
 * and it would also be the only audio in this app the user has no path to. So the rip writes into
 * a folder picked with the system chooser, and then hands that folder to the ordinary import —
 * the same call the ADD ALBUM folder button makes. Nothing about importing a ripped CD is a new
 * path; it is a normal folder that happened to be written a minute ago.
 */
@Composable
fun CdImportScreen(
    onBack: () -> Unit,
    /** Hands the finished folder to the importer, exactly as if it had been picked by hand. */
    onImportFolder: (android.net.Uri) -> Unit,
    viewModel: CdImportViewModel = hiltViewModel()
) {
    val state by viewModel.state.collectAsStateWithLifecycle()

    val folderLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.OpenDocumentTree()
    ) { uri -> uri?.let(viewModel::rip) }

    LaunchedEffect(state) {
        val done = state as? CdState.Done ?: return@LaunchedEffect
        onImportFolder(done.folder)
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(CrateBlack)
            .statusBarsPadding()
    ) {
        ScreenBar(title = "IMPORT A CD", onBack = onBack)

        Column(
            modifier = Modifier
                .fillMaxSize()
                .widthIn(max = READING_MAX_W)
                .align(Alignment.CenterHorizontally)
                .navigationBarsPadding()
                .padding(horizontal = 24.dp, vertical = 16.dp)
        ) {
            // The journey, shown whole (0.7.6): what to connect, drawn, and the five steps with
            // this one marked. A failure stays at the step it happened in — "try again" means
            // that step again, not the beginning.
            val stage = state.stage()
            var reached by remember { mutableStateOf(CdStep.DRIVE) }
            val step = CdSteps.stepOf(stage, before = reached)
            LaunchedEffect(step) { reached = step }

            CdDrawing(stage, Modifier.padding(horizontal = 6.dp))
            Steps(now = step, modifier = Modifier.padding(top = 14.dp, bottom = 18.dp))

            when (val current = state) {
                is CdState.NoDrive -> Note(
                    title = "WAITING FOR A DRIVE",
                    body = "Plug a USB CD drive into your phone — Jawon notices it by itself. " +
                        "Most drives need their own power, the second lead in the drawing: a " +
                        "phone cannot run one alone, and a drive that lights up and then stops " +
                        "answering is one that is short of power.",
                    action = "LOOK AGAIN",
                    onAction = viewModel::look
                )

                is CdState.NeedsPermission -> Note(
                    title = "DRIVE FOUND",
                    body = "Android asks before any app may use something plugged into the " +
                        "phone. Allow it, and Jawon reads the disc.",
                    action = "ALLOW THE DRIVE",
                    onAction = { viewModel.requestPermission(current.device) }
                )

                is CdState.Reading -> Note(
                    title = "READING THE DISC",
                    body = "Waiting for it to spin up, then reading what is on it. A few seconds."
                )

                is CdState.NoDisc -> Note(
                    title = "NO DISC TO READ",
                    body = "The drive is answering but there is no music disc in it. Put one " +
                        "in, label up, close the tray and look again. A disc of files — photos, " +
                        "MP3s — reads as empty here: this copies audio CDs.",
                    action = "LOOK AGAIN",
                    onAction = viewModel::look
                )

                is CdState.Ready -> ReadyView(
                    state = current,
                    onRip = { folderLauncher.launch(null) }
                )

                is CdState.Ripping -> RippingView(current, viewModel::cancelRip)

                is CdState.Done -> Note(
                    title = "COPIED",
                    body = "${current.tracks} tracks copied. Putting it on a shelf."
                )

                is CdState.Failed -> Note(
                    title = "STOPPED",
                    body = current.reason,
                    action = "TRY AGAIN",
                    onAction = viewModel::look
                )
            }
        }
    }
}

/**
 * The five steps in a row, this one marked: done steps filled, this one ringed and named, the
 * rest waiting. How far along, and how much is left, at a glance.
 */
@Composable
private fun Steps(now: CdStep, modifier: Modifier = Modifier) {
    val amber = LampAmber
    val waiting = CrateEdge
    val ground = CrateBlack
    Column(
        modifier = modifier
            .fillMaxWidth()
            .semantics(mergeDescendants = true) {
                contentDescription =
                    "Step ${now.ordinal + 1} of ${CdStep.entries.size}: ${now.title}"
            },
        verticalArrangement = Arrangement.spacedBy(10.dp)
    ) {
        Canvas(Modifier.fillMaxWidth().height(22.dp)) {
            val count = CdStep.entries.size
            val radius = 9.dp.toPx()
            val y = size.height / 2f
            val first = radius + 1.dp.toPx()
            val gap = (size.width - 2f * first) / (count - 1)
            // The line between the dots, lit as far as this step.
            drawLine(waiting, Offset(first, y), Offset(size.width - first, y), 2.dp.toPx())
            drawLine(amber, Offset(first, y), Offset(first + gap * now.ordinal, y), 2.dp.toPx())
            for (step in CdStep.entries) {
                val centre = Offset(first + gap * step.ordinal, y)
                when (CdSteps.mark(step, now)) {
                    CdSteps.Mark.DONE -> {
                        drawCircle(amber, radius, centre)
                        // A tick, in the ground's colour.
                        val tick = Path().apply {
                            moveTo(centre.x - radius * 0.42f, centre.y + radius * 0.02f)
                            lineTo(centre.x - radius * 0.10f, centre.y + radius * 0.34f)
                            lineTo(centre.x + radius * 0.44f, centre.y - radius * 0.30f)
                        }
                        drawPath(tick, ground, style = Stroke(2.dp.toPx(), cap = StrokeCap.Round, join = StrokeJoin.Round))
                    }
                    CdSteps.Mark.NOW -> {
                        drawCircle(ground, radius, centre)
                        drawCircle(amber, radius - 1.dp.toPx(), centre, style = Stroke(2.dp.toPx()))
                        drawCircle(amber, radius * 0.38f, centre)
                    }
                    CdSteps.Mark.TO_DO -> {
                        drawCircle(ground, radius, centre)
                        drawCircle(waiting, radius - 1.dp.toPx(), centre, style = Stroke(2.dp.toPx()))
                    }
                }
            }
        }
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            Text(
                text = "STEP ${now.ordinal + 1} OF ${CdStep.entries.size}",
                fontFamily = JetBrainsMonoFamily,
                fontSize = JawonType.Fine,
                letterSpacing = 1.6.sp,
                color = LabelDim
            )
            Text(
                text = now.title,
                fontFamily = ArchivoFamily,
                fontWeight = FontWeight.ExtraBold,
                fontSize = JawonType.Subhead,
                color = LabelCream,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
        }
    }
}

/** One state, said in a sentence, with the thing to press if there is one. */
@Composable
private fun Note(title: String, body: String, action: String? = null, onAction: () -> Unit = {}) {
    Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
        Text(
            text = title,
            fontFamily = JetBrainsMonoFamily,
            fontWeight = FontWeight.Bold,
            fontSize = JawonType.Body,
            letterSpacing = 2.sp,
            color = LampAmber
        )
        Text(
            text = body,
            fontFamily = JetBrainsMonoFamily,
            fontSize = JawonType.Body,
            lineHeight = 18.sp,
            color = LabelDim
        )
        if (action != null) Button(action, onAction)
    }
}

@Composable
private fun Button(text: String, onClick: () -> Unit, enabled: Boolean = true) {
    Box(
        modifier = Modifier
            .clip(RoundedCornerShape(3.dp))
            .background(CrateRaised)
            .clickable(enabled = enabled, onClick = onClick)
            .padding(horizontal = 18.dp, vertical = 12.dp)
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
 * The disc, before anything is written.
 *
 * The size is quoted up front and in the same units the phone's storage screen uses, because
 * around 600MB per disc is genuinely surprising and finding out halfway through is the worst time.
 */
@Composable
private fun ReadyView(state: CdState.Ready, onRip: () -> Unit) {
    val minutes = state.toc.totalDurationMs / 60_000
    val megabytes = CdRip.totalBytes(state.plan) / (1024 * 1024)

    Column(Modifier.fillMaxHeight(), verticalArrangement = Arrangement.spacedBy(14.dp)) {
        Text(
            text = state.release?.title ?: "UNKNOWN DISC",
            style = MaterialTheme.typography.titleMedium,
            color = LabelCream,
            maxLines = 2,
            overflow = TextOverflow.Ellipsis
        )
        Text(
            text = buildString {
                state.release?.artist?.let { append(it).append("  ·  ") }
                append(state.plan.size)
                append(" TRACKS  ·  ")
                append(minutes)
                append(" MIN  ·  ")
                append(megabytes)
                append(" MB")
            }.uppercase(Locale.ROOT),
            fontFamily = JetBrainsMonoFamily,
            fontSize = JawonType.Caption,
            letterSpacing = 1.4.sp,
            color = LabelDim
        )
        if (state.identifying) {
            Text(
                text = "LOOKING THE DISC UP…",
                fontFamily = JetBrainsMonoFamily,
                fontSize = JawonType.Fine,
                letterSpacing = 1.4.sp,
                color = LabelDim
            )
        } else if (state.release == null) {
            // Not an error, and worth saying so plainly: most home-made and plenty of obscure
            // discs are simply not in the database, and the rip is unaffected.
            Text(
                text = "NOT IN THE DATABASE — TRACKS WILL BE NUMBERED, AND YOU CAN NAME THEM " +
                    "ON THE NEXT SCREEN.",
                fontFamily = JetBrainsMonoFamily,
                fontSize = JawonType.Fine,
                lineHeight = 15.sp,
                letterSpacing = 1.2.sp,
                color = LabelDim
            )
        }

        Button("CHOOSE A FOLDER AND RIP", onRip)

        Text(
            text = "The tracks are written as WAV into a folder you pick, then imported like " +
                "any other folder. They stay yours, where you put them.",
            fontFamily = JetBrainsMonoFamily,
            fontSize = JawonType.Caption,
            lineHeight = 16.sp,
            color = LabelDim
        )

        LazyColumn(Modifier.fillMaxWidth()) {
            items(state.plan, key = { it.number }) { track ->
                Row(
                    modifier = Modifier.fillMaxWidth().padding(vertical = 6.dp),
                    horizontalArrangement = Arrangement.spacedBy(12.dp)
                ) {
                    Text(
                        text = "%02d".format(track.number),
                        fontFamily = JetBrainsMonoFamily,
                        fontSize = JawonType.Body,
                        color = LabelDim
                    )
                    Text(
                        text = track.title ?: "Track ${track.number}",
                        style = MaterialTheme.typography.bodyMedium,
                        color = LabelCream,
                        modifier = Modifier.weight(1f),
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                    Text(
                        text = "%d:%02d".format(
                            track.sectors / 75 / 60,
                            (track.sectors / 75) % 60
                        ),
                        fontFamily = JetBrainsMonoFamily,
                        fontSize = JawonType.Body,
                        color = LabelDim
                    )
                }
            }
        }
    }
}

@Composable
private fun RippingView(state: CdState.Ripping, onCancel: () -> Unit) {
    val progress = state.progress
    Column(verticalArrangement = Arrangement.spacedBy(14.dp)) {
        Text(
            text = "RIPPING",
            fontFamily = JetBrainsMonoFamily,
            fontWeight = FontWeight.Bold,
            fontSize = JawonType.Body,
            letterSpacing = 2.sp,
            color = LampAmber
        )
        Text(
            text = progress.trackTitle ?: "Track ${progress.trackNumber}",
            style = MaterialTheme.typography.bodyMedium,
            color = LabelCream,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis
        )
        Box(
            Modifier.fillMaxWidth().height(3.dp).background(CrateEdge)
        ) {
            Box(
                Modifier
                    .fillMaxHeight()
                    .fillMaxWidth(progress.fraction.coerceIn(0f, 1f))
                    .background(LampAmber)
            )
        }
        Text(
            text = "TRACK ${progress.tracksDone + 1} OF ${progress.tracksTotal}  ·  " +
                "${(progress.fraction * 100).toInt()}%",
            fontFamily = JetBrainsMonoFamily,
            fontSize = JawonType.Caption,
            letterSpacing = 1.4.sp,
            color = LabelDim
        )
        Text(
            text = "A disc takes a few minutes and the drive has to stay connected. Anything " +
                "already written stays where it is if you stop.",
            fontFamily = JetBrainsMonoFamily,
            fontSize = JawonType.Caption,
            lineHeight = 16.sp,
            color = LabelDim
        )
        Button("STOP", onCancel)
    }
}
