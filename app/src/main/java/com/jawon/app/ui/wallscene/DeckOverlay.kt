package com.jawon.app.ui.wallscene

import androidx.compose.foundation.gestures.detectHorizontalDragGestures
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.unit.IntOffset
import androidx.compose.foundation.layout.offset
import com.jawon.app.playback.Crossfader
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
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.jawon.app.data.local.Track
import com.jawon.app.data.playback.EqBand
import com.jawon.app.ui.common.EqualizerPanel
import com.jawon.app.ui.common.formatDuration
import com.jawon.app.ui.common.qualityReadoutOutput
import com.jawon.app.ui.common.qualityReadoutSource
import com.jawon.app.playback.SleepTimer
import com.jawon.app.ui.theme.CrateEdge
import com.jawon.app.ui.theme.CrateRaised
import com.jawon.app.ui.theme.LabelCream
import com.jawon.app.ui.theme.JawonTextStyle
import com.jawon.app.ui.theme.LabelDim
import com.jawon.app.ui.theme.JetBrainsMonoFamily
import com.jawon.app.ui.theme.LampAmber
import kotlinx.coroutines.flow.StateFlow
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
    /** The crossfader, 0 shut and 1 open — the other hand. See [com.jawon.app.playback.Crossfader]. */
    onFaderChange: (Float) -> Unit,
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
    showEqualiser: Boolean,
    onShowEqualiser: (Boolean) -> Unit,
    modifier: Modifier = Modifier
) {

    Column(
        modifier = modifier
            .fillMaxWidth()
            .background(
                Brush.verticalGradient(
                    0f to ComposeColor.Transparent,
                    0.22f to ComposeColor(0xD1060403),
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
        // Dropped while the equaliser is up. The panel has to grow to fit five band sliders,
        // and a display-size title is both the largest thing here and the least useful when
        // what you are doing is listening — the artist and the format readout stay, because
        // those are what you check against while adjusting.
        if (!showEqualiser) {
            Text(
                text = title,
                style = MaterialTheme.typography.displaySmall,
                textAlign = TextAlign.Center,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
        }
        Text(
            text = artist.uppercase(),
            style = JawonTextStyle.mono,
            modifier = Modifier.padding(top = 6.dp)
        )
        // The quality readout is the resting state of this slot; a scratch takes it over.
        // Both are drawn inside ScrubOrQuality so that the scratch's ~12Hz updates
        // recompose these two lines and nothing else (ENGINEERING.md rule 3).
        ScrubOrQuality(scrubReadout = scrubReadout, deck = deck)

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
            DeckTab("TRACKS", selected = !showEqualiser) { onShowEqualiser(false) }
            if (eqAvailable) {
                DeckTab("SOUND", selected = showEqualiser) { onShowEqualiser(true) }
            }

            Box(Modifier.weight(1f))

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

        // The tracklist takes the slack, so the controls below it stay pinned to the bottom
        // of the panel — the reachable part of a phone this tall.
        if (showEqualiser) {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .weight(1f)
                    .verticalScroll(rememberScrollState())
                    .padding(top = 10.dp)
            ) {
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
                    .weight(1f)
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

        // Under the transport, where a fader is on a real deck and where a thumb can reach it
        // while the other hand is on the disc.
        FaderStrip(onChange = onFaderChange)
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
 * The crossfader — the other hand.
 *
 * A scratch is two hands: one moves the record and supplies the pitch, one cuts the sound in
 * and out and supplies the rhythm. Every named technique is a pattern of cuts over a movement,
 * so without this the disc can only smear. It sits directly under the transport, within reach
 * of a thumb while the other hand is on the disc — which is the only arrangement that lets
 * anyone actually do the two things at once on a phone.
 *
 * **It springs open when released**, like a fader hand coming off. Left where it was last put,
 * a shut fader would silence the next record someone played with no visible reason why, and
 * that is a support question rather than an instrument.
 */
@Composable
private fun FaderStrip(onChange: (Float) -> Unit) {
    var position by remember { mutableFloatStateOf(Crossfader.RESTING) }
    var width by remember { mutableFloatStateOf(1f) }

    fun set(value: Float) {
        position = value.coerceIn(0f, 1f)
        onChange(position)
    }

    Box(
        modifier = Modifier
            .fillMaxWidth()
            .padding(top = 10.dp, bottom = 2.dp)
            .height(34.dp)
            .onSizeChanged { width = it.width.toFloat().coerceAtLeast(1f) }
            .pointerInput(Unit) {
                detectHorizontalDragGestures(
                    onDragStart = { set(it.x / width) },
                    onDragEnd = { set(Crossfader.RESTING) },
                    onDragCancel = { set(Crossfader.RESTING) }
                ) { change, _ -> set(change.position.x / width) }
            }
    ) {
        // The travel.
        Box(
            Modifier
                .align(Alignment.Center)
                .fillMaxWidth()
                .height(3.dp)
                .background(CrateEdge)
        )
        // Where it cuts, marked. A scratch fader opens within a few millimetres of the closed
        // edge, and knowing where that is by eye is most of learning to use one.
        Box(
            Modifier
                .align(Alignment.CenterStart)
                .fillMaxWidth(Crossfader.OPEN_ABOVE)
                .height(3.dp)
                .background(LampAmber.copy(alpha = 0.30f))
        )

        val open = Crossfader.gainFor(position)
        Box(
            Modifier
                .align(Alignment.CenterStart)
                .offset { IntOffset((position * width * 0.94f).toInt(), 0) }
                .size(width = 22.dp, height = 30.dp)
                .background(
                    if (open > 0.5f) LampAmber else LabelDim,
                    RoundedCornerShape(2.dp)
                )
        )
    }
}
