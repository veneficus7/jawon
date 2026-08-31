package com.jawon.app.ui.listening

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
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

/**
 * The listening record — what this collection has actually been played.
 *
 * Ranked by time rather than by play count, because a play count rewards a two-minute record
 * and this app is built around albums. The bar is share-of-the-longest, so the shape of the
 * list says something at a glance whatever the absolute numbers are.
 *
 * Deliberately not a track leaderboard: `PLAN.md` §2 keeps the album as the atomic unit, and
 * this would be the first screen to break that.
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
        Header(onBack = onBack)

        if (state.loaded && state.rows.isEmpty()) {
            EmptyNote()
            return@Column
        }

        LazyColumn(
            modifier = Modifier
                .fillMaxSize()
                .navigationBarsPadding(),
            contentPadding = PaddingValues(
                start = 20.dp, end = 20.dp, top = 18.dp, bottom = 40.dp
            ),
            verticalArrangement = Arrangement.spacedBy(2.dp)
        ) {
            item { Totals(state) }
            itemsIndexed(state.rows, key = { _, row -> row.albumId }) { index, row ->
                ListeningRowView(
                    rank = index + 1,
                    row = row,
                    onClick = { onOpenAlbum(row.albumId) }
                )
            }
            item { Footnote() }
        }
    }
}

@Composable
private fun Header(onBack: () -> Unit) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(start = 8.dp, end = 8.dp, top = 6.dp, bottom = 10.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        IconButton(onClick = onBack) {
            Icon(
                Icons.AutoMirrored.Filled.ArrowBack,
                contentDescription = "Back",
                tint = LabelDim
            )
        }
        Text(
            text = "LISTENING",
            fontFamily = JetBrainsMonoFamily,
            fontWeight = FontWeight.Bold,
            fontSize = 12.sp,
            letterSpacing = 3.sp,
            color = LampAmber,
            modifier = Modifier.weight(1f)
        )
    }
    Box(Modifier.fillMaxWidth().height(1.dp).background(CrateEdge))
}

/** The one line worth reading before any of the rows. */
@Composable
private fun Totals(state: ListeningState) {
    Column(Modifier.padding(bottom = 22.dp)) {
        Text(
            text = longFormat(state.totalMs),
            fontFamily = JetBrainsMonoFamily,
            fontWeight = FontWeight.Bold,
            fontSize = 34.sp,
            color = LabelCream
        )
        Text(
            text = buildString {
                append("ACROSS ")
                append(state.albumsHeard)
                append(if (state.albumsHeard == 1) " ALBUM" else " ALBUMS")
            },
            fontFamily = JetBrainsMonoFamily,
            fontSize = 11.sp,
            letterSpacing = 2.sp,
            color = LabelDim,
            modifier = Modifier.padding(top = 6.dp)
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
                fontSize = 11.sp,
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
                    fontSize = 15.sp,
                    fontWeight = FontWeight.Medium,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
                Text(
                    text = row.artist.uppercase(),
                    fontFamily = JetBrainsMonoFamily,
                    fontSize = 10.sp,
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
                    fontSize = 13.sp,
                    color = LabelCream
                )
                Text(
                    text = "${row.plays}×",
                    fontFamily = JetBrainsMonoFamily,
                    fontSize = 10.sp,
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
private fun EmptyNote() {
    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(horizontal = 32.dp),
        verticalArrangement = Arrangement.Center,
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        Text(
            text = "NOTHING PLAYED YET",
            fontFamily = JetBrainsMonoFamily,
            fontWeight = FontWeight.Bold,
            fontSize = 13.sp,
            letterSpacing = 2.5.sp,
            color = LabelCream
        )
        Text(
            text = "Put a record on. Jawon keeps a note of what you actually listen to, " +
                "including while the app is closed, and the cases on the shelf wear in to match.",
            fontFamily = JetBrainsMonoFamily,
            fontSize = 12.sp,
            color = LabelDim,
            modifier = Modifier.padding(top = 14.dp)
        )
    }
}

@Composable
private fun Footnote() {
    Text(
        text = "COUNTED AS TIME IN THE TRACK — PAUSING AND SCRUBBING BACK DO NOT INFLATE IT",
        fontFamily = JetBrainsMonoFamily,
        fontSize = 9.sp,
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
