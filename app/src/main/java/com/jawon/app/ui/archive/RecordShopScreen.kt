package com.jawon.app.ui.archive

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.LocalTextStyle
import androidx.compose.material3.Text
import androidx.compose.material3.TextField
import androidx.compose.material3.TextFieldDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.jawon.app.data.archive.ArchiveAlbum
import com.jawon.app.data.archive.ArchiveCollection
import com.jawon.app.data.archive.ArchiveResult
import com.jawon.app.ui.theme.ArchivoFamily
import com.jawon.app.ui.theme.CrateBlack
import com.jawon.app.ui.theme.CrateEdge
import com.jawon.app.ui.theme.CrateRaised
import com.jawon.app.ui.theme.JawonTextStyle
import com.jawon.app.ui.theme.JetBrainsMonoFamily
import com.jawon.app.ui.theme.LabelCream
import com.jawon.app.ui.theme.LabelDim
import com.jawon.app.ui.theme.LampAmber

/**
 * A record shop, not a streaming service.
 *
 * The distinction is the whole design. You browse the racks, you pull something out and look at
 * the back of it, and if you want it you take it home — at which point it is *yours*: a case on
 * your wall, with a disc that spins and can be scratched, exactly like every other record in the
 * room. Nothing here is a rented stream that stops working when a subscription lapses, because
 * everything here is openly licensed.
 *
 * That is also why this screen ends in "TAKE IT HOME" rather than a play button. Playing from
 * the shop would make it a catalogue you visit; taking the record makes it part of the
 * collection, which is what this app is about.
 */
@Composable
fun RecordShopScreen(
    onBack: () -> Unit,
    viewModel: RecordShopViewModel = hiltViewModel()
) {
    val state by viewModel.state.collectAsStateWithLifecycle()

    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(CrateBlack)
            .statusBarsPadding()
    ) {
        ScreenHeader(
            title = if (state.opened != null) "ONE RECORD" else "THE RECORD SHOP",
            onBack = { if (state.opened != null) viewModel.onCloseOpened() else onBack() }
        )

        val opened = state.opened
        if (opened != null) {
            OpenedRecord(
                album = opened,
                owned = state.owned.contains(opened.identifier),
                onTakeHome = { viewModel.onTakeHome(opened) }
            )
        } else {
            Browsing(
                state = state,
                onQueryChanged = viewModel::onQueryChanged,
                onCollectionChanged = viewModel::onCollectionChanged,
                onOpen = viewModel::onOpen
            )
        }
    }
}

/**
 * The same bar every 2D screen wears.
 *
 * Duplicated rather than shared, because the one in `AddAlbumScreen` is private and widening a
 * composable's visibility to reuse eleven lines is the wrong trade — it makes a screen's private
 * detail into an API that other screens can then constrain.
 */
@Composable
private fun ScreenHeader(title: String, onBack: () -> Unit) {
    Column {
        Row(
            modifier = Modifier.fillMaxWidth().padding(horizontal = 20.dp, vertical = 18.dp),
            horizontalArrangement = Arrangement.spacedBy(16.dp)
        ) {
            Text(
                text = "←",
                fontFamily = JetBrainsMonoFamily,
                fontSize = 18.sp,
                color = LabelDim,
                modifier = Modifier.clickable(onClick = onBack)
            )
            Text(
                text = title,
                fontFamily = JetBrainsMonoFamily,
                fontWeight = FontWeight.Bold,
                fontSize = 13.sp,
                letterSpacing = 2.sp,
                color = LampAmber
            )
        }
        Box(Modifier.fillMaxWidth().height(1.dp).background(CrateEdge))
    }
}

@Composable
private fun Browsing(
    state: RecordShopState,
    onQueryChanged: (String) -> Unit,
    onCollectionChanged: (ArchiveCollection) -> Unit,
    onOpen: (ArchiveResult) -> Unit
) {
    Column(modifier = Modifier.fillMaxSize().padding(horizontal = 20.dp)) {
        Row(
            modifier = Modifier.padding(top = 8.dp),
            horizontalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            ArchiveCollection.entries.forEach { collection ->
                CollectionChip(
                    label = collection.label,
                    selected = state.collection == collection,
                    onClick = { onCollectionChanged(collection) }
                )
            }
        }

        Text(
            text = state.collection.blurb,
            style = JawonTextStyle.mono.copy(color = LabelDim, fontSize = 10.sp),
            modifier = Modifier.padding(top = 10.dp)
        )

        TextField(
            value = state.query,
            onValueChange = onQueryChanged,
            singleLine = true,
            placeholder = {
                Text(
                    text = "A band, a year, a venue…",
                    style = JawonTextStyle.mono.copy(color = LabelDim, fontSize = 12.sp)
                )
            },
            textStyle = LocalTextStyle.current.copy(
                fontFamily = JetBrainsMonoFamily,
                fontSize = 13.sp,
                color = LabelCream
            ),
            colors = TextFieldDefaults.colors(
                focusedContainerColor = CrateRaised,
                unfocusedContainerColor = CrateRaised,
                focusedIndicatorColor = LampAmber,
                unfocusedIndicatorColor = CrateEdge,
                cursorColor = LampAmber
            ),
            modifier = Modifier.fillMaxWidth().padding(top = 12.dp)
        )

        state.message?.let {
            Text(
                text = it,
                style = JawonTextStyle.mono.copy(color = LampAmber, fontSize = 10.sp),
                lineHeight = 16.sp,
                modifier = Modifier.padding(top = 12.dp)
            )
        }

        if (state.searching) {
            Text(
                text = "LOOKING…",
                fontFamily = JetBrainsMonoFamily,
                fontWeight = FontWeight.Bold,
                fontSize = 10.sp,
                letterSpacing = 1.6.sp,
                color = LabelDim,
                modifier = Modifier.padding(top = 14.dp)
            )
        }

        LazyColumn(
            modifier = Modifier.fillMaxSize().navigationBarsPadding(),
            contentPadding = PaddingValues(
                top = 14.dp, bottom = 32.dp
            ),
            verticalArrangement = Arrangement.spacedBy(1.dp)
        ) {
            items(state.results, key = { it.identifier }) { result ->
                ResultRow(
                    result = result,
                    owned = state.owned.contains(result.identifier),
                    opening = state.opening == result.identifier,
                    onClick = { onOpen(result) }
                )
            }
        }
    }
}

@Composable
private fun CollectionChip(label: String, selected: Boolean, onClick: () -> Unit) {
    Box(
        modifier = Modifier
            .border(1.dp, if (selected) LampAmber else CrateEdge)
            .clickable(onClick = onClick)
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

@Composable
private fun ResultRow(
    result: ArchiveResult,
    owned: Boolean,
    opening: Boolean,
    onClick: () -> Unit
) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick)
            .padding(vertical = 12.dp),
        verticalArrangement = Arrangement.spacedBy(4.dp)
    ) {
        Text(
            text = result.title,
            fontFamily = ArchivoFamily,
            fontWeight = FontWeight.ExtraBold,
            fontSize = 15.sp,
            color = if (owned) LabelDim else LabelCream,
            maxLines = 2
        )
        Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            Text(
                text = result.creator + (result.year?.let { " · $it" } ?: ""),
                style = JawonTextStyle.mono.copy(color = LabelDim, fontSize = 10.sp),
                maxLines = 1,
                modifier = Modifier.weight(1f, fill = false)
            )
            // Said rather than hidden: an item already taken is still worth seeing in a search,
            // and removing it would make the shop's results change depending on the library.
            if (owned) {
                Text(
                    text = "ON YOUR WALL",
                    fontFamily = JetBrainsMonoFamily,
                    fontWeight = FontWeight.Bold,
                    fontSize = 9.sp,
                    letterSpacing = 1.2.sp,
                    color = LampAmber
                )
            }
            if (opening) {
                Text(
                    text = "OPENING…",
                    style = JawonTextStyle.mono.copy(color = LampAmber, fontSize = 9.sp)
                )
            }
        }
        Box(
            Modifier
                .padding(top = 8.dp)
                .fillMaxWidth()
                .height(1.dp)
                .background(CrateEdge)
        )
    }
}

@Composable
private fun OpenedRecord(
    album: ArchiveAlbum,
    owned: Boolean,
    onTakeHome: () -> Unit
) {
    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .navigationBarsPadding()
            .padding(horizontal = 20.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp)
    ) {
        Text(
            text = album.title,
            fontFamily = ArchivoFamily,
            fontWeight = FontWeight.ExtraBold,
            fontSize = 22.sp,
            color = LabelCream,
            modifier = Modifier.padding(top = 12.dp)
        )
        Text(
            text = album.creator + (album.year?.let { " · $it" } ?: ""),
            style = JawonTextStyle.mono.copy(color = LampAmber, fontSize = 12.sp)
        )
        Text(
            text = "${album.tracks.size} tracks",
            style = JawonTextStyle.mono.copy(color = LabelDim, fontSize = 10.sp)
        )

        Box(
            Modifier
                .padding(vertical = 6.dp)
                .fillMaxWidth()
                .border(1.dp, if (owned) CrateEdge else LampAmber.copy(alpha = 0.55f))
                .clickable(enabled = !owned, onClick = onTakeHome)
                .padding(horizontal = 18.dp, vertical = 14.dp)
        ) {
            Text(
                text = if (owned) "ALREADY ON YOUR WALL" else "TAKE IT HOME",
                fontFamily = JetBrainsMonoFamily,
                fontWeight = FontWeight.Bold,
                fontSize = 11.sp,
                letterSpacing = 1.6.sp,
                color = if (owned) LabelDim else LampAmber
            )
        }

        Text(
            text = "It becomes a case on your wall, streamed from the Archive when you play " +
                "it and kept in the offline store after that — so the disc, the equaliser and " +
                "the rack all work on it, the same as anything else in the room.",
            style = JawonTextStyle.mono.copy(color = LabelDim, fontSize = 10.sp),
            lineHeight = 16.sp
        )

        album.tracks.forEach { track ->
            Row(
                modifier = Modifier.fillMaxWidth().padding(vertical = 7.dp),
                horizontalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                Text(
                    text = "%02d".format(track.trackNumber),
                    style = JawonTextStyle.mono.copy(color = LabelDim, fontSize = 11.sp)
                )
                Text(
                    text = track.title,
                    style = JawonTextStyle.mono.copy(color = LabelCream, fontSize = 11.sp),
                    modifier = Modifier.weight(1f),
                    maxLines = 2
                )
                if (track.durationMs > 0) {
                    Text(
                        text = "%d:%02d".format(
                            track.durationMs / 60_000,
                            (track.durationMs / 1000) % 60
                        ),
                        style = JawonTextStyle.mono.copy(color = LabelDim, fontSize = 11.sp)
                    )
                }
            }
        }
    }
}
