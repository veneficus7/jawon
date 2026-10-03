package com.jawon.app.ui.editalbum

import com.jawon.app.ui.common.YEAR_KEYS
import com.jawon.app.ui.common.NAME_KEYS
import com.jawon.app.ui.common.FieldLabel
import com.jawon.app.ui.common.FieldFace
import com.jawon.app.ui.common.JawonTextField
import com.jawon.app.ui.theme.JawonType
import com.jawon.app.ui.common.ScreenBar
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Check
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.Slider
import androidx.compose.material3.SliderDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
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
import com.jawon.app.ui.theme.JawonTextStyle
import com.jawon.app.ui.theme.JetBrainsMonoFamily
import com.jawon.app.ui.theme.LabelCream
import com.jawon.app.ui.theme.LabelDim
import com.jawon.app.ui.theme.LampAmber
import java.util.Locale

/**
 * Editing a record that is already on the wall.
 *
 * Laid out as the object rather than as a form: the two pictures the album actually shows —
 * the case cover and the printed disc face — sit at the top at the proportions they are seen
 * at, then the lettering, then the underlight, then which shelf it lives on. What you change
 * up here is what changes out there.
 */
@Composable
fun EditAlbumScreen(
    onDone: () -> Unit,
    onCancel: () -> Unit,
    viewModel: EditAlbumViewModel = hiltViewModel()
) {
    val state by viewModel.uiState.collectAsStateWithLifecycle()

    val coverLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.PickVisualMedia()
    ) { viewModel.onCaseCoverPicked(it) }
    val discLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.PickVisualMedia()
    ) { viewModel.onDiscFacePicked(it) }
    val imagesOnly = PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly)

    LaunchedEffect(state.saved, state.missing) {
        // `missing` means the album was removed while this screen was open — there is
        // nothing to edit, so leave rather than sit on an empty form.
        if (state.saved || state.missing) onDone()
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(CrateBlack)
            .statusBarsPadding()
    ) {
        val canSave = !state.loading && !state.saving
        ScreenBar(title = "EDIT ALBUM", onBack = onCancel, backDescription = "Discard changes") {
            IconButton(onClick = viewModel::onSave, enabled = canSave) {
                Icon(
                    Icons.Default.Check,
                    contentDescription = "Save",
                    tint = if (canSave) LampAmber else CrateEdge
                )
            }
        }

        if (state.loading) {
            Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                CircularProgressIndicator(color = LampAmber)
            }
            return@Column
        }

        LazyColumn(
            modifier = Modifier
                .fillMaxSize()
                .navigationBarsPadding(),
            contentPadding = PaddingValues(
                start = 20.dp, end = 20.dp, top = 20.dp, bottom = 48.dp
            ),
            verticalArrangement = Arrangement.spacedBy(22.dp)
        ) {
            item {
                Row(horizontalArrangement = Arrangement.spacedBy(14.dp)) {
                    // Square, the shape it is printed at and the shape the case shows.
                    ArtworkPicker(
                        caption = "CASE COVER",
                        model = state.pendingCaseCover ?: state.caseCoverPath,
                        aspect = 1f,
                        modifier = Modifier.weight(1f),
                        onPick = { coverLauncher.launch(imagesOnly) }
                    )
                    ArtworkPicker(
                        caption = "DISC FACE",
                        model = state.pendingDiscFace ?: state.discFacePath,
                        aspect = 1f,
                        circular = true,
                        modifier = Modifier.weight(1f),
                        onPick = { discLauncher.launch(imagesOnly) }
                    )
                }
            }

            item {
                // Under the pictures and above the lettering, because it depends on the
                // lettering: the lookup uses whatever title and artist are typed below, so
                // correcting a wrong tag and pressing this again is the intended loop.
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(12.dp)
                ) {
                    Row(
                        modifier = Modifier
                            .border(1.dp, CrateEdge)
                            .clickable(enabled = !state.findingCover) {
                                viewModel.onFindCoverArt()
                            }
                            .padding(horizontal = 14.dp, vertical = 10.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        if (state.findingCover) {
                            CircularProgressIndicator(
                                color = LampAmber,
                                strokeWidth = 2.dp,
                                modifier = Modifier.size(14.dp)
                            )
                        }
                        Text(
                            text = if (state.findingCover) "LOOKING…" else "FIND COVER ART",
                            fontFamily = JetBrainsMonoFamily,
                            fontWeight = FontWeight.Bold,
                            fontSize = JawonType.Body,
                            letterSpacing = 1.6.sp,
                            color = if (state.findingCover) LabelDim else LampAmber
                        )
                    }
                    if (state.coverLookupFailed) {
                        // Says which way it failed. "Nothing happened" would leave the user
                        // pressing it again; this points at the fix, which is usually the tags.
                        Text(
                            "Nothing found — check the title and artist.",
                            style = JawonTextStyle.mono.copy(color = LabelDim, fontSize = JawonType.Caption)
                        )
                    }
                }
            }

            item {
                Column(verticalArrangement = Arrangement.spacedBy(14.dp)) {
                    JawonTextField(
                        state.title, viewModel::onTitleChanged,
                        label = "TITLE",
                        face = FieldFace.SLEEVE,
                        keyboardOptions = NAME_KEYS
                    )
                    JawonTextField(
                        state.artist, viewModel::onArtistChanged,
                        label = "ARTIST",
                        face = FieldFace.SLEEVE,
                        keyboardOptions = NAME_KEYS
                    )
                    JawonTextField(
                        state.year, viewModel::onYearChanged,
                        label = "YEAR",
                        keyboardOptions = YEAR_KEYS
                    )
                }
            }

            item {
                UnderlightPicker(
                    hue = state.spineHue,
                    onHueChanged = viewModel::onHueChanged,
                    onCleared = viewModel::onHueCleared
                )
            }

            item {
                ShelfPicker(
                    current = state.shelfName,
                    shelves = state.shelves,
                    onPick = viewModel::onShelfChanged
                )
            }
        }
    }
}

/**
 * One of the two pictures, shown at the size and shape the album wears it.
 *
 * [model] is deliberately `Any?` — Coil takes a `Uri` for something just picked and a `String`
 * path for something already stored, and the caller should not have to care which it is
 * holding.
 */
@Composable
private fun ArtworkPicker(
    caption: String,
    model: Any?,
    aspect: Float,
    modifier: Modifier = Modifier,
    circular: Boolean = false,
    onPick: () -> Unit
) {
    Column(modifier = modifier, verticalArrangement = Arrangement.spacedBy(8.dp)) {
        FieldLabel(caption)
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .aspectRatio(aspect)
                .background(CrateRaised)
                .border(1.dp, CrateEdge)
                .clickable(onClick = onPick),
            contentAlignment = Alignment.Center
        ) {
            if (model != null) {
                AsyncImage(
                    model = model,
                    contentDescription = caption,
                    contentScale = ContentScale.Crop,
                    modifier = Modifier
                        .fillMaxSize()
                        .padding(if (circular) 10.dp else 0.dp)
                )
                // The spindle hole, so a disc face reads as a disc rather than a square
                // picture that happens to be inset.
                if (circular) {
                    Box(
                        Modifier
                            .size(22.dp)
                            .background(CrateBlack)
                            .border(1.dp, CrateEdge)
                    )
                }
            } else {
                Text(
                    "TAP TO SET",
                    style = JawonTextStyle.mono.copy(color = LabelDim, fontSize = JawonType.Caption)
                )
            }
        }
    }
}

/**
 * The album's accent colour, which becomes the underlight its case stands in.
 *
 * Same rule as the importer's copy of this control: `0f` is not red, it is *unset*, and an
 * unset album takes its colour from the dominant hue of the cover — which is usually a better
 * answer than anything picked off a strip, and costs the user nothing. This exists for the
 * album where that guess comes out wrong.
 */
@Composable
private fun UnderlightPicker(hue: Float, onHueChanged: (Float) -> Unit, onCleared: () -> Unit) {
    val chosen = hue != 0f
    val swatch = if (chosen) Color.hsv(hue * 360f, 0.62f, 0.9f) else LabelDim

    Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            FieldLabel("UNDERLIGHT")
            Text(
                text = if (chosen) "USE THE COVER'S" else "FROM THE COVER",
                style = JawonTextStyle.mono.copy(
                    color = if (chosen) LampAmber else LabelDim,
                    fontSize = JawonType.Caption
                ),
                modifier = if (chosen) Modifier.clickable(onClick = onCleared) else Modifier
            )
        }
        // The strip is the control's own legend — it shows what dragging along it will do,
        // so no separate swatch is needed to explain the slider.
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .height(30.dp)
                .border(1.dp, CrateEdge)
                .background(
                    Brush.horizontalGradient(
                        (0..6).map { Color.hsv(it * 60f % 360f, 0.62f, 0.92f) }
                    )
                )
        )
        Slider(
            value = hue,
            // Nudged off zero on first touch: zero is the "unset" sentinel, and someone
            // dragging to the far left is asking for red, not for the cover's colour.
            onValueChange = { onHueChanged(it.coerceAtLeast(0.001f)) },
            valueRange = 0f..1f,
            colors = SliderDefaults.colors(
                thumbColor = swatch,
                activeTrackColor = swatch.copy(alpha = 0.7f),
                inactiveTrackColor = CrateEdge
            )
        )
    }
}

/** Which shelf the record lives on. One shelf per album — a CD cannot be in two places. */
@Composable
private fun ShelfPicker(
    current: String,
    shelves: List<com.jawon.app.data.local.Shelf>,
    onPick: (String) -> Unit
) {
    var open by remember { mutableStateOf(false) }
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        FieldLabel("SHELF")
        Box {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .border(1.dp, CrateEdge)
                    .background(CrateRaised)
                    .clickable { open = true }
                    .padding(horizontal = 14.dp, vertical = 14.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween
            ) {
                Text(
                    text = current.uppercase(Locale.ROOT),
                    fontFamily = JetBrainsMonoFamily,
                    fontSize = JawonType.Title,
                    letterSpacing = 1.6.sp,
                    color = LabelCream,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
                Text(
                    "CHANGE",
                    style = JawonTextStyle.mono.copy(color = LabelDim, fontSize = JawonType.Caption)
                )
            }
            DropdownMenu(
                expanded = open,
                onDismissRequest = { open = false },
                containerColor = CrateBlack,
                modifier = Modifier.border(1.dp, CrateEdge)
            ) {
                shelves.forEach { shelf ->
                    DropdownMenuItem(
                        text = {
                            Text(
                                shelf.name.uppercase(Locale.ROOT),
                                style = JawonTextStyle.mono.copy(color = LabelCream)
                            )
                        },
                        onClick = {
                            onPick(shelf.id)
                            open = false
                        }
                    )
                }
            }
        }
    }
}

