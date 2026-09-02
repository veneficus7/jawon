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
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.rememberLazyListState
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
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.Spring
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.withFrameMillis
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.TransformOrigin
import androidx.compose.ui.graphics.lerp
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
import com.jawon.app.data.lyrics.Lyrics
import com.jawon.app.data.lyrics.LyricSource
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
import com.jawon.app.playback.RackChannel
import com.jawon.app.playback.SleepTimer
import com.jawon.app.playback.Varispeed
import com.jawon.app.ui.theme.CrateEdge
import com.jawon.app.ui.theme.CrateRaised
import com.jawon.app.ui.theme.LabelCream
import com.jawon.app.ui.theme.JawonTextStyle
import com.jawon.app.ui.theme.LabelDim
import com.jawon.app.ui.theme.JetBrainsMonoFamily
import com.jawon.app.ui.theme.DeckFinish
import com.jawon.app.ui.theme.LampAmber
import com.jawon.app.ui.theme.LocalDeckFinish
import com.jawon.app.ui.theme.deckPlate
import kotlinx.coroutines.flow.StateFlow
import kotlin.math.abs
import kotlin.math.roundToInt
import androidx.compose.ui.graphics.Color
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
    onPitchCommit: () -> Unit,
    /** The current track's words, for the third page. */
    lyrics: StateFlow<Lyrics?>,
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
    /** Which of the eight cells on the rack are switched in. See [RackGrid]. */
    engagedChannels: StateFlow<Set<RackChannel>>,
    onChannelToggle: (RackChannel) -> Unit,
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
    /**
     * Which page the deck is on, hoisted so the panel's height can react to it.
     *
     * **An index rather than the boolean this used to be.** While there were two pages, "is the
     * rack showing" held all the state there was. A third page broke it in a way that looked
     * like a haunting: landing on LYRICS made the flag read false — because false meant "not
     * SOUND" — which the effect below then obeyed by driving the pager back to TRACKS. The page
     * opened and bounced. Two-state flags cannot count to three.
     */
    deckPage: Int,
    onDeckPageChange: (Int) -> Unit,
    modifier: Modifier = Modifier
) {
    // Short enough that the fixed rows no longer fit, so the optional ones come out. Sits above
    // PEEK rather than at it, so the panel sheds its extras while being dragged toward that stop
    // instead of all at once when it arrives.
    val compact = sheetFraction < COMPACT_BELOW

    val scope = rememberCoroutineScope()
    val pagerState = rememberPagerState(initialPage = deckPage) { PAGE_COUNT }

    /**
     * Which cell the rack's faders currently belong to.
     *
     * Local, and deliberately not hoisted: it is a property of looking at this page rather than
     * of the app, and forgetting it when the case closes is the right behaviour — the next record
     * should open on its own tracklist with nothing already picked out.
     */
    var selectedCell by remember { mutableStateOf<RackChannel?>(null) }

    /** What the deck is made of. See [DeckFinish]. */
    val finish = LocalDeckFinish.current

    // Arriving at the rack with nothing selected means three empty fader slots and no way to
    // guess that tapping a key fills them. Picking the first one is not a decision on the
    // user's behalf — nothing is switched in or out by it — it just means the page opens
    // showing what it is for.
    LaunchedEffect(pagerState.currentPage) {
        if (pagerState.currentPage == PAGE_SOUND && selectedCell == null) {
            selectedCell = RackChannel.FILTER
        }
    }

    // The pager and the hoisted page have to agree without chasing each other. The hoisted value
    // drives the pager only when they actually differ, and the pager reports back on
    // `settledPage` rather than `currentPage` — reporting mid-drag would write the destination
    // before the finger had committed to it, and the panel would resize under a gesture the user
    // had not finished making.
    LaunchedEffect(deckPage) {
        if (pagerState.currentPage != deckPage) pagerState.animateScrollToPage(deckPage)
    }
    LaunchedEffect(pagerState.settledPage) {
        onDeckPageChange(pagerState.settledPage)
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
            .deckPlate(finish, sheetFraction)
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
                    .background(finish.plateEdge, RoundedCornerShape(2.dp))
            )
            // Always shown now, on every page.
            //
            // It used to be dropped while the equaliser was up, to buy the band sliders room
            // back when this slot was a mode you switched into. As a *page* that reads as a
            // fault: the header lurches halfway through the swipe, and the record you are
            // holding loses its name because you looked at its tone controls. The sheet is
            // draggable now, so room is something the user asks for rather than something the
            // panel takes.
            // The *track*, not the record.
            //
            // The album's name is on the spine, on the front of the case, and on the shelf label
            // — you have just chosen it, and it is still behind this panel. What you cannot see
            // anywhere else is which of its tracks is playing, and that is what a deck is for.
            // Falls back to the album for the moment before a track is loaded.
            DeckWindow(
                title = title,
                artist = artist,
                deck = deck,
                positionFlow = positionFlow,
                scrubReadout = scrubReadout,
                compact = compact,
                rackStatus = if (pagerState.currentPage == PAGE_SOUND) {
                    (selectedCell?.label ?: "--") + "  " + engagedChannels.collectAsState().value.size + " IN"
                } else {
                    null
                }
            )
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
            DeckTab("LYRICS", selected = pagerState.currentPage == PAGE_LYRICS) {
                scope.launch { pagerState.animateScrollToPage(PAGE_LYRICS) }
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
                                if (page == pagerState.currentPage) finish.accent else finish.plateEdge,
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
            // **Layout C: eight cells you switch in and out, over three faders that follow.**
            //
            // Effects first, parameters second — the order you use them in when something is
            // already playing. Tapping a cell throws the effect in and selects it; the faders
            // underneath then belong to whichever cell is selected. The old stack of full-width
            // sliders is gone rather than hidden: it drew several of these controls a second
            // time, which is why the page read as duplicated.
            //
            // No scroll. Everything the rack can do is on this one face, which is the whole
            // point of the layout.
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .fillMaxHeight()
                    .padding(top = 8.dp),
                // Centred rather than hung from the top: the rack is a fixed-size object, and
                // whatever height is left over should sit evenly around it instead of pooling
                // underneath as a lake of empty plate.
                verticalArrangement = Arrangement.Center
            ) {
                RackGrid(
                    cells = rackCells(
                        filter = filterPosition.collectAsState().value,
                        speed = speedPosition.collectAsState().value,
                        pitch = pitchPosition.collectAsState().value,
                        drive = drivePosition.collectAsState().value,
                        echoTime = echoTime.collectAsState().value,
                        echoFeedback = echoFeedback.collectAsState().value,
                        echoMix = echoMix.collectAsState().value,
                        reverbPreset = reverbPreset.collectAsState().value,
                        loudness = loudnessGain.collectAsState().value,
                        bass = bassStrength.collectAsState().value,
                        width = stereoWidth.collectAsState().value,
                        available = nativeAvailable.collectAsState().value,
                        onFilter = onFilterChange,
                        onSpeed = onSpeedChange,
                        onPitch = onPitchChange,
                        onPitchCommit = onPitchCommit,
                        onDrive = onDriveChange,
                        onEchoTime = onEchoTime,
                        onEchoFeedback = onEchoFeedback,
                        onEchoMix = onEchoMix,
                        onReverb = onReverbChange,
                        onLoudness = onLoudnessChange,
                        onBass = onBassChange,
                        onWidth = onWidthChange
                    ),
                    engaged = engagedChannels.collectAsState().value,
                    selected = selectedCell,
                    onToggle = onChannelToggle,
                    onSelect = { selectedCell = if (selectedCell == it) null else it }
                ) {
                    if (eqAvailable) {
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
                        Text(
                            text = "This phone has no equaliser.",
                            style = JawonTextStyle.mono.copy(color = finish.plateDim, fontSize = 10.sp),
                            modifier = Modifier.padding(vertical = 10.dp)
                        )
                    }
                }
            }
        } else if (page == PAGE_LYRICS) {
            LyricsPage(
                lyricsFlow = lyrics,
                positionFlow = positionFlow,
                // A pager composes its neighbours, so this page is alive while you are reading
                // the tracklist next door — and its frame callback would be advancing a
                // playhead for words nobody is looking at. Only the page you are actually on
                // gets to hold a frame loop open.
                isPlaying = deck.isPlaying && pagerState.currentPage == PAGE_LYRICS
            )
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
                    tint = finish.plateInk,
                    modifier = Modifier.size(30.dp)
                )
            }
            IconButton(onClick = onPlayPause, modifier = Modifier.size(68.dp)) {
                Icon(
                    imageVector = if (deck.isPlaying) Icons.Default.Pause else Icons.Default.PlayArrow,
                    contentDescription = if (deck.isPlaying) "Pause" else "Play",
                    tint = finish.accent,
                    modifier = Modifier.size(40.dp)
                )
            }
            IconButton(onClick = onNext, modifier = Modifier.size(56.dp)) {
                Icon(
                    Icons.Default.SkipNext,
                    contentDescription = "Next track",
                    tint = finish.plateInk,
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
    val finish = LocalDeckFinish.current
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
            style = JawonTextStyle.mono.copy(color = finish.plateDim),
            modifier = Modifier.padding(top = 2.dp),
            maxLines = 1,
            overflow = TextOverflow.Ellipsis
        )
        return
    }
    deck.currentTrack?.let { track ->
        Text(
            text = qualityReadoutSource(track).uppercase(),
            style = JawonTextStyle.mono.copy(color = finish.plateDim),
            modifier = Modifier.padding(top = 10.dp)
        )
        Text(
            text = qualityReadoutOutput(
                track, deck.routeLabel, deck.outputSampleRateHz
            ).uppercase(),
            style = JawonTextStyle.mono.copy(color = finish.plateDim),
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
    val finish = LocalDeckFinish.current
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
                thumbColor = finish.accent,
                activeTrackColor = finish.accent,
                inactiveTrackColor = finish.plateEdge
            )
        )
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween
        ) {
            // Quiet, because the window above already carries the live time in the glass —
            // this row is the scale for the bar, not the readout.
            Text(
                formatDuration(shown.toLong()),
                style = JawonTextStyle.mono.copy(color = finish.plateDim)
            )
            Text(
                formatDuration(durationMs),
                style = JawonTextStyle.mono.copy(color = finish.plateDim)
            )
        }
    }
}

@Composable
private fun TrackRow(track: Track, isCurrent: Boolean, onClick: () -> Unit) {
    val finish = LocalDeckFinish.current
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
                color = if (isCurrent) finish.accent else finish.plateDim
            )
        )
        Text(
            text = track.title,
            modifier = Modifier.weight(1f),
            style = MaterialTheme.typography.bodyMedium,
            color = if (isCurrent) finish.accent else finish.plateInk,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis
        )
        Text(
            text = formatDuration(track.durationMs),
            style = JawonTextStyle.mono.copy(color = finish.plateDim)
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
    val finish = LocalDeckFinish.current
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
            color = if (selected) finish.accent else finish.plateDim,
            modifier = Modifier.padding(bottom = 5.dp)
        )
        Box(
            Modifier
                .width(26.dp)
                .height(1.dp)
                .background(if (selected) finish.accent else ComposeColor.Transparent)
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
    val finish = LocalDeckFinish.current
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
                tint = if (on) finish.accent else finish.plateDim,
                modifier = Modifier.size(15.dp)
            )
            if (on) {
                Text(
                    text = SleepTimer.label(mode, remainingMs),
                    fontFamily = JetBrainsMonoFamily,
                    fontWeight = FontWeight.Bold,
                    fontSize = 9.sp,
                    letterSpacing = 1.2.sp,
                    color = finish.accent,
                    modifier = Modifier.padding(start = 6.dp)
                )
            }
        }

        DropdownMenu(
            expanded = open,
            onDismissRequest = { open = false },
            modifier = Modifier.background(finish.key)
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
    val finish = LocalDeckFinish.current
    DropdownMenuItem(
        text = {
            Text(
                text = label,
                fontFamily = JetBrainsMonoFamily,
                fontSize = 11.sp,
                letterSpacing = 1.3.sp,
                color = if (selected) finish.accent else finish.plateInk
            )
        },
        onClick = onClick
    )
}

/** `OFF`, `LP 4.2k` or `HP 320` — a value you can read aloud, not a nameless slider. */


/** One 0..1 control: a name, a travel and a fill. The rack's unit of measurement. */


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
 * Three: the tracklist, the rack, and the lyrics — a *view* rather than a control, which is why
 * they are a page here and not another module crammed into the rack.
 *
 * `internal` rather than private because the screen hoists the current page, and the panel's
 * height depends on which one it is.
 */
internal const val PAGE_TRACKS = 0
internal const val PAGE_SOUND = 1
internal const val PAGE_LYRICS = 2
internal const val PAGE_COUNT = 3




/**
 * The words, as a page rather than a control.
 *
 * **Why lyrics get a page and not a panel.** Everything else in this deck is something you
 * operate; this is something you read, and reading wants the whole width and as much height as
 * the sheet has been dragged to. Cramming it in beside the tracklist would make both worse.
 *
 * Timed lyrics scroll themselves and light the current line. Untimed ones are a page you scroll
 * yourself — which is the common case offline, because the tag format that carries timing is
 * written by almost nothing. The source is named at the top for exactly that reason: it explains
 * why one record follows along and the next does not.
 */
@Composable
private fun LyricsPage(
    lyricsFlow: StateFlow<Lyrics?>,
    positionFlow: StateFlow<Long>,
    isPlaying: Boolean
) {
    val finish = LocalDeckFinish.current
    val lyrics by lyricsFlow.collectAsState()
    val words = lyrics

    if (words == null || words.isEmpty) {
        Box(Modifier.fillMaxWidth().fillMaxHeight(), contentAlignment = Alignment.Center) {
            Text(
                "NO LYRICS FOR THIS TRACK",
                style = JawonTextStyle.mono.copy(color = finish.plateDim, fontSize = 11.sp)
            )
        }
        return
    }

    val sampled by positionFlow.collectAsState()
    val position = rememberSmoothPosition(sampled, isPlaying && words.synced)

    // **Derived, so the page recomposes once per line rather than once per frame.**
    //
    // The position underneath this advances every frame, and reading it directly in the body
    // meant the entire lyric list recomposed sixty times a second to discover that the answer
    // was usually the same integer — measured at about eight percent of a core, spent almost
    // entirely on nothing. `derivedStateOf` reads the frame-rate value and publishes only the
    // line index, so a recomposition happens when the sung line actually changes.
    val current by remember(words) { derivedStateOf { words.lineAt(position.value) } }
    val listState = rememberLazyListState()

    // Keep the sung line a third of the way down rather than at the top: a lyric sheet reads
    // better with what is coming next already on screen. Two lines above it is close enough at
    // every sheet height, and cheaper than measuring the viewport every frame.
    // Keyed on the words as well as the line, so a new track starts at the top instead of
    // inheriting wherever the last one had scrolled to. Before the first stamp there is no line
    // to follow, and the top is the honest place to be.
    LaunchedEffect(words, current) {
        runCatching {
            if (current >= 0) {
                listState.animateScrollToItem((current - 2).coerceAtLeast(0))
            } else {
                listState.scrollToItem(0)
            }
        }
    }

    Column(Modifier.fillMaxWidth().fillMaxHeight()) {
        Text(
            text = when {
                words.synced -> "SYNCED · ${sourceLabel(words.source)}"
                else -> sourceLabel(words.source)
            },
            style = JawonTextStyle.mono.copy(color = finish.plateDim, fontSize = 9.sp),
            modifier = Modifier.padding(top = 8.dp, bottom = 6.dp)
        )
        LazyColumn(state = listState, modifier = Modifier.fillMaxWidth().fillMaxHeight()) {
            itemsIndexed(words.lines) { index, line ->
                val sung = words.synced && index == current

                // Animated rather than switched, and both at once: a line that only changes
                // colour reads as a highlight moving down a list, while one that also grows
                // reads as the words being *sung*. That is most of the difference between a
                // lyric sheet and a karaoke machine.
                val emphasis by animateFloatAsState(
                    targetValue = if (sung) 1f else 0f,
                    animationSpec = spring(
                        dampingRatio = 0.85f,
                        stiffness = Spring.StiffnessMediumLow
                    ),
                    label = "lyricEmphasis"
                )

                Text(
                    text = line.text,
                    style = MaterialTheme.typography.bodyLarge.copy(
                        // Interpolated rather than picked, so a line brightens as it grows
                        // instead of snapping at the moment the scale animation starts.
                        color = lerp(finish.plateDim, finish.plateInk, emphasis),
                        fontWeight = if (sung) FontWeight.Bold else FontWeight.Normal,
                        fontSize = if (words.synced) 17.sp else 15.sp
                    ),
                    modifier = Modifier
                        .graphicsLayer {
                            val scale = 1f + 0.07f * emphasis
                            scaleX = scale
                            scaleY = scale
                            // Grown from the left edge. Scaling about the centre slides every
                            // line sideways as it lights up, which reads as a wobble rather
                            // than as emphasis.
                            transformOrigin = TransformOrigin(0f, 0.5f)
                        }
                        .padding(vertical = if (words.synced) 5.dp else 3.dp)
                )
            }
        }
    }
}

/**
 * The playhead, interpolated between the twice-a-second samples the player actually publishes.
 *
 * **This is what stands between the lyrics and Apple Music, and no amount of animation fixes
 * it.** `PlaybackRepository` polls the position every 500ms, which is right for a progress bar
 * — it moves a pixel — and hopeless for lyrics, where it means a line can light up anywhere up
 * to half a second after it is sung. On a fast verse that is one or two whole lines late.
 *
 * Polling faster is the wrong answer: it would cost an IPC per tick and recompose the deck at
 * whatever rate was chosen. Instead each sample becomes an anchor, and the frame clock carries
 * the position forward from it — free, because Compose is already ticking, and exact between
 * anchors because a playhead advancing in real time is precisely what wall-clock milliseconds
 * measure.
 *
 * Only while playing. Paused, the last sample *is* the position.
 */
@Composable
private fun rememberSmoothPosition(sampled: Long, isPlaying: Boolean): State<Long> =
    produceState(sampled, sampled, isPlaying) {
        // Paused, or unsynced words that have nothing to follow: the sample *is* the position,
        // and there is no reason to hold a frame callback open.
        if (!isPlaying) {
            value = sampled
            return@produceState
        }
        var anchorFrame = 0L
        withFrameMillis { anchorFrame = it }
        while (true) {
            withFrameMillis { frame ->
                // A small step backwards is the extrapolation and the player disagreeing about
                // the same moment; a large one is a seek or a new track. See Playhead.
                value = Playhead.next(value, sampled + (frame - anchorFrame))
            }
        }
    }

private fun sourceLabel(source: LyricSource): String = when (source) {
    LyricSource.EMBEDDED -> "FROM THE FILE'S TAG"
    LyricSource.SIDECAR -> "FROM AN .LRC BESIDE IT"
    LyricSource.ONLINE -> "FROM LRCLIB"
}

/**
 * The deck's window: what a machine shows you about the disc it is holding.
 *
 * **The title went inside the glass, and that is the whole idea.** It used to be a large piece of
 * display type floating on the panel, which reads as an app; a record player puts it behind a
 * window with the time beside it, and once the title is in there the panel stops being a screen
 * and starts being a front. It also fixes a real problem — a long track name was truncated at
 * about fourteen characters at display size, and at window size it fits.
 *
 * Every finish gets one, because every finish is a machine. What changes is the glass: amber on
 * near-black for the shop, a cyan vacuum-fluorescent tube for the component, four-pixel green for
 * the desktop player.
 */
@Composable
private fun DeckWindow(
    title: String,
    artist: String,
    deck: DeckState,
    positionFlow: StateFlow<Long>,
    scrubReadout: State<ScrubReadout?>,
    compact: Boolean,
    /**
     * What the rack is doing, when the rack is what you are looking at.
     *
     * Takes the place of the track number, because a component has one window and the rack used
     * to open a second one directly beneath this. Null on the other pages, where the track
     * number is the more useful thing to know.
     */
    rackStatus: String?
) {
    val finish = LocalDeckFinish.current

    Column(modifier = Modifier.fillMaxWidth()) {

        // Silkscreen. Costs one line and does more for the illusion than anything else on the
        // panel — real machines are covered in small print nobody reads.
        if (!compact) {
            Row(
                modifier = Modifier.fillMaxWidth().padding(bottom = 6.dp),
                horizontalArrangement = Arrangement.SpaceBetween
            ) {
                Text(
                    text = finish.brand,
                    fontFamily = JetBrainsMonoFamily,
                    fontSize = 8.sp,
                    fontWeight = FontWeight.Bold,
                    letterSpacing = 3.sp,
                    color = finish.plateDim
                )
                if (finish.model.isNotBlank()) {
                    Text(
                        text = finish.model,
                        fontFamily = JetBrainsMonoFamily,
                        fontSize = 8.sp,
                        letterSpacing = 2.sp,
                        color = finish.plateDim
                    )
                }
            }
        }

        Box(modifier = Modifier.fillMaxWidth()) {

            // **The window bleeds onto the plate around it.**
            //
            // A lit sign in a dark room does not stop at its own frame, and how far it spills is
            // most of what tells two modes apart: the bar mode bleeds all over the panel, the
            // workbench does not bleed at all and its halo is simply absent. Drawn as a soft
            // radial wash rather than a blur, because the only real blur Android offers needs
            // API 31 and this app runs from 28.
            if (finish.glow > 0f) {
                // **Breathing, and driven from the draw phase.**
                //
                // A sign in a bar hums rather than sits still, and a glow that never moves
                // reads as a gradient someone painted on. Read inside `graphicsLayer`, so the
                // sixty-times-a-second value never recomposes anything — it only redraws one
                // layer. ENGINEERING.md rule 3: high-frequency state must not drive recomposition.
                val breath = rememberInfiniteTransition(label = "glow").animateFloat(
                    initialValue = BREATH_LOW,
                    targetValue = 1f,
                    animationSpec = infiniteRepeatable(
                        animation = tween(BREATH_MS, easing = FastOutSlowInEasing),
                        repeatMode = RepeatMode.Reverse
                    ),
                    label = "breath"
                )
                Box(
                    modifier = Modifier
                        .matchParentSize()
                        // Scaled rather than inset with a negative padding, which Compose
                        // refuses outright — it throws "Padding must be non-negative" and takes
                        // the whole app down on the first frame. Scaling spills the wash past
                        // the window without moving anything around it.
                        .graphicsLayer {
                            scaleY = HALO_SPREAD_Y
                            scaleX = HALO_SPREAD_X
                            alpha = breath.value
                        }
                        .background(
                            Brush.radialGradient(
                                0f to finish.displayInk.copy(alpha = HALO_ALPHA * finish.glow),
                                0.55f to finish.displayInk.copy(alpha = HALO_ALPHA * 0.35f * finish.glow),
                                1f to Color.Transparent
                            )
                        )
                )
            }

        Row(
            modifier = Modifier
                .fillMaxWidth()
                .background(finish.window, RoundedCornerShape(finish.windowRadius))
                .border(1.dp, finish.windowEdge, RoundedCornerShape(finish.windowRadius))
                .padding(horizontal = 12.dp, vertical = 9.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Column(modifier = Modifier.weight(1f)) {
                // The *track*, not the record. The album's name is on the spine, on the front of
                // the case and on the shelf label — you just chose it. What you cannot see
                // anywhere else is which of its tracks is playing, and that is what a deck is for.
                DisplayText(
                    text = (deck.currentTrack?.title?.takeIf { it.isNotBlank() } ?: title)
                        .uppercase(),
                    size = finish.displaySize,
                    weight = FontWeight.Bold
                )
                if (!compact) {
                    // Whoever is actually singing, which on a compilation is not who the shelf is
                    // filed under. Falls back to the album's artist when the file carried no
                    // per-track credit.
                    DisplayText(
                        text = (deck.currentTrack?.artist?.takeIf { it.isNotBlank() } ?: artist)
                            .uppercase(),
                        size = finish.displaySize * SECONDARY_SCALE,
                        weight = FontWeight.Normal,
                        dim = true
                    )
                }
            }

            // Its own composable so the twice-a-second tick recomposes the clock and nothing
            // else — the panel around it holds still. ENGINEERING.md rule 3.
            WindowClock(
                positionFlow = positionFlow,
                deck = deck,
                scrubReadout = scrubReadout,
                compact = compact,
                rackStatus = rackStatus
            )
        }
        }

        if (!compact) {
            // What the file actually is, in plate ink rather than in the glass: it is a property
            // of the record, not something the machine is doing.
            ScrubOrQuality(scrubReadout = scrubReadout, deck = deck)
        }
    }
}

/** The clock, alone, so the panel does not recompose twice a second with it. */
@Composable
private fun WindowClock(
    positionFlow: StateFlow<Long>,
    deck: DeckState,
    scrubReadout: State<ScrubReadout?>,
    compact: Boolean,
    rackStatus: String?
) {
    val finish = LocalDeckFinish.current
    val position by positionFlow.collectAsState()
    val scrub = scrubReadout.value

    // The gap matters: without it a long track name runs straight into the clock and the two
    // read as one string.
    Column(
        horizontalAlignment = Alignment.End,
        modifier = Modifier.padding(start = 14.dp)
    ) {
        DisplayText(
            text = formatDuration(scrub?.positionMs ?: position),
            size = finish.displaySize,
            weight = FontWeight.Bold
        )
        if (!compact) {
            val index = (scrub?.trackIndex ?: deck.currentTrackIndex) + 1
            DisplayText(
                text = rackStatus ?: ("TRK " + index.toString().padStart(2, Char(48))),
                size = finish.displaySize * SECONDARY_SCALE,
                weight = FontWeight.Normal,
                dim = true
            )
        }
    }
}

/**
 * Type inside the glass, with the tube's own light bleeding past its segments.
 *
 * **Four offset copies rather than a blur.** `Modifier.blur` needs API 31 and this app's floor is
 * 28, where it silently does nothing rather than failing — a halo that quietly vanishes on older
 * phones is exactly the bug nobody finds. A text shadow is no good either: it is one directional
 * drop, and a display tube spills evenly in every direction.
 */
@Composable
private fun DisplayText(
    text: String,
    size: androidx.compose.ui.unit.TextUnit,
    weight: FontWeight,
    dim: Boolean = false
) {
    val finish = LocalDeckFinish.current
    val colour = if (dim) finish.displayInk.copy(alpha = 0.6f) else finish.displayInk

    Box {
        if (finish.displayGlow > 0.dp) {
            val spill = finish.displayGlow * GLOW_SPREAD
            listOf(spill to 0.dp, -spill to 0.dp, 0.dp to spill, 0.dp to -spill).forEach { off ->
                Text(
                    text = text,
                    fontFamily = finish.display,
                    fontSize = size,
                    fontWeight = weight,
                    color = colour.copy(alpha = GLOW_ALPHA),
                    letterSpacing = 0.5.sp,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.offset(x = off.first, y = off.second)
                )
            }
        }
        Text(
            text = text,
            fontFamily = finish.display,
            fontSize = size,
            fontWeight = weight,
            color = colour,
            letterSpacing = 0.5.sp,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis
        )
    }
}

/** How much smaller the second line in the window is than the first. */
private const val SECONDARY_SCALE = 0.58f

// How far the window's light spills past its own frame, and how strong it is at the centre.
// **Deliberately loud.** The first pass at these was so restrained the owner could not see the
// effect at all on a real screen, which makes it worse than nothing: it costs frames and buys
// no design. A neon sign in a dark bar is not subtle, and neither is this now.
private const val HALO_SPREAD_Y = 3.1f
private const val HALO_SPREAD_X = 1.16f
private const val HALO_ALPHA = 0.80f

// How far the glow breathes, and how long one breath takes. Slow enough to read as a sign
// humming rather than as something blinking for attention.
private const val BREATH_LOW = 0.62f
private const val BREATH_MS = 2800
private const val GLOW_SPREAD = 0.16f
private const val GLOW_ALPHA = 0.28f
