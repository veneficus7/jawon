package com.jawon.app.ui.shelfmanager

import com.jawon.app.ui.theme.JawonType
import com.jawon.app.ui.common.ScreenBar
import androidx.activity.compose.BackHandler
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
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
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
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.BarChart
import androidx.compose.material.icons.filled.KeyboardArrowDown
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.KeyboardArrowUp
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LocalTextStyle
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TextField
import androidx.compose.material3.TextFieldDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.RectangleShape
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
import com.jawon.app.ui.wallscene.WallSort
import com.jawon.app.ui.theme.ArchivoFamily
import com.jawon.app.ui.theme.CrateBlack
import com.jawon.app.ui.theme.CrateEdge
import com.jawon.app.ui.theme.CrateRaised
import com.jawon.app.ui.theme.JawonTextStyle
import com.jawon.app.ui.theme.JetBrainsMonoFamily
import com.jawon.app.ui.theme.LabelCream
import com.jawon.app.ui.theme.LabelDim
import com.jawon.app.ui.theme.LampAmber
import java.util.Locale

/**
 * The room's index — every shelf on the wall, in the order they hang.
 *
 * **This screen is a picture of the wall, not a settings form.** Each shelf is drawn as the
 * board you actually see in the room, with the cream tape label stuck across the middle of it.
 * Reading down the list is reading down the wall, so a shelf moved up here is a shelf that
 * visibly moves up there.
 *
 * It replaced a screen that laid every control for every shelf out at once — a bare text field,
 * a hue slider, Up and Down buttons and a Move button per album, all of it visible for all of
 * them, plus a "Back" button parked at the bottom of a scrolling list where it drifted off
 * screen. Nothing there told you which shelf you were looking at at a glance. Now a shelf shows
 * its name and how full it is; the controls arrive when you open one.
 *
 * **There is no shelf colour any more** (2026-09-21, the owner: "what are they for? just remove
 * it"). It coloured each shelf's underlight, and the underlights left the room on 2026-09-13;
 * after that it tinted a small label card and a line on this screen, and nothing else.
 */
@Composable
fun ShelfManagerScreen(
    onBack: () -> Unit,
    onEditAlbum: (String) -> Unit,
    onOpenListening: () -> Unit,
    viewModel: ShelfManagerViewModel = hiltViewModel()
) {
    val shelves by viewModel.shelves.collectAsStateWithLifecycle()
    val sort by viewModel.sort.collectAsStateWithLifecycle()
    val selection by viewModel.selection.collectAsStateWithLifecycle()
    val selecting by viewModel.selecting.collectAsStateWithLifecycle()
    val onToggleSelected = viewModel::onToggleSelected
    var openShelfId by remember { mutableStateOf<String?>(null) }
    var creating by remember { mutableStateOf(false) }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(CrateBlack)
            .statusBarsPadding()
    ) {
        // Cancels the selection before it leaves the screen: the mode is easy to enter by
        // accident with a long press, and a back that both clears and navigates would take two
        // presses to do the obvious thing.
        BackHandler(enabled = selecting) { viewModel.onClearSelection() }

        // Back on the left, new shelf on the right. The old screen's only way out was a button
        // below every shelf, so on a full library you had to scroll to leave.
        ScreenBar(title = "SHELVES", onBack = onBack, backDescription = "Back to the room") {
            // The listening record. It sits here rather than in the room's chrome because that
            // is three even thirds by an explicit decision in WallSceneScreen, and because what
            // you have played is a fact about the collection, which is what this screen is.
            IconButton(onClick = onOpenListening) {
                Icon(
                    Icons.Default.BarChart,
                    contentDescription = "What you have listened to",
                    tint = LabelDim
                )
            }
            IconButton(onClick = { creating = !creating }) {
                Icon(
                    Icons.Default.Add,
                    contentDescription = "New shelf",
                    // Turns into a close affordance while the field is open, so the button
                    // that opened it is also the one that puts it away.
                    modifier = Modifier.rotate(if (creating) 45f else 0f),
                    tint = if (creating) LampAmber else LabelDim
                )
            }
        }

        if (selecting) {
            SelectionBar(
                count = selection.size,
                onCancel = viewModel::onClearSelection,
                onRemove = viewModel::onRemoveSelected
            )
        }

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
                FilingRow(current = sort, onPick = viewModel::onSortChanged)
            }

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
                        canDelete = shelves.size > 1,
                        expanded = openShelfId == entry.shelf.id,
                        // One at a time. Two open boards is most of a screen of controls,
                        // which is the clutter this layout exists to get rid of.
                        onToggle = {
                            openShelfId = if (openShelfId == entry.shelf.id) null
                            else entry.shelf.id
                        },
                        onRename = viewModel::onRename,
                        onMoveUp = viewModel::onMoveUp,
                        onMoveDown = viewModel::onMoveDown,
                        onEditAlbum = onEditAlbum,
                        selecting = selecting,
                        selection = selection,
                        onToggleSelected = onToggleSelected,
                        onDelete = viewModel::onDeleteShelf
                    )
                }
            }
        }
    }
}

/**
 * How records are filed along every shelf.
 *
 * At the top of the list rather than in Settings: this is a property of the collection, not a
 * preference about the app, and it belongs next to the shelves it rearranges.
 *
 * The note underneath earns its space. Sorting alphabetically is what turns the crate flip's
 * rail into an actual A–Z index rather than a labelled position scrubber, and that connection
 * is not guessable from a list of six words.
 */
@Composable
private fun FilingRow(current: WallSort, onPick: (WallSort) -> Unit) {
    var open by remember { mutableStateOf(false) }

    Column(Modifier.fillMaxWidth().padding(bottom = 6.dp)) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .border(1.dp, CrateEdge)
                .clickable { open = true }
                .padding(horizontal = 16.dp, vertical = 13.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text(
                text = "FILED BY",
                fontFamily = JetBrainsMonoFamily,
                fontSize = JawonType.Caption,
                letterSpacing = 1.6.sp,
                color = LabelDim,
                modifier = Modifier.weight(1f)
            )
            Text(
                text = current.label,
                fontFamily = JetBrainsMonoFamily,
                fontWeight = FontWeight.Bold,
                fontSize = JawonType.Caption,
                letterSpacing = 1.6.sp,
                color = LampAmber
            )
            Icon(
                Icons.Default.KeyboardArrowDown,
                contentDescription = "Change how records are filed",
                tint = LabelDim,
                modifier = Modifier.padding(start = 8.dp).size(18.dp)
            )
        }

        Text(
            text = if (current.isAlphabetical) {
                "The letters down the side of the wall jump you to that letter."
            } else {
                "Sort by album or artist and the letters down the side of the " +
                    "wall start working."
            },
            style = JawonTextStyle.mono.copy(color = LabelDim, fontSize = JawonType.Fine),
            lineHeight = 15.sp,
            modifier = Modifier.padding(top = 7.dp, start = 2.dp)
        )

        DropdownMenu(
            expanded = open,
            onDismissRequest = { open = false },
            modifier = Modifier.background(CrateRaised)
        ) {
            WallSort.entries.forEach { option ->
                DropdownMenuItem(
                    text = {
                        Text(
                            text = option.label,
                            fontFamily = JetBrainsMonoFamily,
                            fontSize = JawonType.Body,
                            letterSpacing = 1.4.sp,
                            color = if (option == current) LampAmber else LabelCream
                        )
                    },
                    onClick = { onPick(option); open = false }
                )
            }
        }
    }
}

/** One shelf, drawn as its board. Collapsed it is a label and a count; open it, and it edits. */
@Composable
private fun ShelfBoard(
    entry: ShelfWithAlbums,
    isFirst: Boolean,
    isLast: Boolean,
    canDelete: Boolean,
    expanded: Boolean,
    onToggle: () -> Unit,
    onRename: (String, String) -> Unit,
    onMoveUp: (String) -> Unit,
    onMoveDown: (String) -> Unit,
    onEditAlbum: (String) -> Unit,
    selecting: Boolean,
    selection: Set<String>,
    onToggleSelected: (String) -> Unit,
    onDelete: (String) -> Unit
) {
    val shelf = entry.shelf
    var confirmingDelete by remember(shelf.id) { mutableStateOf(false) }

    if (confirmingDelete) {
        RemoveShelfDialog(
            name = shelf.name,
            albumCount = entry.albums.size,
            onConfirm = {
                confirmingDelete = false
                onDelete(shelf.id)
            },
            onDismiss = { confirmingDelete = false }
        )
    }
    val chevron by animateFloatAsState(
        targetValue = if (expanded) 180f else 0f,
        animationSpec = tween(220, easing = FastOutSlowInEasing),
        label = "chevron"
    )

    Column(
        modifier = Modifier
            .fillMaxWidth()
            .border(1.dp, if (expanded) LampAmber.copy(alpha = 0.45f) else CrateEdge)
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
                    style = JawonTextStyle.mono.copy(color = LabelDim, fontSize = JawonType.Caption)
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
                        AlbumLine(
                            album = album,
                            onEdit = onEditAlbum,
                            selecting = selecting,
                            selected = album.id in selection,
                            onToggleSelected = onToggleSelected
                        )
                    }
                }

                // Hidden on the last shelf rather than shown and refused. Every album has to
                // live somewhere, so a library cannot get to zero shelves — and a control
                // that is present but always fails is worse than one that is not there.
                if (canDelete) {
                    Row(
                        modifier = Modifier
                            .padding(top = 4.dp)
                            .border(1.dp, CrateEdge)
                            .clickable { confirmingDelete = true }
                            .padding(horizontal = 14.dp, vertical = 8.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(6.dp)
                    ) {
                        Icon(
                            Icons.Default.Delete,
                            contentDescription = null,
                            tint = LabelDim,
                            modifier = Modifier.size(15.dp)
                        )
                        Text(
                            "REMOVE SHELF",
                            fontFamily = JetBrainsMonoFamily,
                            fontSize = JawonType.Body,
                            letterSpacing = 1.4.sp,
                            color = LabelDim
                        )
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
            text = name.uppercase(Locale.ROOT),
            fontFamily = JetBrainsMonoFamily,
            fontWeight = FontWeight.Medium,
            fontSize = JawonType.Body,
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
                fontSize = JawonType.Title,
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
                    fontSize = JawonType.Title,
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
                    fontSize = JawonType.Title,
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
private fun AlbumLine(
    album: Album,
    onEdit: (String) -> Unit,
    selecting: Boolean,
    selected: Boolean,
    onToggleSelected: (String) -> Unit
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            // **Long press enters selection, and after that a tap ticks rather than edits.**
            //
            // A mode rather than a permanent row of checkboxes: removing records is the rare
            // thing done here and editing one is the common thing, so the common case keeps
            // the whole row and the rare one has to be asked for. Once the mode is on, a plain
            // tap has to mean "tick" — a list where some taps select and others navigate is
            // the classic way to delete something by accident.
            .combinedClickable(
                onClick = { if (selecting) onToggleSelected(album.id) else onEdit(album.id) },
                onLongClick = { onToggleSelected(album.id) }
            )
            .background(if (selected) LampAmber.copy(alpha = 0.10f) else Color.Transparent)
            .padding(vertical = 9.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(10.dp)
    ) {
        if (selecting) {
            // A mark, not a Material checkbox: this screen draws its own controls throughout,
            // and a stock checkbox here would be the one piece of another app's furniture.
            Text(
                text = if (selected) "■" else "□",
                style = JawonTextStyle.mono.copy(
                    color = if (selected) LampAmber else LabelDim,
                    fontSize = JawonType.Title
                )
            )
        }
        Column(Modifier.weight(1f)) {
            Text(
                album.title,
                fontFamily = ArchivoFamily,
                fontWeight = FontWeight.ExtraBold,
                fontSize = JawonType.Row,
                color = LabelCream,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
            Text(
                album.artist.uppercase(Locale.ROOT),
                style = JawonTextStyle.mono.copy(color = LabelDim, fontSize = JawonType.Caption),
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
        }
        // Says what tapping does, without being a second target to aim at.
        Text("EDIT", style = JawonTextStyle.mono.copy(color = LabelDim, fontSize = JawonType.Caption))
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
            fontSize = JawonType.Body,
            letterSpacing = 1.4.sp,
            color = tint
        )
    }
}

/**
 * Confirms taking a shelf off the wall.
 *
 * **What it says about the albums is the point.** "Delete shelf" reads like it takes the
 * records with it, and it does not — so the dialog leads with where they go instead of asking
 * a bare "are you sure?". Same reasoning as the album removal dialog, which had to say out
 * loud that it never touches the user's audio files.
 */
@Composable
private fun RemoveShelfDialog(
    name: String,
    albumCount: Int,
    onConfirm: () -> Unit,
    onDismiss: () -> Unit
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        shape = RectangleShape,
        containerColor = CrateBlack,
        tonalElevation = 0.dp,
        modifier = Modifier.border(1.dp, CrateEdge),
        title = {
            Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                Text(
                    text = "REMOVE SHELF",
                    fontFamily = JetBrainsMonoFamily,
                    fontWeight = FontWeight.Bold,
                    fontSize = JawonType.Body,
                    letterSpacing = 2.2.sp,
                    color = LampAmber
                )
                Text(
                    text = name,
                    fontFamily = ArchivoFamily,
                    fontWeight = FontWeight.ExtraBold,
                    fontSize = JawonType.Heading,
                    lineHeight = 25.sp,
                    color = LabelCream,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis
                )
            }
        },
        text = {
            Text(
                text = when (albumCount) {
                    0 -> "The shelf comes off the wall. It is empty, so nothing moves."
                    1 -> "The album on it moves to the next shelf. Nothing is deleted."
                    else -> "The $albumCount albums on it move to the next shelf. " +
                        "Nothing is deleted."
                },
                style = JawonTextStyle.mono.copy(color = LabelDim),
                lineHeight = 19.sp
            )
        },
        confirmButton = {
            TextButton(onClick = onConfirm) {
                Text(
                    "REMOVE",
                    fontFamily = JetBrainsMonoFamily,
                    fontWeight = FontWeight.Bold,
                    fontSize = JawonType.Title,
                    letterSpacing = 1.6.sp,
                    color = LampAmber
                )
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) {
                Text(
                    "KEEP IT",
                    fontFamily = JetBrainsMonoFamily,
                    fontSize = JawonType.Title,
                    letterSpacing = 1.6.sp,
                    color = LabelDim
                )
            }
        }
    )
}

/** The small amber caption that names a control. */
@Composable
private fun FieldLabel(text: String) {
    Text(
        text = text,
        fontFamily = JetBrainsMonoFamily,
        fontWeight = FontWeight.Bold,
        fontSize = JawonType.Fine,
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
            fontSize = JawonType.Body,
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

/**
 * What to do with the records that are ticked.
 *
 * Sits under the header rather than floating over the list, because it is a mode the screen is
 * *in* rather than an action hovering above it — and a floating bar would cover the last row,
 * which is the one most likely to have just been ticked.
 *
 * The count is in the button rather than beside it. "REMOVE 12" is a sentence about what is
 * going to happen; a bare "REMOVE" next to a number somewhere else is two things to read.
 */
@Composable
private fun SelectionBar(count: Int, onCancel: () -> Unit, onRemove: () -> Unit) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .background(CrateRaised)
            .padding(horizontal = 20.dp, vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        Text(
            text = if (count == 1) "1 RECORD" else "$count RECORDS",
            style = JawonTextStyle.mono.copy(color = LabelCream, fontSize = JawonType.Body),
            modifier = Modifier.weight(1f)
        )
        Box(
            Modifier
                .border(1.dp, CrateEdge)
                .clickable(onClick = onCancel)
                .padding(horizontal = 14.dp, vertical = 10.dp)
        ) {
            Text(
                "CANCEL",
                fontFamily = JetBrainsMonoFamily,
                fontWeight = FontWeight.Bold,
                fontSize = JawonType.Caption,
                letterSpacing = 1.5.sp,
                color = LabelDim
            )
        }
        Box(
            Modifier
                // The destructive one carries the accent and the other does not, so the two are
                // told apart by more than the words on them.
                .border(1.dp, LampAmber)
                .clickable(onClick = onRemove)
                .padding(horizontal = 14.dp, vertical = 10.dp)
        ) {
            Text(
                "REMOVE $count",
                fontFamily = JetBrainsMonoFamily,
                fontWeight = FontWeight.Bold,
                fontSize = JawonType.Caption,
                letterSpacing = 1.5.sp,
                color = LampAmber
            )
        }
    }
}
