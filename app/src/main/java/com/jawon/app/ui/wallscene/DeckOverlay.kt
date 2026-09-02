package com.jawon.app.ui.wallscene

import androidx.compose.foundation.gestures.Orientation
import androidx.compose.foundation.gestures.detectHorizontalDragGestures
import androidx.compose.foundation.gestures.draggable
import androidx.compose.foundation.gestures.rememberDraggableState
import androidx.compose.foundation.gestures.scrollBy
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.rememberCoroutineScope
import kotlinx.coroutines.launch
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Bedtime
import androidx.compose.material.icons.filled.Pause
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.SkipNext
import androidx.compose.material.icons.filled.SkipPrevious
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Slider
import androidx.compose.material3.SliderDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.State
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.BiasAlignment
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.jawon.app.data.local.Track
import com.jawon.app.data.playback.EqBand
import com.jawon.app.data.playback.NativeEffect
import com.jawon.app.data.playback.NativeEffects
import com.jawon.app.ui.common.EqualizerPanel
import com.jawon.app.ui.common.formatDuration
import com.jawon.app.ui.common.qualityReadoutOutput
import com.jawon.app.ui.common.qualityReadoutSource
import com.jawon.app.playback.DjFilter
import com.jawon.app.playback.Drive
import com.jawon.app.playback.Echo
import com.jawon.app.playback.SleepTimer
import com.jawon.app.playback.Varispeed
import com.jawon.app.ui.theme.CrateEdge
import com.jawon.app.ui.theme.CrateRaised
import com.jawon.app.ui.theme.LabelCream
import com.jawon.app.ui.theme.JawonTextStyle
import com.jawon.app.ui.theme.LabelDim
import com.jawon.app.ui.theme.JetBrainsMonoFamily
import com.jawon.app.ui.theme.LampAmber
import kotlinx.coroutines.flow.StateFlow
import kotlin.math.abs
import kotlin.math.roundToInt
import androidx.compose.ui.graphics.Color as ComposeColor

/**
 * The deck, in the room: what PLAN.md §4 asks of The Deck minus the disc itself, which is
 * already there in 3D behind this.
 *
 * Sits over a scrim rather than a solid sheet so the room still reads through it, matching
 * the prototype's deck panel.
 */
/** Where a scratch has dragged playback to. Null whenever nobody is touching the disc. */
data class ScrubReadout(
    val trackIndex: Int,
    val positionMs: Long,
    val forward: Boolean
)

@Composable
fun DeckOverlay(
    title: String,
    artist: String,
    deck: DeckState,
    positionFlow: StateFlow<Long>,
    /** What the sleep timer is set to, and how long is left on a countdown. */
    sleepMode: SleepTimer.Mode,
    sleepRemainingMs: Long?,
    onSleepModeChange: (SleepTimer.Mode) -> Unit,
    /** The filter knob, −1 low-pass … 0 off … +1 high-pass. See [com.jawon.app.playback.DjFilter]. */
    filterPosition: StateFlow<Float>,
    onFilterChange: (Float) -> Unit,
    /** Tempo and key, each −1 … 0 … +1 and independent. See [com.jawon.app.playback.Varispeed]. */
    speedPosition: StateFlow<Float>,
    pitchPosition: StateFlow<Float>,
    onSpeedChange: (Float) -> Unit,
    onPitchChange: (Float) -> Unit,
    /** Saturation, 0 clean … 1 broken. See [com.jawon.app.playback.Drive]. */
    drivePosition: StateFlow<Float>,
    onDriveChange: (Float) -> Unit,
    /** The echo. Mix rests at zero: an effect you throw in starts out of the way. */
    echoTime: StateFlow<Float>,
    echoFeedback: StateFlow<Float>,
    echoMix: StateFlow<Float>,
    onEchoTime: (Float) -> Unit,
    onEchoFeedback: (Float) -> Unit,
    onEchoMix: (Float) -> Unit,
    /** The platform's own four, and which of them this phone has. See [NativeEffects]. */
    nativeAvailable: StateFlow<Set<NativeEffect>>,
    bassStrength: StateFlow<Float>,
    stereoWidth: StateFlow<Float>,
    reverbPreset: StateFlow<Int>,
    loudnessGain: StateFlow<Float>,
    onBassChange: (Float) -> Unit,
    onWidthChange: (Float) -> Unit,
    onReverbChange: (Int) -> Unit,
    onLoudnessChange: (Float) -> Unit,
    onPlayPause: () -> Unit,
    onNext: () -> Unit,
    onPrevious: () -> Unit,
    onSeek: (Long) -> Unit,
    onSelectTrack: (Int) -> Unit,
    scrubReadout: State<ScrubReadout?>,
    eqAvailable: Boolean,
    eqEnabled: Boolean,
    eqBands: List<EqBand>,
    eqRangeMb: Pair<Int, Int>,
    eqPresets: List<String>,
    onEqEnabledChange: (Boolean) -> Unit,
    onEqBandChange: (Int, Int) -> Unit,
    onEqPreset: (Int) -> Unit,
    onEqReset: () -> Unit,
    /**
     * Dragging the panel taller or shorter. Only the header does this — not the whole sheet —
     * because the tracklist and the rack both scroll vertically, and a sheet that also claimed
     * vertical drags would fight them for every gesture.
     */
    onSheetDrag: (Float) -> Unit,
    onSheetRelease: () -> Unit,
    /** How tall the panel currently is, as a fraction of the screen. See [DeckDetent]. */
    sheetFraction: Float,
    showEqualiser: Boolean,
    onShowEqualiser: (Boolean) -> Unit,
    modifier: Modifier = Modifier
) {
    // Short enough that the fixed rows no longer fit, so the optional ones come out. Sits above
    // PEEK rather than at it, so the panel sheds its extras while being dragged toward that stop
    // instead of all at once when it arrives.
    val compact = sheetFraction < COMPACT_BELOW

    val scope = rememberCoroutineScope()
    val pagerState = rememberPagerState(
        initialPage = if (showEqualiser) PAGE_SOUND else PAGE_TRACKS
    ) { PAGE_COUNT }

    // The pager and the hoisted flag have to agree without chasing each other. The flag drives
    // the pager only when they actually differ, and the pager reports back on `settledPage`
    // rather than `currentPage` — reporting mid-drag would write the destination before the
    // finger had committed to it, and the panel would resize under a gesture the user had not
    // finished making.
    LaunchedEffect(showEqualiser) {
        val target = if (showEqualiser) PAGE_SOUND else PAGE_TRACKS
        if (pagerState.currentPage != target) pagerState.animateScrollToPage(target)
    }
    LaunchedEffect(pagerState.settledPage) {
        onShowEqualiser(pagerState.settledPage == PAGE_SOUND)
    }

    Column(
        modifier = modifier
            .fillMaxWidth()
            // The scrim fades in over a fixed *screen* distance, not a fixed fraction of the
            // panel.
            //
            // A constant 0.22 was right while the panel was always a little over half the screen.
            // Dragged to full height that same fraction becomes a much taller band of near
            // transparency, and it stops sitting over the dark room and starts sitting over the
            // spinning disc — with the album title trying to be read against it. Dividing by the
            // height holds the fade at the size it was tuned to be.
            .background(
                Brush.verticalGradient(
                    0f to ComposeColor.Transparent,
                    (FADE_SCREEN_FRACTION / sheetFraction.coerceAtLeast(0.05f))
                        .coerceIn(0.08f, 0.32f) to ComposeColor(0xD1060403),
                    1f to ComposeColor(0xF7060403)
                )
            )
            // The app is edge-to-edge, so without this the tracklist and transport run
            // underneath the system navigation bar.
            .navigationBarsPadding()
            .padding(horizontal = 24.dp)
            .padding(top = 16.dp, bottom = 8.dp),
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        // The header is the deck's neutral surface: both gestures live here and nowhere else.
        //
        // *Vertically* it drags the sheet. The tracklist and the rack below both scroll
        // vertically, and a sheet claiming vertical drags anywhere on its surface would argue
        // with them over every gesture — with the winner depending on which handler gets the
        // pointer first, the ordering ENGINEERING.md's trap 6 warns is not guaranteed.
        //
        // *Horizontally* it turns the page, and that had to be added. The pager works on its own
        // content, which on the SOUND side is wall-to-wall horizontal sliders — and those quite
        // correctly win the gesture, so swiping back to the tracklist only worked from the thin
        // gaps between them. Two dots were advertising a swipe that mostly did not happen. Here
        // there is nothing to compete with, so there is always somewhere to swipe from.
        Column(
            horizontalAlignment = Alignment.CenterHorizontally,
            modifier = Modifier
                .fillMaxWidth()
                .draggable(
                    orientation = Orientation.Vertical,
                    state = rememberDraggableState { onSheetDrag(it) },
                    onDragStopped = { onSheetRelease() }
                )
                .draggable(
                    orientation = Orientation.Horizontal,
                    // Negated: dragging left moves the content left, which is the *next* page.
                    state = rememberDraggableState { delta ->
                        scope.launch { pagerState.scrollBy(-delta) }
                    },
                    // Settle to whichever page is nearest. `currentPage` already tracks that as
                    // the scroll moves, so this is the snap rather than a decision.
                    onDragStopped = {
                        scope.launch { pagerState.animateScrollToPage(pagerState.currentPage) }
                    }
                )
        ) {
            // The grab handle. Small, and the only affordance saying the panel moves at all.
            Box(
                Modifier
                    .padding(bottom = 10.dp)
                    .width(36.dp)
                    .height(3.dp)
                    .background(CrateEdge, RoundedCornerShape(2.dp))
            )
            // Always shown now, on every page.
            //
            // It used to be dropped while the equaliser was up, to buy the band sliders room
            // back when this slot was a mode you switched into. As a *page* that reads as a
            // fault: the header lurches halfway through the swipe, and the record you are
            // holding loses its name because you looked at its tone controls. The sheet is
            // draggable now, so room is something the user asks for rather than something the
            // panel takes.
            Text(
                text = title,
                style = MaterialTheme.typography.displaySmall,
                textAlign = TextAlign.Center,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
        if (!compact) {
            // Whoever is actually singing, which on a compilation is not who the shelf is filed
            // under. Falls back to the album's artist when the file carried no per-track credit —
            // including every track imported before that column existed.
            Text(
                text = (deck.currentTrack?.artist?.takeIf { it.isNotBlank() } ?: artist).uppercase(),
                style = JawonTextStyle.mono,
                modifier = Modifier.padding(top = 6.dp)
            )
            // The quality readout is the resting state of this slot; a scratch takes it over.
            // Both are drawn inside ScrubOrQuality so that the scratch's ~12Hz updates
            // recompose these two lines and nothing else (ENGINEERING.md rule 3).
            ScrubOrQuality(scrubReadout = scrubReadout, deck = deck)
        }
        }

        // Everything from here to the fader is dropped at the shortest stop.
        //
        // Not shrunk — dropped. At PEEK the panel is under a third of the screen and the fixed
        // rows alone want more than that, so laying them all out anyway pushed the transport off
        // the bottom: the one height whose whole purpose is watching the record turn was also
        // the one where play and pause had gone. What survives is the title, the progress bar and
        // the transport, which is what that height is for.
        if (!compact) {

        // Two views of the same slot: the tracklist, or the equaliser.
        //
        // The equaliser lives here as well as in Settings because reaching it there means
        // closing the record first — and an equaliser is adjusted *by ear*, which needs the
        // music still playing. A mode switch rather than a floating sheet: the panel is
        // already an overlay on a 3D scene, and stacking another one over it costs more than
        // swapping what this one shows.
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(top = 8.dp),
            horizontalArrangement = Arrangement.spacedBy(18.dp)
        ) {
            // Still tappable — a swipe is the faster way, not the only way. The labels now read
            // the pager rather than a boolean, so they track a half-finished drag instead of
            // flipping when it lands.
            DeckTab("TRACKS", selected = pagerState.currentPage == PAGE_TRACKS) {
                scope.launch { pagerState.animateScrollToPage(PAGE_TRACKS) }
            }
            // No longer gated on the platform equaliser: this page is mostly Jawon's own effects
            // now, and the filter, drive and echo run inside the player's pipeline whether or not
            // the phone offers a system equaliser at all. Hiding the whole page because one panel
            // on it was unavailable hid three that were not.
            DeckTab("SOUND", selected = pagerState.currentPage == PAGE_SOUND) {
                scope.launch { pagerState.animateScrollToPage(PAGE_SOUND) }
            }

            Box(Modifier.weight(1f))

            // Which page, as two dots. The one piece of the pager that says a second page exists
            // at all — without it a swipe is a gesture nobody knows to make.
            Row(
                horizontalArrangement = Arrangement.spacedBy(5.dp),
                verticalAlignment = Alignment.CenterVertically,
                modifier = Modifier.padding(end = 12.dp)
            ) {
                for (page in 0 until pagerState.pageCount) {
                    Box(
                        Modifier
                            .size(5.dp)
                            .background(
                                if (page == pagerState.currentPage) LampAmber else CrateEdge,
                                RoundedCornerShape(3.dp)
                            )
                    )
                }
            }

            // The sleep timer lives on the deck, not in Settings, for the same reason the
            // equaliser was moved here: it is set while a record is playing and you are
            // already lying down, and making someone close the case and cross two screens to
            // reach it is how a feature goes unused.
            SleepChip(
                mode = sleepMode,
                remainingMs = sleepRemainingMs,
                onPick = onSleepModeChange
            )
        }

        // The pages take the slack, so the controls below them stay pinned to the bottom of the
        // panel — the reachable part of a phone this tall.
        //
        // A pager rather than a swapped branch, because switching should be a gesture rather than
        // a target. It also makes room for the third page lyrics will want: pages cost a swipe
        // each, while tabs cost screen width none of which is spare.
        //
        // The horizontal drags inside these pages — the filter, the drive, the echo's three —
        // consume the pointer before the pager sees it, so a drag that starts on a control moves
        // that control and a drag that starts anywhere else turns the page. That is the ordinary
        // Compose resolution and it is the behaviour anyone expects; it is also the thing most
        // likely to feel wrong, so it is what to check first on the device.
        HorizontalPager(
            state = pagerState,
            modifier = Modifier
                .fillMaxWidth()
                .weight(1f)
        ) { page ->
        if (page == PAGE_SOUND) {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .fillMaxHeight()
                    .verticalScroll(rememberScrollState())
                    .padding(top = 10.dp)
            ) {
                // Above the equaliser, and outside its on/off switch, because this is Jawon's
                // own filter rather than the platform's — it runs inside the player's pipeline
                // and works whether or not the phone offers a system equaliser at all.
                FilterStrip(positionFlow = filterPosition, onChange = onFilterChange)

                // Above the dirt, because these two are the ones people set deliberately and
                // then leave; the filter and the drive are swept while listening.
                VarispeedModule(
                    speedFlow = speedPosition,
                    pitchFlow = pitchPosition,
                    onSpeed = onSpeedChange,
                    onPitch = onPitchChange
                )

                DriveModule(positionFlow = drivePosition, onChange = onDriveChange)

                EchoModule(
                    timeFlow = echoTime,
                    feedbackFlow = echoFeedback,
                    mixFlow = echoMix,
                    onTime = onEchoTime,
                    onFeedback = onEchoFeedback,
                    onMix = onEchoMix
                )

                NativeEffectsModule(
                    availableFlow = nativeAvailable,
                    bassFlow = bassStrength,
                    widthFlow = stereoWidth,
                    reverbFlow = reverbPreset,
                    loudnessFlow = loudnessGain,
                    onBass = onBassChange,
                    onWidth = onWidthChange,
                    onReverb = onReverbChange,
                    onLoudness = onLoudnessChange
                )

                if (eqEnabled) {
                    EqualizerPanel(
                        bands = eqBands,
                        rangeMb = eqRangeMb,
                        presets = eqPresets,
                        onBandChange = onEqBandChange,
                        onPreset = onEqPreset,
                        onReset = onEqReset,
                        compact = true
                    )
                } else {
                    // Off is a real state, and the switch belongs to the same control the
                    // settings screen owns — so this turns it on rather than pretending the
                    // equaliser is broken.
                    Text(
                        text = "The equaliser is off.",
                        style = JawonTextStyle.mono.copy(color = LabelDim, fontSize = 11.sp),
                        modifier = Modifier.padding(vertical = 8.dp)
                    )
                    Row(
                        modifier = Modifier
                            .border(1.dp, CrateEdge)
                            .clickable { onEqEnabledChange(true) }
                            .padding(horizontal = 14.dp, vertical = 8.dp)
                    ) {
                        Text(
                            "TURN IT ON",
                            fontFamily = JetBrainsMonoFamily,
                            fontWeight = FontWeight.Bold,
                            fontSize = 11.sp,
                            letterSpacing = 1.4.sp,
                            color = LampAmber
                        )
                    }
                }
            }
        } else {
            LazyColumn(
                modifier = Modifier
                    .fillMaxWidth()
                    .fillMaxHeight()
                    .padding(top = 12.dp)
            ) {
                itemsIndexed(deck.tracks) { index, track ->
                    TrackRow(
                        track = track,
                        isCurrent = index == deck.currentTrackIndex,
                        onClick = { onSelectTrack(index) }
                    )
                }
            }
        }
        }

        }

        ProgressRow(
            positionFlow = positionFlow,
            scrubReadout = scrubReadout,
            deck = deck,
            durationMs = deck.durationMs,
            onSeek = onSeek
        )

        Row(
            modifier = Modifier.padding(top = 2.dp, bottom = 4.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(28.dp)
        ) {
            IconButton(onClick = onPrevious, modifier = Modifier.size(56.dp)) {
                Icon(
                    Icons.Default.SkipPrevious,
                    contentDescription = "Previous track",
                    modifier = Modifier.size(30.dp)
                )
            }
            IconButton(onClick = onPlayPause, modifier = Modifier.size(68.dp)) {
                Icon(
                    imageVector = if (deck.isPlaying) Icons.Default.Pause else Icons.Default.PlayArrow,
                    contentDescription = if (deck.isPlaying) "Pause" else "Play",
                    tint = LampAmber,
                    modifier = Modifier.size(40.dp)
                )
            }
            IconButton(onClick = onNext, modifier = Modifier.size(56.dp)) {
                Icon(
                    Icons.Default.SkipNext,
                    contentDescription = "Next track",
                    modifier = Modifier.size(30.dp)
                )
            }
        }

    }
}

/**
 * The two-line slot under the artist: normally the quality readout, and while the disc is
 * being turned, where the scratch has dragged to.
 *
 * The `State` is read *here* rather than in the caller on purpose — that is what confines a
 * scratch's updates to this composable instead of recomposing the whole deck.
 */
@Composable
private fun ScrubOrQuality(scrubReadout: State<ScrubReadout?>, deck: DeckState) {
    val scrub = scrubReadout.value
    if (scrub != null) {
        val track = deck.tracks.getOrNull(scrub.trackIndex)
        Text(
            text = (if (scrub.forward) "▶▶  " else "◀◀  ") +
                formatDuration(scrub.positionMs) + " / " +
                formatDuration(track?.durationMs ?: 0L),
            style = JawonTextStyle.mono,
            modifier = Modifier.padding(top = 10.dp)
        )
        Text(
            text = track?.title.orEmpty().uppercase(),
            style = JawonTextStyle.mono.copy(color = LabelDim),
            modifier = Modifier.padding(top = 2.dp),
            maxLines = 1,
            overflow = TextOverflow.Ellipsis
        )
        return
    }
    deck.currentTrack?.let { track ->
        Text(
            text = qualityReadoutSource(track).uppercase(),
            style = JawonTextStyle.mono.copy(color = LabelDim),
            modifier = Modifier.padding(top = 10.dp)
        )
        Text(
            text = qualityReadoutOutput(
                track, deck.routeLabel, deck.outputSampleRateHz
            ).uppercase(),
            style = JawonTextStyle.mono.copy(color = LabelDim),
            modifier = Modifier.padding(top = 2.dp)
        )
    }
}

/**
 * Isolated so the 2Hz position tick recomposes only the bar and its two labels — the
 * tracklist above it doesn't need to redraw twice a second.
 *
 * That isolation is also why the scrub readout is read *here* rather than passed in as a
 * value: turning the disc updates it about twelve times a second, and reading it at this
 * depth keeps those updates inside this composable.
 */
@Composable
private fun ProgressRow(
    positionFlow: StateFlow<Long>,
    scrubReadout: State<ScrubReadout?>,
    deck: DeckState,
    durationMs: Long,
    onSeek: (Long) -> Unit
) {
    val position by positionFlow.collectAsState()
    var isDragging by remember { mutableStateOf(false) }
    var dragValue by remember { mutableFloatStateOf(0f) }

    // While the disc is being turned the bar follows the *scrub*, not the player.
    //
    // It used to follow `positionFlow`, which is polled at 2Hz — so spinning the platter
    // moved the disc immediately and dragged the bar along behind it in visible steps, a
    // third of a second late. The scrub cursor is the value the disc itself is drawn from,
    // so taking it here is what puts the two in sync.
    //
    // The duration comes from the scrubbed track rather than the playing one, because a
    // scrub can cross track boundaries well before the player has been told to follow.
    val scrub = scrubReadout.value
    val scrubTrack = scrub?.let { deck.tracks.getOrNull(it.trackIndex) }

    val range = (scrubTrack?.durationMs ?: durationMs).coerceAtLeast(1L).toFloat()
    val shown = when {
        scrub != null -> scrub.positionMs.toFloat().coerceIn(0f, range)
        isDragging -> dragValue
        else -> position.toFloat().coerceIn(0f, range)
    }

    Column(modifier = Modifier.fillMaxWidth()) {
        Slider(
            value = shown,
            onValueChange = {
                isDragging = true
                dragValue = it
            },
            onValueChangeFinished = {
                isDragging = false
                onSeek(dragValue.toLong())
            },
            valueRange = 0f..range,
            colors = SliderDefaults.colors(
                thumbColor = LampAmber,
                activeTrackColor = LampAmber,
                inactiveTrackColor = CrateEdge
            )
        )
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween
        ) {
            Text(formatDuration(shown.toLong()), style = JawonTextStyle.mono)
            Text(formatDuration(durationMs), style = JawonTextStyle.mono)
        }
    }
}

@Composable
private fun TrackRow(track: Track, isCurrent: Boolean, onClick: () -> Unit) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .height(42.dp)
            .clickable(onClick = onClick),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        Text(
            text = "%02d".format(track.trackNumber),
            style = JawonTextStyle.mono.copy(
                color = if (isCurrent) LampAmber else LabelDim
            )
        )
        Text(
            text = track.title,
            modifier = Modifier.weight(1f),
            style = MaterialTheme.typography.bodyMedium,
            color = if (isCurrent) LampAmber else MaterialTheme.colorScheme.onBackground,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis
        )
        Text(
            text = formatDuration(track.durationMs),
            style = JawonTextStyle.mono.copy(color = LabelDim)
        )
    }
}

/**
 * One of the deck's two views, named. Underlined when it is the one showing.
 *
 * Not a Material tab: those bring an indicator animation and a ripple that belong to a
 * different app. Two words and a rule is the whole control.
 */
@Composable
private fun DeckTab(label: String, selected: Boolean, onClick: () -> Unit) {
    Column(
        modifier = Modifier.clickable(onClick = onClick),
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        Text(
            text = label,
            fontFamily = JetBrainsMonoFamily,
            fontWeight = FontWeight.Bold,
            fontSize = 10.sp,
            letterSpacing = 2.sp,
            color = if (selected) LampAmber else LabelDim,
            modifier = Modifier.padding(bottom = 5.dp)
        )
        Box(
            Modifier
                .width(26.dp)
                .height(1.dp)
                .background(if (selected) LampAmber else ComposeColor.Transparent)
        )
    }
}

/**
 * The sleep timer, as one chip on the deck's tab row.
 *
 * Collapsed to its current state until tapped, because it is off almost always and a row of
 * five countdown lengths permanently across the deck would be five controls competing with
 * the record for attention.
 *
 * "End of record" sits at the top of the menu rather than among the minutes: it is the option
 * this app is actually recommending, and burying it under 15 / 30 / 45 would make it look
 * like an afterthought instead of the point.
 */
@Composable
private fun SleepChip(
    mode: SleepTimer.Mode,
    remainingMs: Long?,
    onPick: (SleepTimer.Mode) -> Unit
) {
    var open by remember { mutableStateOf(false) }
    val on = mode != SleepTimer.Mode.Off

    Box {
        Row(
            modifier = Modifier
                .clickable { open = true }
                .padding(vertical = 2.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Icon(
                Icons.Default.Bedtime,
                contentDescription = "Sleep timer",
                tint = if (on) LampAmber else LabelDim,
                modifier = Modifier.size(15.dp)
            )
            if (on) {
                Text(
                    text = SleepTimer.label(mode, remainingMs),
                    fontFamily = JetBrainsMonoFamily,
                    fontWeight = FontWeight.Bold,
                    fontSize = 9.sp,
                    letterSpacing = 1.2.sp,
                    color = LampAmber,
                    modifier = Modifier.padding(start = 6.dp)
                )
            }
        }

        DropdownMenu(
            expanded = open,
            onDismissRequest = { open = false },
            modifier = Modifier.background(CrateRaised)
        ) {
            SleepOption("END OF RECORD", mode == SleepTimer.Mode.EndOfAlbum) {
                onPick(SleepTimer.Mode.EndOfAlbum); open = false
            }
            SleepTimer.OFFERED_MINUTES.forEach { minutes ->
                val option = SleepTimer.Mode.After(minutes)
                SleepOption("$minutes MINUTES", mode == option) {
                    onPick(option); open = false
                }
            }
            SleepOption("OFF", mode == SleepTimer.Mode.Off) {
                onPick(SleepTimer.Mode.Off); open = false
            }
        }
    }
}

@Composable
private fun SleepOption(label: String, selected: Boolean, onClick: () -> Unit) {
    DropdownMenuItem(
        text = {
            Text(
                text = label,
                fontFamily = JetBrainsMonoFamily,
                fontSize = 11.sp,
                letterSpacing = 1.3.sp,
                color = if (selected) LampAmber else LabelCream
            )
        },
        onClick = onClick
    )
}
/**
 * The filter knob, as a strip with a centre detent.
 *
 * **Why a strip and not a rotary.** A knob is the right *object* — this is one control covering
 * two filters, which is what a mixer spends a knob on — but a rotary on a touchscreen is turned
 * by dragging in an arc your finger cannot see, and the finger covers the very thing it is
 * setting. A strip is the same single control with a travel a thumb can actually follow.
 *
 * Does **not** spring back: a filter is a state you leave a track in and walk away from, not a
 * gesture you hold open.
 */
@Composable
private fun FilterStrip(positionFlow: StateFlow<Float>, onChange: (Float) -> Unit) {
    val position by positionFlow.collectAsState()
    var width by remember { mutableFloatStateOf(1f) }
    val haptics = LocalHapticFeedback.current

    // Whether the last thing the finger did was sit in the dead zone. Kept so the detent ticks
    // once on the way in rather than buzzing continuously while the knob is parked at centre.
    var wasCentred by remember { mutableStateOf(true) }

    fun set(x: Float) {
        // −1 at the far left, +1 at the far right, 0 in the middle.
        val raw = ((x / width) * 2f - 1f).coerceIn(-1f, 1f)
        val centred = abs(raw) <= DjFilter.DEAD_ZONE
        if (centred && !wasCentred) {
            // The click you feel on a real mixer as the knob drops into the middle. This is the
            // difference between a control that is off and one you can *tell* is off without
            // looking, which is the whole reason detents exist.
            haptics.performHapticFeedback(HapticFeedbackType.TextHandleMove)
        }
        wasCentred = centred
        // Snapped, so the drawn knob agrees with the filter. The DSP already treats anything
        // inside the dead zone as bypassed; letting the marker sit visibly off-centre while the
        // sound is untouched would be the fader's mistake again.
        onChange(if (centred) 0f else raw)
    }

    Column(modifier = Modifier.fillMaxWidth().padding(bottom = 12.dp)) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween
        ) {
            Text("FILTER", style = JawonTextStyle.mono.copy(color = LabelDim, fontSize = 10.sp))
            Text(
                text = filterReadout(position),
                style = JawonTextStyle.mono.copy(
                    color = if (DjFilter.modeFor(position) == DjFilter.Mode.OFF) LabelDim else LampAmber,
                    fontSize = 10.sp
                )
            )
        }

        Box(
            modifier = Modifier
                .fillMaxWidth()
                .padding(top = 6.dp)
                .height(34.dp)
                .onSizeChanged { width = it.width.toFloat().coerceAtLeast(1f) }
                .pointerInput(Unit) {
                    detectHorizontalDragGestures(
                        onDragStart = { set(it.x) }
                    ) { change, _ -> set(change.position.x) }
                }
        ) {
            // The travel.
            Box(
                Modifier
                    .align(Alignment.Center)
                    .fillMaxWidth()
                    .height(1.dp)
                    .background(CrateEdge)
            )
            // The detent, drawn taller than the rest so the middle is findable by eye as well
            // as by feel.
            Box(
                Modifier
                    .align(Alignment.Center)
                    .width(1.dp)
                    .height(12.dp)
                    .background(LabelDim)
            )
            // How far from centre the filter is, drawn from the middle *outward* so the direction
            // reads at a glance: left is low-pass, right is high-pass.
            //
            // Two halves rather than one box with a computed alignment. The single-box version
            // put the fill on the opposite side from the handle — aligning a half-width box to
            // the end anchors it in the *right* half, then fills within that, which draws a
            // left-hand sweep on the right of the screen.
            Row(Modifier.fillMaxWidth().align(Alignment.Center)) {
                Box(Modifier.weight(1f), contentAlignment = Alignment.CenterEnd) {
                    if (position < -DjFilter.DEAD_ZONE) {
                        Box(
                            Modifier
                                .fillMaxWidth(abs(position).coerceIn(0f, 1f))
                                .height(8.dp)
                                .background(LampAmber)
                        )
                    }
                }
                Box(Modifier.weight(1f), contentAlignment = Alignment.CenterStart) {
                    if (position > DjFilter.DEAD_ZONE) {
                        Box(
                            Modifier
                                .fillMaxWidth(position.coerceIn(0f, 1f))
                                .height(8.dp)
                                .background(LampAmber)
                        )
                    }
                }
            }
            Box(
                Modifier
                    .align(BiasAlignment(horizontalBias = position.coerceIn(-1f, 1f), verticalBias = 0f))
                    .width(14.dp)
                    .height(30.dp)
                    .background(
                        if (DjFilter.modeFor(position) == DjFilter.Mode.OFF) LabelDim else LampAmber,
                        RoundedCornerShape(2.dp)
                    )
            )
        }
    }
}

/** `OFF`, `LP 4.2k` or `HP 320` — a value you can read aloud, not a nameless slider. */
private fun filterReadout(position: Float): String {
    val mode = DjFilter.modeFor(position)
    if (mode == DjFilter.Mode.OFF) return "OFF"
    val hz = DjFilter.cutoffHz(position)
    val label = if (hz >= 1000f) "${(hz / 100f).roundToInt() / 10f}k" else "${hz.roundToInt()}"
    return if (mode == DjFilter.Mode.LOW_PASS) "LP $label" else "HP $label"
}

/**
 * The echo, as three labelled slots in one module.
 *
 * MIX rests at zero and is the one you actually perform with — the gesture is throwing it in for
 * a bar and pulling it back out, which is why it sits last and reads as the loudest control here.
 * TIME and FEED are set once and left.
 */
@Composable
private fun EchoModule(
    timeFlow: StateFlow<Float>,
    feedbackFlow: StateFlow<Float>,
    mixFlow: StateFlow<Float>,
    onTime: (Float) -> Unit,
    onFeedback: (Float) -> Unit,
    onMix: (Float) -> Unit
) {
    val time by timeFlow.collectAsState()
    val feedback by feedbackFlow.collectAsState()
    val mix by mixFlow.collectAsState()

    Column(modifier = Modifier.fillMaxWidth().padding(bottom = 12.dp)) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween
        ) {
            Text("ECHO", style = JawonTextStyle.mono.copy(color = LabelDim, fontSize = 10.sp))
            Text(
                // A value you can read aloud, not a nameless slider.
                text = if (mix <= 0.001f) "OFF" else "${Echo.timeMsFor(time).roundToInt()} MS",
                style = JawonTextStyle.mono.copy(
                    color = if (mix <= 0.001f) LabelDim else LampAmber,
                    fontSize = 10.sp
                )
            )
        }
        RackSlider("TIME", time, onTime)
        RackSlider("FEED", feedback, onFeedback)
        RackSlider("MIX", mix, onMix, emphasised = true)
    }
}

/** One 0..1 control: a name, a travel and a fill. The rack's unit of measurement. */
@Composable
private fun RackSlider(
    label: String,
    value: Float,
    onChange: (Float) -> Unit,
    emphasised: Boolean = false
) {
    var width by remember { mutableFloatStateOf(1f) }
    Row(
        modifier = Modifier.fillMaxWidth().padding(top = 6.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Text(
            text = label,
            style = JawonTextStyle.mono.copy(color = LabelDim, fontSize = 9.sp),
            modifier = Modifier.width(44.dp)
        )
        Box(
            modifier = Modifier
                .weight(1f)
                .height(if (emphasised) 26.dp else 20.dp)
                .onSizeChanged { width = it.width.toFloat().coerceAtLeast(1f) }
                .pointerInput(Unit) {
                    detectHorizontalDragGestures(
                        onDragStart = { onChange((it.x / width).coerceIn(0f, 1f)) }
                    ) { change, _ -> onChange((change.position.x / width).coerceIn(0f, 1f)) }
                }
        ) {
            Box(
                Modifier
                    .align(Alignment.CenterStart)
                    .fillMaxWidth()
                    .height(1.dp)
                    .background(CrateEdge)
            )
            if (value > 0.001f) {
                Box(
                    Modifier
                        .align(Alignment.CenterStart)
                        .fillMaxWidth(value.coerceIn(0f, 1f))
                        .height(if (emphasised) 8.dp else 5.dp)
                        .background(if (emphasised) LampAmber else LabelDim)
                )
            }
            Box(
                Modifier
                    .align(BiasAlignment(horizontalBias = value.coerceIn(0f, 1f) * 2f - 1f, verticalBias = 0f))
                    .width(10.dp)
                    .height(if (emphasised) 22.dp else 16.dp)
                    .background(
                        if (emphasised && value > 0.001f) LampAmber else LabelDim,
                        RoundedCornerShape(2.dp)
                    )
            )
        }
    }
}

/**
 * Saturation, as one control.
 *
 * One knob rather than the usual drive-plus-output pair, because the output half is not a
 * decision anyone wants to make — it exists only to stop the drive knob doubling as a volume
 * knob, and [Drive] handles that itself. Leaving it out is the same constraint the filter
 * applies: one control, doing the whole job.
 */
@Composable
private fun DriveModule(positionFlow: StateFlow<Float>, onChange: (Float) -> Unit) {
    val position by positionFlow.collectAsState()
    Column(modifier = Modifier.fillMaxWidth().padding(bottom = 12.dp)) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween
        ) {
            Text("DRIVE", style = JawonTextStyle.mono.copy(color = LabelDim, fontSize = 10.sp))
            Text(
                text = if (position <= Drive.SILENT) "CLEAN"
                else "×${"%.1f".format(Drive.driveFor(position))}",
                style = JawonTextStyle.mono.copy(
                    color = if (position <= Drive.SILENT) LabelDim else LampAmber,
                    fontSize = 10.sp
                )
            )
        }
        RackSlider("AMOUNT", position, onChange, emphasised = true)
    }
}

/**
 * How far down the *screen* the deck's scrim finishes fading in.
 *
 * Derived rather than picked: 0.22 of a panel that was 0.575 of the screen, which is the value
 * the gradient was tuned at before the panel could be dragged.
 */
private const val FADE_SCREEN_FRACTION = 0.22f * 0.575f

/**
 * Below this panel height the deck sheds everything but the record's identity and its transport.
 *
 * Set between PEEK and DECK rather than at PEEK exactly, so the change happens *during* the drag
 * toward the short stop rather than snapping at the end of it — a panel that reorganises itself
 * on arrival reads as a glitch, while one that does it under the finger reads as a response.
 */
private const val COMPACT_BELOW = 0.44f

/**
 * The deck's pages.
 *
 * Two for now. Lyrics are the third, and are a *view* rather than a control — which is why they
 * belong here and not as another module crammed into the rack.
 */
private const val PAGE_TRACKS = 0
private const val PAGE_SOUND = 1
private const val PAGE_COUNT = 2

/**
 * Tempo and key, as a pair of centre-detented strips.
 *
 * They sit together because their whole point is being apart: a record deck has one control
 * here, and seeing two is the fastest way to understand that this is not that. Big numeric
 * readouts because these are the two values people actually aim at rather than sweep.
 */
@Composable
private fun VarispeedModule(
    speedFlow: StateFlow<Float>,
    pitchFlow: StateFlow<Float>,
    onSpeed: (Float) -> Unit,
    onPitch: (Float) -> Unit
) {
    val speed by speedFlow.collectAsState()
    val pitch by pitchFlow.collectAsState()

    Column(modifier = Modifier.fillMaxWidth().padding(bottom = 12.dp)) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween
        ) {
            Text("SPEED", style = JawonTextStyle.mono.copy(color = LabelDim, fontSize = 10.sp))
            Text(
                text = if (Varispeed.isNeutral(speed)) "NORMAL"
                else "%.2f".format(Varispeed.speedFor(speed)) + "\u00D7",
                style = JawonTextStyle.mono.copy(
                    color = if (Varispeed.isNeutral(speed)) LabelDim else LampAmber,
                    fontSize = 10.sp
                )
            )
        }
        CentredStrip(speed, onSpeed)

        Row(
            modifier = Modifier.fillMaxWidth().padding(top = 10.dp),
            horizontalArrangement = Arrangement.SpaceBetween
        ) {
            Text("PITCH", style = JawonTextStyle.mono.copy(color = LabelDim, fontSize = 10.sp))
            Text(
                text = if (Varispeed.isNeutral(pitch)) "NORMAL"
                else "%+.1f ST".format(Varispeed.semitonesFor(pitch)),
                style = JawonTextStyle.mono.copy(
                    color = if (Varispeed.isNeutral(pitch)) LabelDim else LampAmber,
                    fontSize = 10.sp
                )
            )
        }
        CentredStrip(pitch, onPitch)
    }
}

/**
 * A −1…0…+1 strip with a detent in the middle.
 *
 * Shared by speed and pitch, and shaped like [FilterStrip] on purpose: three controls that all
 * rest at centre should be operated the same way, and a user who learns one has learned them.
 */
@Composable
private fun CentredStrip(position: Float, onChange: (Float) -> Unit) {
    var width by remember { mutableFloatStateOf(1f) }
    val haptics = LocalHapticFeedback.current
    var wasCentred by remember { mutableStateOf(true) }

    fun set(x: Float) {
        val raw = ((x / width) * 2f - 1f).coerceIn(-1f, 1f)
        val centred = Varispeed.isNeutral(raw)
        if (centred && !wasCentred) haptics.performHapticFeedback(HapticFeedbackType.TextHandleMove)
        wasCentred = centred
        onChange(if (centred) 0f else raw)
    }

    Box(
        modifier = Modifier
            .fillMaxWidth()
            .padding(top = 4.dp)
            .height(28.dp)
            .onSizeChanged { width = it.width.toFloat().coerceAtLeast(1f) }
            .pointerInput(Unit) {
                detectHorizontalDragGestures(
                    onDragStart = { set(it.x) }
                ) { change, _ -> set(change.position.x) }
            }
    ) {
        Box(
            Modifier
                .align(Alignment.Center)
                .fillMaxWidth()
                .height(1.dp)
                .background(CrateEdge)
        )
        Box(
            Modifier
                .align(Alignment.Center)
                .width(1.dp)
                .height(10.dp)
                .background(LabelDim)
        )
        Row(Modifier.fillMaxWidth().align(Alignment.Center)) {
            Box(Modifier.weight(1f), contentAlignment = Alignment.CenterEnd) {
                if (position < -Varispeed.DEAD_ZONE) {
                    Box(
                        Modifier
                            .fillMaxWidth(abs(position).coerceIn(0f, 1f))
                            .height(6.dp)
                            .background(LampAmber)
                    )
                }
            }
            Box(Modifier.weight(1f), contentAlignment = Alignment.CenterStart) {
                if (position > Varispeed.DEAD_ZONE) {
                    Box(
                        Modifier
                            .fillMaxWidth(position.coerceIn(0f, 1f))
                            .height(6.dp)
                            .background(LampAmber)
                    )
                }
            }
        }
        Box(
            Modifier
                .align(BiasAlignment(horizontalBias = position.coerceIn(-1f, 1f), verticalBias = 0f))
                .width(12.dp)
                .height(24.dp)
                .background(
                    if (Varispeed.isNeutral(position)) LabelDim else LampAmber,
                    RoundedCornerShape(2.dp)
                )
        )
    }
}

/**
 * The four effects the platform supplies, drawn only where the phone actually has them.
 *
 * **An absent module is absent, not disabled.** Vendor support for these varies, and a greyed
 * row saying "your phone cannot do this" is a row of screen spent on bad news. If a phone has
 * none of them the whole block disappears and the rack is Jawon's own effects, which still
 * work — they run inside the player's pipeline rather than on the audio session.
 */
@Composable
private fun NativeEffectsModule(
    availableFlow: StateFlow<Set<NativeEffect>>,
    bassFlow: StateFlow<Float>,
    widthFlow: StateFlow<Float>,
    reverbFlow: StateFlow<Int>,
    loudnessFlow: StateFlow<Float>,
    onBass: (Float) -> Unit,
    onWidth: (Float) -> Unit,
    onReverb: (Int) -> Unit,
    onLoudness: (Float) -> Unit
) {
    val available by availableFlow.collectAsState()
    if (available.isEmpty()) return

    val bass by bassFlow.collectAsState()
    val width by widthFlow.collectAsState()
    val reverb by reverbFlow.collectAsState()
    val loudness by loudnessFlow.collectAsState()

    Column(modifier = Modifier.fillMaxWidth().padding(bottom = 12.dp)) {
        Text(
            "SPACE",
            style = JawonTextStyle.mono.copy(color = LabelDim, fontSize = 10.sp),
            modifier = Modifier.padding(bottom = 2.dp)
        )

        if (NativeEffect.REVERB in available) {
            // A row of names rather than a slider: the platform offers rooms, not a dial, and
            // pretending otherwise would put seven discrete values under a continuous control.
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .horizontalScroll(rememberScrollState())
                    .padding(vertical = 4.dp),
                horizontalArrangement = Arrangement.spacedBy(6.dp)
            ) {
                NativeEffects.REVERB_PRESETS.forEachIndexed { index, name ->
                    val selected = index == reverb
                    Text(
                        text = name,
                        fontFamily = JetBrainsMonoFamily,
                        fontWeight = FontWeight.Bold,
                        fontSize = 9.sp,
                        letterSpacing = 1.1.sp,
                        color = if (selected) LampAmber else LabelDim,
                        modifier = Modifier
                            .border(1.dp, if (selected) LampAmber else CrateEdge)
                            .clickable { onReverb(index) }
                            .padding(horizontal = 8.dp, vertical = 5.dp)
                    )
                }
            }
        }

        if (NativeEffect.BASS in available) RackSlider("BASS", bass, onBass)
        if (NativeEffect.WIDTH in available) RackSlider("WIDTH", width, onWidth)
        if (NativeEffect.LOUDNESS in available) RackSlider("LOUD", loudness, onLoudness)
    }
}
