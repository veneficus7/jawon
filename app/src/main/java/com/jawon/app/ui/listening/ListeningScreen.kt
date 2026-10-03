package com.jawon.app.ui.listening

import com.jawon.app.ui.common.EmptyNote
import com.jawon.app.ui.theme.JawonType
import com.jawon.app.ui.common.ScreenBar
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.jawon.app.ui.common.READING_MAX_W
import androidx.compose.ui.unit.sp
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import coil3.compose.AsyncImage
import com.jawon.app.ui.theme.CrateBlack
import com.jawon.app.ui.theme.CrateEdge
import com.jawon.app.ui.theme.CrateRaised
import com.jawon.app.ui.theme.JetBrainsMonoFamily
import com.jawon.app.ui.theme.LabelCream
import com.jawon.app.ui.theme.LabelDim
import com.jawon.app.ui.theme.LampAmber
import java.io.File
import java.util.concurrent.TimeUnit
import java.util.Locale

/**
 * The listening record — what this collection has actually been played.
 *
 * Ranked by time rather than by play count, because a play count rewards a two-minute record
 * and this app is built around albums. The bar is share-of-the-longest, so the shape of the
 * list says something at a glance whatever the absolute numbers are.
 *
 * **The album stays first, and the rule it came from stays too.** This used to say the screen was
 * deliberately not a track leaderboard, because `PLAN.md` §2 keeps the album as the atomic unit.
 * The owner asked for stats.fm-style depth and top tracks are most of what that means, so the
 * rule is restated rather than dropped: the album is the unit you *play*; a track is something
 * the record can *report on*. The album leaderboard is still the body of this screen, the two
 * plain lists are ten rows each below it, and nothing here offers to play a single track.
 */
@Composable
fun ListeningScreen(
    onBack: () -> Unit,
    onOpenAlbum: (String) -> Unit,
    viewModel: ListeningViewModel = hiltViewModel()
) {
    val state by viewModel.state.collectAsStateWithLifecycle()

    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(CrateBlack)
            .statusBarsPadding()
    ) {
        ScreenBar(title = "LISTENING", onBack = onBack)

        if (state.loaded && state.rows.isEmpty()) {
            EmptyNote(
                title = "NOTHING PLAYED YET",
                body = "Put a record on. Jawon keeps a note of what you actually listen to, " +
                    "including while the app is closed, and the cases on the shelf wear in " +
                    "to match.",
                modifier = Modifier.fillMaxSize()
            )
            return@Column
        }

        LazyColumn(
            modifier = Modifier
                .fillMaxSize()
                // See READING_MAX_W: a landscape window gets a readable column rather than
                // rows stretched across the whole device. Portrait is unaffected.
                .widthIn(max = READING_MAX_W)
                .align(Alignment.CenterHorizontally)
                .navigationBarsPadding(),
            contentPadding = PaddingValues(
                start = 20.dp, end = 20.dp, top = 18.dp, bottom = 40.dp
            ),
            verticalArrangement = Arrangement.spacedBy(2.dp)
        ) {
            item {
                PeriodChips(
                    selected = state.period,
                    onSelect = viewModel::onPeriodChanged
                )
            }
            item { Totals(state) }
            if (state.byHour.any { it > 0L }) {
                item { ListeningClock(state) }
            }
            item { SectionLabel("ALBUMS") }
            itemsIndexed(state.rows, key = { _, row -> row.albumId }) { index, row ->
                ListeningRowView(
                    rank = index + 1,
                    row = row,
                    onClick = { onOpenAlbum(row.albumId) }
                )
            }
            if (state.artists.isNotEmpty()) {
                item { SectionLabel("ARTISTS") }
                itemsIndexed(state.artists, key = { _, row -> "artist:" + row.name }) { i, row ->
                    TallyRowView(rank = i + 1, row = row)
                }
            }
            if (state.tracks.isNotEmpty()) {
                item { SectionLabel("TRACKS") }
                itemsIndexed(state.tracks, key = { i, row -> "track:" + i + row.name }) { i, row ->
                    TallyRowView(rank = i + 1, row = row)
                }
            }
            item { Footnote() }
        }
    }
}

/** The one line worth reading before any of the rows. */
@Composable
private fun Totals(state: ListeningState) {
    Column(Modifier.padding(bottom = 22.dp)) {
        Text(
            text = longFormat(state.totalMs),
            fontFamily = JetBrainsMonoFamily,
            fontWeight = FontWeight.Bold,
            fontSize = JawonType.Figure,
            color = LabelCream
        )
        Text(
            text = buildString {
                append("ACROSS ")
                append(state.albumsHeard)
                append(if (state.albumsHeard == 1) " ALBUM" else " ALBUMS")
                if (state.daysListened > 0) {
                    append("  ·  ")
                    append(state.daysListened)
                    append(if (state.daysListened == 1) " DAY" else " DAYS")
                }
            },
            fontFamily = JetBrainsMonoFamily,
            fontSize = JawonType.Body,
            letterSpacing = 2.sp,
            color = LabelDim,
            modifier = Modifier.padding(top = 6.dp)
        )
        // Only when there is one. A row of zeroes is worse than no row: it invites the reader to
        // work out what they did wrong, when the answer is that they have not listened yet.
        if (state.currentStreak > 1 || state.longestStreak > 1) {
            Text(
                text = buildString {
                    if (state.currentStreak > 1) {
                        append(state.currentStreak)
                        append(" DAYS RUNNING")
                    }
                    if (state.longestStreak > state.currentStreak) {
                        if (isNotEmpty()) append("  ·  ")
                        append("BEST ")
                        append(state.longestStreak)
                    }
                },
                fontFamily = JetBrainsMonoFamily,
                fontSize = JawonType.Body,
                letterSpacing = 2.sp,
                color = LampAmber,
                modifier = Modifier.padding(top = 4.dp)
            )
        }
    }
}

/**
 * How far back the numbers reach.
 *
 * Instant, because every window is a filter over rows already in memory — see
 * `PlayEventDao.watchAll`. That responsiveness is most of what the control is for: a period
 * selector you have to wait for stops being something you play with, and playing with it is how
 * you notice that the record you had on repeat in June has not been touched since.
 */
@Composable
private fun PeriodChips(
    selected: ListeningStats.Period,
    onSelect: (ListeningStats.Period) -> Unit
) {
    Row(
        modifier = Modifier.fillMaxWidth().padding(bottom = 20.dp),
        horizontalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        for (period in ListeningStats.Period.entries) {
            val active = period == selected
            Box(
                modifier = Modifier
                    .clip(RoundedCornerShape(3.dp))
                    .background(if (active) LampAmber.copy(alpha = 0.16f) else CrateRaised)
                    .clickable { onSelect(period) }
                    .padding(horizontal = 10.dp, vertical = 7.dp)
            ) {
                Text(
                    text = period.label,
                    fontFamily = JetBrainsMonoFamily,
                    fontWeight = if (active) FontWeight.Bold else FontWeight.Normal,
                    fontSize = JawonType.Fine,
                    letterSpacing = 1.4.sp,
                    color = if (active) LampAmber else LabelDim
                )
            }
        }
    }
}

/**
 * When you listen, by hour of the local day.
 *
 * **The one number here that says something about the person rather than the collection.** Every
 * other row is a ranking — which record, which artist, which song — and rankings mostly confirm
 * what you already knew. The shape of a day does not: that the listening is nearly all after
 * eleven, or that it stops dead at nine in the morning, is a fact about how the app is actually
 * used and it is not visible anywhere else in it.
 *
 * Bars rather than a ring, despite "clock" — a ring puts midnight and midday opposite each other
 * and makes a late-night listener's data read as two unrelated clusters. Laid out left to right
 * from midnight, the same listening is one unmistakable block at the end of the day.
 */
@Composable
private fun ListeningClock(state: ListeningState) {
    val peak = state.byHour.max().coerceAtLeast(1L)

    Column(Modifier.fillMaxWidth().padding(bottom = 24.dp)) {
        SectionLabel("WHEN")
        Row(
            modifier = Modifier.fillMaxWidth().height(56.dp),
            horizontalArrangement = Arrangement.spacedBy(2.dp),
            verticalAlignment = Alignment.Bottom
        ) {
            for (hour in 0 until 24) {
                val share = (state.byHour[hour].toFloat() / peak).coerceIn(0f, 1f)
                Box(
                    modifier = Modifier
                        .weight(1f)
                        // A floor, so an hour with a little listening is a mark rather than
                        // nothing: the difference between "rarely" and "never" is the most
                        // interesting thing on this chart and a proportional bar loses it.
                        .fillMaxHeight(if (share > 0f) (0.06f + share * 0.94f) else 0.02f)
                        .background(
                            if (hour == state.peakHour) LampAmber
                            else if (share > 0f) LampAmber.copy(alpha = 0.35f)
                            else CrateEdge
                        )
                )
            }
        }
        Row(
            modifier = Modifier.fillMaxWidth().padding(top = 6.dp),
            horizontalArrangement = Arrangement.SpaceBetween
        ) {
            for (label in listOf("00", "06", "12", "18", "23")) {
                Text(
                    text = label,
                    fontFamily = JetBrainsMonoFamily,
                    fontSize = JawonType.Fine,
                    letterSpacing = 1.sp,
                    color = LabelDim
                )
            }
        }
        state.peakHour?.let { hour ->
            Text(
                text = "MOSTLY AROUND %02d:00".format(hour),
                fontFamily = JetBrainsMonoFamily,
                fontSize = JawonType.Caption,
                letterSpacing = 1.6.sp,
                color = LabelDim,
                modifier = Modifier.padding(top = 10.dp)
            )
        }
    }
}

/** Names the block below it. The screen has four now and needed telling apart. */
@Composable
private fun SectionLabel(text: String) {
    Text(
        text = text,
        fontFamily = JetBrainsMonoFamily,
        fontWeight = FontWeight.Bold,
        fontSize = JawonType.Caption,
        letterSpacing = 3.sp,
        color = LampAmber,
        modifier = Modifier.padding(top = 18.dp, bottom = 10.dp)
    )
}

/**
 * An artist or a track: a rank, a name, a bar, a time.
 *
 * Deliberately plainer than the album row, which carries a cover and its patina. These are
 * *derived* from albums rather than things in the room, and giving them the same weight would say
 * the app ranks songs, which is the thing the header note is careful about.
 */
@Composable
private fun TallyRowView(rank: Int, row: TallyRow) {
    Column(Modifier.fillMaxWidth().padding(vertical = 7.dp)) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            Text(
                text = "%02d".format(rank),
                fontFamily = JetBrainsMonoFamily,
                fontSize = JawonType.Body,
                color = LabelDim
            )
            Column(Modifier.weight(1f)) {
                Text(
                    text = row.name,
                    style = MaterialTheme.typography.bodyMedium,
                    color = LabelCream,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
                if (row.under.isNotBlank()) {
                    Text(
                        text = row.under,
                        fontFamily = JetBrainsMonoFamily,
                        fontSize = JawonType.Fine,
                        letterSpacing = 1.sp,
                        color = LabelDim,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                }
            }
            Text(
                text = shortFormat(row.totalMs),
                fontFamily = JetBrainsMonoFamily,
                fontSize = JawonType.Body,
                color = LabelDim
            )
        }
        Box(
            Modifier
                .padding(top = 6.dp)
                .fillMaxWidth(row.relative.coerceIn(0.02f, 1f))
                .height(2.dp)
                .background(LampAmber.copy(alpha = 0.5f))
        )
    }
}

@Composable
private fun ListeningRowView(rank: Int, row: ListeningRow, onClick: () -> Unit) {
    Column(
        Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick)
            .padding(vertical = 10.dp)
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(
                text = rank.toString().padStart(2, '0'),
                fontFamily = JetBrainsMonoFamily,
                fontSize = JawonType.Body,
                color = if (rank <= 3) LampAmber else LabelDim,
                modifier = Modifier.width(30.dp)
            )

            // The sleeve, small. It is the fastest way to recognise a record and it makes the
            // list read as a shelf rather than as a table.
            Box(
                Modifier
                    .size(44.dp)
                    .clip(androidx.compose.foundation.shape.RoundedCornerShape(2.dp))
                    .background(CrateRaised)
            ) {
                val path = row.coverPath
                if (!path.isNullOrEmpty() && File(path).exists()) {
                    AsyncImage(
                        model = File(path),
                        contentDescription = null,
                        contentScale = ContentScale.Crop,
                        modifier = Modifier.fillMaxSize()
                    )
                }
            }

            Column(
                Modifier
                    .weight(1f)
                    .padding(start = 14.dp)
            ) {
                Text(
                    text = row.title,
                    color = LabelCream,
                    fontSize = JawonType.Row,
                    fontWeight = FontWeight.Medium,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
                Text(
                    text = row.artist.uppercase(Locale.ROOT),
                    fontFamily = JetBrainsMonoFamily,
                    fontSize = JawonType.Caption,
                    letterSpacing = 1.2.sp,
                    color = LabelDim,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
            }

            Column(horizontalAlignment = Alignment.End) {
                Text(
                    text = shortFormat(row.totalMs),
                    fontFamily = JetBrainsMonoFamily,
                    fontSize = JawonType.Title,
                    color = LabelCream
                )
                Text(
                    text = "${row.plays}×",
                    fontFamily = JetBrainsMonoFamily,
                    fontSize = JawonType.Caption,
                    color = LabelDim
                )
            }
        }

        // Share of the most-listened album. Amber fills to the album's own share; the tail is
        // the same bar the case's wear would fill, so the row says both "how much of the
        // listening this album is" and "how worn its case looks" in one mark.
        Spacer(Modifier.height(9.dp))
        Box(
            Modifier
                .padding(start = 30.dp)
                .fillMaxWidth()
                .height(2.dp)
                .background(CrateEdge)
        ) {
            Box(
                Modifier
                    .fillMaxWidth(row.relative)
                    .height(2.dp)
                    .background(
                        Brush.horizontalGradient(
                            listOf(
                                LampAmber.copy(alpha = 0.45f + row.wear * 0.55f),
                                LampAmber
                            )
                        )
                    )
            )
        }
    }
}

@Composable
private fun Footnote() {
    Text(
        text = "COUNTED AS TIME IN THE TRACK — PAUSING AND SCRUBBING BACK DO NOT INFLATE IT",
        fontFamily = JetBrainsMonoFamily,
        fontSize = JawonType.Fine,
        letterSpacing = 1.sp,
        color = Color(0xFF6E6152),
        modifier = Modifier.padding(top = 28.dp, start = 30.dp)
    )
}

// ---- formatting --------------------------------------------------------------------

/** `4h 12m`, or `12m` under an hour. The headline number, so it reads as prose. */
private fun longFormat(ms: Long): String {
    val hours = TimeUnit.MILLISECONDS.toHours(ms)
    val minutes = TimeUnit.MILLISECONDS.toMinutes(ms) % 60
    return when {
        hours > 0 -> "${hours}h ${minutes}m"
        minutes > 0 -> "${minutes}m"
        else -> "under a minute"
    }
}

/**
 * `4h12` / `12m` / `<1m`, tight enough for the right-hand column.
 *
 * The last case matters. A row only appears here because something was genuinely played, so
 * rounding sixteen seconds down to `0m` states the opposite of why the row exists and reads
 * as a bug rather than as a short listen.
 */
private fun shortFormat(ms: Long): String {
    val hours = TimeUnit.MILLISECONDS.toHours(ms)
    val minutes = TimeUnit.MILLISECONDS.toMinutes(ms) % 60
    return when {
        hours > 0 -> "${hours}h${minutes.toString().padStart(2, '0')}"
        minutes > 0 -> "${minutes}m"
        else -> "<1m"
    }
}
