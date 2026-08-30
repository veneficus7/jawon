package com.jawon.app.ui.shelfmanager

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkVertically
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.KeyboardArrowDown
import androidx.compose.material.icons.filled.KeyboardArrowUp
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LocalTextStyle
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Slider
import androidx.compose.material3.SliderDefaults
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TextField
import androidx.compose.material3.TextFieldDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.jawon.app.data.local.Album
import com.jawon.app.data.local.ShelfWithAlbums
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
 * The room's index — every shelf on the wall, in the order they hang.
 *
 * **This screen is a picture of the wall, not a settings form.** Each shelf is drawn as the
 * board you actually see in the room: the cream tape label stuck across the middle of it, its
 * underlight glowing along the front edge in the shelf's own accent hue. Reading down the list
 * is reading down the wall, so a shelf moved up here is a shelf that visibly moves up there.
 *
 * It replaced a screen that laid every control for every shelf out at once — a bare text field,
 * a hue slider, Up and Down buttons and a Move button per album, all of it visible for all of
 * them, plus a "Back" button parked at the bottom of a scrolling list where it drifted off
 * screen. Nothing there told you which shelf you were looking at at a glance. Now a shelf shows
 * its name, its colour and how full it is; the controls arrive when you open one.
 */
@Composable
fun ShelfManagerScreen(
    onBack: () -> Unit,
    onEditAlbum: (String) -> Unit,
    viewModel: ShelfManagerViewModel = hiltViewModel()
) {
    val shelves by viewModel.shelves.collectAsStateWithLifecycle()
    var openShelfId by remember { mutableStateOf<String?>(null) }
    var creating by remember { mutableStateOf(false) }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(CrateBlack)
            .statusBarsPadding()
    ) {
        ScreenHeader(
            onBack = onBack,
            onNew = { creating = !creating },
            newIsOpen = creating
        )

        LazyColumn(
            modifier = Modifier
                .fillMaxSize()
                .navigationBarsPadding(),
            contentPadding = PaddingValues(
                start = 20.dp, end = 20.dp, top = 16.dp, bottom = 32.dp
            ),
            verticalArrangement = Arrangement.spacedBy(14.dp)
        ) {
            item {
                AnimatedVisibility(
                    visible = creating,
                    enter = fadeIn() + expandVertically(),
                    exit = fadeOut() + shrinkVertically()
                ) {
                    NewShelfField(onCreate = {
                        viewModel.onCreateShelf(it)
                        creating = false
                    })
                }
            }

            if (shelves.isEmpty()) {
                item { EmptyNote() }
            } else {
                items(shelves, key = { it.shelf.id }) { entry ->
                    val index = shelves.indexOfFirst { it.shelf.id == entry.shelf.id }
                    ShelfBoard(
                        entry = entry,
                        isFirst = index == 0,
                        isLast = index == shelves.lastIndex,
                        expanded = openShelfId == entry.shelf.id,
                        // One at a time. Two open boards is most of a screen of controls,
                        // which is the clutter this layout exists to get rid of.
                        onToggle = {
                            openShelfId = if (openShelfId == entry.shelf.id) null
                            else entry.shelf.id
                        },
                        onRename = viewModel::onRename,
                        onRecolour = viewModel::onRecolour,
                        onMoveUp = viewModel::onMoveUp,
                        onMoveDown = viewModel::onMoveDown,
                        onEditAlbum = onEditAlbum
                    )
                }
            }
        }
    }
}

/**
 * Back on the left, new shelf on the right, title between them.
 *
 * The old screen's only way out was a button at the very bottom of the list, below every shelf
 * — on a full library you had to scroll to leave. A header is always reachable, and the system
 * back gesture already worked, so this just makes it visible.
 */
@Composable
private fun ScreenHeader(onBack: () -> Unit, onNew: () -> Unit, newIsOpen: Boolean) {
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
            text = "SHELVES",
            fontFamily = JetBrainsMonoFamily,
            fontWeight = FontWeight.Bold,
            fontSize = 12.sp,
            letterSpacing = 3.sp,
            color = LampAmber,
            modifier = Modifier.weight(1f)
        )
        IconButton(onClick = onNew) {
            Icon(
                Icons.Default.Add,
                contentDescription = "New shelf",
                // Turns into a close affordance while the field is open, so the button
                // that opened it is also the one that puts it away.
                modifier = Modifier.rotate(if (newIsOpen) 45f else 0f),
                tint = if (newIsOpen) LampAmber else LabelDim
            )
        }
    }
    // Hairline, not a shadow. Everything in this room is a flat printed edge.
    Box(
        Modifier
            .fillMaxWidth()
            .height(1.dp)
            .background(CrateEdge)
    )
}

/** One shelf, drawn as its board. Collapsed it is a label and a count; open it, and it edits. */
@Composable
private fun ShelfBoard(
    entry: ShelfWithAlbums,
    isFirst: Boolean,
    isLast: Boolean,
    expanded: Boolean,
    onToggle: () -> Unit,
    onRename: (String, String) -> Unit,
    onRecolour: (String, Float) -> Unit,
    onMoveUp: (String) -> Unit,
    onMoveDown: (String) -> Unit,
    onEditAlbum: (String) -> Unit
) {
    val shelf = entry.shelf
    var hue by remember(shelf.id) { mutableFloatStateOf(shelf.accentHue) }
    val accent = Color.hsv(hue * 360f, 0.62f, 0.85f)
    val chevron by animateFloatAsState(
        targetValue = if (expanded) 180f else 0f,
        animationSpec = tween(220, easing = FastOutSlowInEasing),
        label = "chevron"
    )

    Column(
        modifier = Modifier
            .fillMaxWidth()
            .border(1.dp, if (expanded) accent.copy(alpha = 0.45f) else CrateEdge)
            .background(CrateRaised)
    ) {
        // ---- The board face -------------------------------------------------------
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .clickable(onClick = onToggle)
                .padding(vertical = 18.dp)
        ) {
            // Centred, because that is where the tape actually is on the wall: the label
            // marks the middle of the shelf's run and the albums are centred on it.
            //
            // The count sits *under* the tape rather than off to one side. Centring the tape
            // means stacking these in a Box, and a Box will happily let them overlap — with
            // the count on the left, the tape had to be inset far enough to clear it and
            // "ALTERNATIVE ROCK" no longer fit. Under the label it costs the name nothing,
            // and it reads better anyway: the count belongs to the shelf it is counting.
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    // Only has to clear the chevron now.
                    .padding(horizontal = 52.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.spacedBy(7.dp)
            ) {
                TapeLabel(shelf.name)
                Text(
                    text = if (entry.albums.isEmpty()) "EMPTY"
                    else "${entry.albums.size} ${if (entry.albums.size == 1) "ALBUM" else "ALBUMS"}",
                    style = JawonTextStyle.mono.copy(color = LabelDim, fontSize = 10.sp)
                )
            }
            Icon(
                Icons.Default.KeyboardArrowDown,
                contentDescription = if (expanded) "Close" else "Edit shelf",
                tint = LabelDim,
                modifier = Modifier
                    .align(Alignment.CenterEnd)
                    .padding(end = 12.dp)
                    .rotate(chevron)
            )
        }

        // ---- The underlight -------------------------------------------------------
        // The one thing that makes a shelf recognisably itself from across the room, so it
        // is the one thing shown without opening anything. Brightest at the centre, under
        // the tape, and falling off toward the ends — the same pool of light the beam casts.
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .height(2.dp)
                .background(
                    Brush.horizontalGradient(
                        0f to Color.Transparent,
                        0.5f to accent,
                        1f to Color.Transparent
                    )
                )
        )

        AnimatedVisibility(
            visible = expanded,
            enter = fadeIn(tween(180)) + expandVertically(tween(220, easing = FastOutSlowInEasing)),
            exit = fadeOut(tween(120)) + shrinkVertically(tween(200, easing = FastOutSlowInEasing))
        ) {
            Column(
                modifier = Modifier.padding(14.dp),
                verticalArrangement = Arrangement.spacedBy(14.dp)
            ) {
                NameField(
                    initial = shelf.name,
                    shelfId = shelf.id,
                    onCommit = onRename
                )

                FieldLabel("UNDERLIGHT")
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(12.dp)
                ) {
                    Slider(
                        value = hue,
                        onValueChange = { hue = it },
                        onValueChangeFinished = { onRecolour(shelf.id, hue) },
                        valueRange = 0f..1f,
                        colors = SliderDefaults.colors(
                            thumbColor = accent,
                            activeTrackColor = accent.copy(alpha = 0.7f),
                            inactiveTrackColor = CrateEdge
                        ),
                        modifier = Modifier.weight(1f)
                    )
                    // Solid, not a tinted wash. At 22% over the board colour this read as an
                    // empty outlined box — the one control whose entire job is to show you a
                    // colour was the one place the colour did not appear.
                    Box(
                        Modifier
                            .size(28.dp)
                            .background(accent)
                    )
                }

                FieldLabel("POSITION ON THE WALL")
                Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    MoveButton(
                        icon = Icons.Default.KeyboardArrowUp,
                        label = "UP",
                        enabled = !isFirst,
                        onClick = { onMoveUp(shelf.id) }
                    )
                    MoveButton(
                        icon = Icons.Default.KeyboardArrowDown,
                        label = "DOWN",
                        enabled = !isLast,
                        onClick = { onMoveDown(shelf.id) }
                    )
                }

                if (entry.albums.isNotEmpty()) {
                    FieldLabel("ON THIS SHELF")
                    entry.albums.forEach { album ->
                        AlbumLine(album, onEditAlbum)
                    }
                }
            }
        }
    }
}

/**
 * The shelf's name as the tape stuck to its board: cream label stock, dark mono lettering.
 *
 * Deliberately the same object as the in-room label plate, so the list and the wall are
 * obviously showing you the same shelves.
 */
@Composable
private fun TapeLabel(name: String, modifier: Modifier = Modifier) {
    Box(
        modifier = modifier
            .background(LabelCream)
            .padding(horizontal = 14.dp, vertical = 5.dp)
    ) {
        Text(
            text = name.uppercase(),
            fontFamily = JetBrainsMonoFamily,
            fontWeight = FontWeight.Medium,
            fontSize = 11.sp,
            letterSpacing = 2.sp,
            color = CrateBlack,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis
        )
    }
}

/**
 * Renaming, committed on Done or when the field loses focus — **not on every keystroke**.
 *
 * The old field called `onRename` from `onValueChange`, so typing a nine-letter shelf name was
 * nine Room writes, and each one re-emitted the library flow and rebuilt the whole wall layout
 * behind this screen. Cheap to fix, and it is the sort of thing that only shows up on a slower
 * phone than the one it was written on.
 */
@Composable
private fun NameField(
    initial: String,
    shelfId: String,
    onCommit: (String, String) -> Unit
) {
    var text by remember(shelfId) { mutableStateOf(initial) }
    var focused by remember(shelfId) { mutableStateOf(false) }
    val focus = LocalFocusManager.current

    fun commit() {
        val trimmed = text.trim()
        if (trimmed.isNotEmpty() && trimmed != initial) onCommit(shelfId, trimmed)
    }

    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
        FieldLabel("NAME")
        TextField(
            value = text,
            onValueChange = { text = it },
            singleLine = true,
            textStyle = LocalTextStyle.current.copy(
                fontFamily = JetBrainsMonoFamily,
                fontSize = 14.sp,
                letterSpacing = 1.2.sp,
                color = LabelCream
            ),
            keyboardOptions = KeyboardOptions(imeAction = ImeAction.Done),
            keyboardActions = KeyboardActions(onDone = {
                commit()
                focus.clearFocus()
            }),
            colors = TextFieldDefaults.colors(
                focusedContainerColor = CrateBlack,
                unfocusedContainerColor = CrateBlack,
                focusedIndicatorColor = LampAmber,
                unfocusedIndicatorColor = CrateEdge,
                cursorColor = LampAmber
            ),
            modifier = Modifier
                .fillMaxWidth()
                .onFocusChanged { state ->
                    // Losing focus counts as finishing — tapping away from the field is how
                    // most people leave it, and nobody expects that to discard the edit.
                    if (focused && !state.isFocused) commit()
                    focused = state.isFocused
                }
        )
    }
}

/** The create-a-shelf field, shown only when the header's + is pressed. */
@Composable
private fun NewShelfField(onCreate: (String) -> Unit) {
    var name by remember { mutableStateOf("") }
    val focus = LocalFocusManager.current

    fun submit() {
        if (name.isBlank()) return
        onCreate(name)
        name = ""
        focus.clearFocus()
    }

    Column(
        modifier = Modifier
            .fillMaxWidth()
            .border(1.dp, CrateEdge)
            .background(CrateRaised)
            .padding(14.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        FieldLabel("NEW SHELF")
        Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            TextField(
                value = name,
                onValueChange = { name = it },
                singleLine = true,
                placeholder = {
                    Text(
                        "Jazz, 2009, Sunday morning…",
                        style = JawonTextStyle.mono.copy(color = LabelDim)
                    )
                },
                textStyle = LocalTextStyle.current.copy(
                    fontFamily = JetBrainsMonoFamily,
                    fontSize = 14.sp,
                    letterSpacing = 1.2.sp,
                    color = LabelCream
                ),
                keyboardOptions = KeyboardOptions(imeAction = ImeAction.Done),
                keyboardActions = KeyboardActions(onDone = { submit() }),
                colors = TextFieldDefaults.colors(
                    focusedContainerColor = CrateBlack,
                    unfocusedContainerColor = CrateBlack,
                    focusedIndicatorColor = LampAmber,
                    unfocusedIndicatorColor = CrateEdge,
                    cursorColor = LampAmber
                ),
                modifier = Modifier.weight(1f)
            )
            TextButton(onClick = { submit() }, enabled = name.isNotBlank()) {
                Text(
                    "ADD",
                    fontFamily = JetBrainsMonoFamily,
                    fontWeight = FontWeight.Bold,
                    fontSize = 12.sp,
                    letterSpacing = 1.6.sp,
                    color = if (name.isNotBlank()) LampAmber else LabelDim
                )
            }
        }
    }
}

/**
 * An album on a shelf. The whole row opens it for editing.
 *
 * There used to be a "Move" button on every single row, opening a menu of other shelves —
 * a column of identical buttons on a full shelf, for the one property of an album this
 * screen could change. Which shelf a record lives on is now one field among the rest on the
 * edit screen, so the row can just be a way in.
 */
@Composable
private fun AlbumLine(album: Album, onEdit: (String) -> Unit) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable { onEdit(album.id) }
            .padding(vertical = 9.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(10.dp)
    ) {
        Column(Modifier.weight(1f)) {
            Text(
                album.title,
                fontFamily = ArchivoFamily,
                fontWeight = FontWeight.ExtraBold,
                fontSize = 14.sp,
                color = LabelCream,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
            Text(
                album.artist.uppercase(),
                style = JawonTextStyle.mono.copy(color = LabelDim, fontSize = 10.sp),
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
        }
        // Says what tapping does, without being a second target to aim at.
        Text("EDIT", style = JawonTextStyle.mono.copy(color = LabelDim, fontSize = 10.sp))
    }
}

@Composable
private fun MoveButton(
    icon: androidx.compose.ui.graphics.vector.ImageVector,
    label: String,
    enabled: Boolean,
    onClick: () -> Unit
) {
    val tint = if (enabled) LabelCream else CrateEdge
    Row(
        modifier = Modifier
            .border(1.dp, if (enabled) CrateEdge else CrateEdge.copy(alpha = 0.4f))
            .clickable(enabled = enabled, onClick = onClick)
            .padding(horizontal = 14.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(4.dp)
    ) {
        Icon(icon, contentDescription = null, tint = tint, modifier = Modifier.size(16.dp))
        Text(
            label,
            fontFamily = JetBrainsMonoFamily,
            fontSize = 11.sp,
            letterSpacing = 1.4.sp,
            color = tint
        )
    }
}

/** The small amber caption that names a control. */
@Composable
private fun FieldLabel(text: String) {
    Text(
        text = text,
        fontFamily = JetBrainsMonoFamily,
        fontWeight = FontWeight.Bold,
        fontSize = 9.sp,
        letterSpacing = 2.sp,
        color = LampAmber.copy(alpha = 0.75f)
    )
}

/**
 * No shelves at all. Rare — importing an album creates "Unsorted" — but reachable by deleting
 * everything, and per `PLAN.md` §4 an empty state should read as waiting rather than broken.
 */
@Composable
private fun EmptyNote() {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(top = 48.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(10.dp)
    ) {
        Box(
            Modifier
                .width(90.dp)
                .height(1.dp)
                .background(CrateEdge)
        )
        Text(
            "NO SHELVES YET",
            fontFamily = JetBrainsMonoFamily,
            fontWeight = FontWeight.Bold,
            fontSize = 11.sp,
            letterSpacing = 2.sp,
            color = LabelDim
        )
        Text(
            "Use + above to put one on the wall.",
            style = MaterialTheme.typography.bodySmall,
            color = LabelDim
        )
    }
}
