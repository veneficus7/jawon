package com.jawon.app.ui.wallscene

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Pause
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.SkipNext
import androidx.compose.material.icons.filled.SkipPrevious
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
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.jawon.app.data.local.Track
import com.jawon.app.ui.common.formatDuration
import com.jawon.app.ui.common.qualityReadoutOutput
import com.jawon.app.ui.common.qualityReadoutSource
import com.jawon.app.ui.theme.CrateEdge
import com.jawon.app.ui.theme.JavonTextStyle
import com.jawon.app.ui.theme.LabelDim
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
    onPlayPause: () -> Unit,
    onNext: () -> Unit,
    onPrevious: () -> Unit,
    onSeek: (Long) -> Unit,
    onSelectTrack: (Int) -> Unit,
    scrubReadout: State<ScrubReadout?>,
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
            .padding(top = 22.dp, bottom = 8.dp),
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        Text(
            text = title,
            style = MaterialTheme.typography.displaySmall,
            textAlign = TextAlign.Center,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis
        )
        Text(
            text = artist.uppercase(),
            style = JavonTextStyle.mono,
            modifier = Modifier.padding(top = 6.dp)
        )
        // The quality readout is the resting state of this slot; a scratch takes it over.
        // Both are drawn inside ScrubOrQuality so that the scratch's ~12Hz updates
        // recompose these two lines and nothing else (ENGINEERING.md rule 3).
        ScrubOrQuality(scrubReadout = scrubReadout, deck = deck)

        // The tracklist takes the slack, so the controls below it stay pinned to the bottom
        // of the panel — the reachable part of a phone this tall.
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

        ProgressRow(
            positionFlow = positionFlow,
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
            style = JavonTextStyle.mono,
            modifier = Modifier.padding(top = 10.dp)
        )
        Text(
            text = track?.title.orEmpty().uppercase(),
            style = JavonTextStyle.mono.copy(color = LabelDim),
            modifier = Modifier.padding(top = 2.dp),
            maxLines = 1,
            overflow = TextOverflow.Ellipsis
        )
        return
    }
    deck.currentTrack?.let { track ->
        Text(
            text = qualityReadoutSource(track).uppercase(),
            style = JavonTextStyle.mono.copy(color = LabelDim),
            modifier = Modifier.padding(top = 10.dp)
        )
        Text(
            text = qualityReadoutOutput(
                track, deck.routeLabel, deck.outputSampleRateHz
            ).uppercase(),
            style = JavonTextStyle.mono.copy(color = LabelDim),
            modifier = Modifier.padding(top = 2.dp)
        )
    }
}

/**
 * Isolated so the 2Hz position tick recomposes only the bar and its two labels — the
 * tracklist above it doesn't need to redraw twice a second.
 */
@Composable
private fun ProgressRow(
    positionFlow: StateFlow<Long>,
    durationMs: Long,
    onSeek: (Long) -> Unit
) {
    val position by positionFlow.collectAsState()
    var isDragging by remember { mutableStateOf(false) }
    var dragValue by remember { mutableFloatStateOf(0f) }

    val range = durationMs.coerceAtLeast(1L).toFloat()
    val shown = if (isDragging) dragValue else position.toFloat().coerceIn(0f, range)

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
            Text(formatDuration(shown.toLong()), style = JavonTextStyle.mono)
            Text(formatDuration(durationMs), style = JavonTextStyle.mono)
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
            style = JavonTextStyle.mono.copy(
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
            style = JavonTextStyle.mono.copy(color = LabelDim)
        )
    }
}
