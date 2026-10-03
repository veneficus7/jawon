package com.jawon.app.ui.addalbum

import com.jawon.app.ui.theme.JawonType
import com.jawon.app.ui.common.ScreenBar
import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
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
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Check
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.LocalTextStyle
import androidx.compose.material3.Slider
import androidx.compose.material3.SliderDefaults
import androidx.compose.material3.Text
import androidx.compose.material3.TextField
import androidx.compose.material3.TextFieldDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.content.ContextCompat
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import coil3.compose.AsyncImage
import com.jawon.app.data.importer.TrackMetadata
import com.jawon.app.ui.common.formatDuration
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
 * Adding records to the room.
 *
 * Every other screen in the app was brought into the house style — the flat boards, the mono
 * lettering, the square hairline borders — and this one was left on stock Material, which made
 * the first thing a new user ever sees the one screen that looks like a different app.
 */
@Composable
fun AddAlbumScreen(
    onDone: (albumId: String) -> Unit,
    onCancel: () -> Unit,
    onBrowseArchive: () -> Unit = {},
    onImportCd: () -> Unit = {},
    /**
     * A folder a CD was just ripped into, handed back by the CD screen.
     *
     * Deliberately routed through the ordinary folder import rather than given an import path of
     * its own: a rip produces a folder of audio files, which is exactly what this screen already
     * knows how to take. Nothing about a ripped CD is special once the discs are files.
     */
    rippedFolder: android.net.Uri? = null,
    onRippedFolderConsumed: () -> Unit = {},
    viewModel: AddAlbumViewModel = hiltViewModel()
) {
    val uiState by viewModel.uiState.collectAsState()
    val context = LocalContext.current

    val folderLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.OpenDocumentTree()
    ) { uri -> uri?.let(viewModel::onFolderPicked) }

    // Declining is not an error state: the folder picker still works without any permission,
    // which is how the app ran until the device scan existed, so a refusal just leaves the
    // user on the screen with the other option in front of them.
    val audioPermissionLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { granted -> if (granted) viewModel.onScanDevice() }

    LaunchedEffect(uiState) {
        val state = uiState
        if (state is AddAlbumUiState.Done) onDone(state.albumId)
    }

    // Consumed immediately, so coming back to this screen later does not re-import the same
    // folder — the saved-state value outlives the navigation that carried it.
    LaunchedEffect(rippedFolder) {
        rippedFolder?.let {
            viewModel.onFolderPicked(it)
            onRippedFolderConsumed()
        }
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(CrateBlack)
            .statusBarsPadding()
    ) {
        ScreenBar(
            title = when (uiState) {
                is AddAlbumUiState.MultiPreview -> "WHAT WAS FOUND"
                is AddAlbumUiState.Preview -> "ONE ALBUM"
                else -> "ADD ALBUM"
            },
            onBack = onCancel,
            backDescription = "Back to the room"
        )

        when (val state = uiState) {
            is AddAlbumUiState.Idle -> IdleContent(
                onChooseFolder = { folderLauncher.launch(null) },
                onScanDevice = {
                    // Already granted, or on a build that needs no grant — go straight to
                    // the scan rather than showing a dialog that answers itself.
                    if (hasAudioPermission(context)) viewModel.onScanDevice()
                    else audioPermissionLauncher.launch(audioPermission())
                },
                onBrowseArchive = onBrowseArchive,
                onImportCd = onImportCd
            )
            is AddAlbumUiState.Scanning -> Working("READING YOUR MUSIC")
            is AddAlbumUiState.Preview -> PreviewContent(
                state = state,
                onTitleChanged = viewModel::onTitleChanged,
                onArtistChanged = viewModel::onArtistChanged,
                onYearChanged = viewModel::onYearChanged,
                onCaseCoverPicked = viewModel::onCaseCoverPicked,
                onDiscFacePicked = viewModel::onDiscFacePicked,
                onSpineHueChanged = viewModel::onSpineHueChanged,
                onSave = viewModel::onSave
            )
            is AddAlbumUiState.MultiPreview -> MultiPreviewContent(
                state = state,
                onToggle = viewModel::onToggleAlbum,
                onToggleAll = viewModel::onToggleAll,
                onImport = viewModel::onImportSelected
            )
            is AddAlbumUiState.DoneMany -> {
                // Nothing to open — a batch has no single album to land on, so this returns
                // to the room where the new records are now on the shelf.
                LaunchedEffect(state) { onCancel() }
            }
            is AddAlbumUiState.Saving -> Working("PUTTING THEM ON THE SHELF")
            is AddAlbumUiState.Done -> Unit // handled by LaunchedEffect above
            is AddAlbumUiState.Error -> Problem(state.message, onBack = onCancel)
        }
    }
}

/** Which permission grants access to the user's audio, on this version of Android. */
private fun audioPermission(): String =
    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
        Manifest.permission.READ_MEDIA_AUDIO
    } else {
        Manifest.permission.READ_EXTERNAL_STORAGE
    }

private fun hasAudioPermission(context: Context): Boolean =
    ContextCompat.checkSelfPermission(context, audioPermission()) ==
        PackageManager.PERMISSION_GRANTED

/**
 * The three ways in, weighted.
 *
 * Scanning is the answer for almost everyone — the music is already on the phone and Android
 * already knows where it is — so it gets the whole card. Picking a folder asks the user a
 * question about their filesystem, and is offered underneath as the thing it actually is: the
 * route to music a scan cannot see.
 *
 * The Archive is last and is a different kind of thing entirely: the first route in this app
 * that adds music the user did not already have. It is offered here rather than as a fourth
 * button in the room's chrome, because whatever it is, it is a way of getting a record onto a
 * shelf — which is what this screen is for.
 */
@Composable
private fun IdleContent(
    onChooseFolder: () -> Unit,
    onScanDevice: () -> Unit,
    onBrowseArchive: () -> Unit,
    onImportCd: () -> Unit
) {
    Column(
        modifier = Modifier
            .fillMaxSize()
            .navigationBarsPadding()
            .padding(horizontal = 24.dp),
        verticalArrangement = Arrangement.Center
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .border(1.dp, LampAmber.copy(alpha = 0.5f))
                .clickable(onClick = onScanDevice)
                .background(LampAmber.copy(alpha = 0.05f))
                .padding(22.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp)
        ) {
            Text(
                text = "SCAN THIS PHONE",
                fontFamily = ArchivoFamily,
                fontWeight = FontWeight.ExtraBold,
                fontSize = JawonType.Heading,
                letterSpacing = 1.sp,
                color = LabelCream
            )
            Text(
                // **A chooser says what each route IS, not how it works.**
                //
                // All four of these carried a paragraph, always on screen, one under the
                // other: the first thing anyone saw when adding music was 172 words of prose.
                // The owner, having already had the same conversation about Settings: "also
                // add album page has lots of text."
                //
                // What the detail was for is real, and it still gets said — on the screen the
                // route actually leads to, where it is about the thing in front of you rather
                // than about a button you have not pressed.
                text = "Finds the music already on this phone. Nothing to point at.",
                style = JawonTextStyle.mono.copy(color = LabelDim),
                lineHeight = 18.sp
            )
        }

        Box(
            Modifier
                .padding(vertical = 28.dp)
                .fillMaxWidth()
                .height(1.dp)
                .background(CrateEdge)
        )

        Column(
            modifier = Modifier
                .fillMaxWidth()
                .border(1.dp, CrateEdge)
                .clickable(onClick = onChooseFolder)
                .padding(22.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp)
        ) {
            Text(
                text = "CHOOSE A FOLDER",
                fontFamily = ArchivoFamily,
                fontWeight = FontWeight.ExtraBold,
                fontSize = JawonType.Subhead,
                letterSpacing = 1.sp,
                color = LabelDim
            )
            Text(
                // Not a fallback for the stubborn: MediaStore only indexes what the media
                // scanner has seen, so this is the only route to some music.
                text = "For a memory card or cloud storage, which a scan cannot see.",
                style = JawonTextStyle.mono.copy(color = LabelDim, fontSize = JawonType.Caption),
                lineHeight = 16.sp
            )
        }

        Column(
            modifier = Modifier
                .padding(top = 18.dp)
                .fillMaxWidth()
                .border(1.dp, CrateEdge)
                .clickable(onClick = onImportCd)
                .padding(22.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp)
        ) {
            Text(
                text = "IMPORT A CD",
                fontFamily = ArchivoFamily,
                fontWeight = FontWeight.ExtraBold,
                fontSize = JawonType.Subhead,
                letterSpacing = 1.sp,
                color = LabelDim
            )
            Text(
                // The power supply stays, cut to a clause: it is the one thing here that
                // is a GOTCHA rather than an explanation, and finding it out by having a
                // drive fail to enumerate is the worst way to learn it.
                text = "Read a disc from a USB drive. Most drives need their own power.",
                style = JawonTextStyle.mono.copy(color = LabelDim, fontSize = JawonType.Caption),
                lineHeight = 16.sp
            )
        }

        Column(
            modifier = Modifier
                .padding(top = 18.dp)
                .fillMaxWidth()
                .border(1.dp, CrateEdge)
                .clickable(onClick = onBrowseArchive)
                .padding(22.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp)
        ) {
            Text(
                text = "THE RECORD SHOP",
                fontFamily = ArchivoFamily,
                fontWeight = FontWeight.ExtraBold,
                fontSize = JawonType.Subhead,
                letterSpacing = 1.sp,
                color = LabelDim
            )
            Text(
                text = "Live concerts and free releases from the Internet Archive.",
                style = JawonTextStyle.mono.copy(color = LabelDim, fontSize = JawonType.Caption),
                lineHeight = 16.sp
            )
        }
    }
}

@Composable
private fun Working(label: String) {
    Column(
        modifier = Modifier.fillMaxSize(),
        verticalArrangement = Arrangement.Center,
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        CircularProgressIndicator(color = LampAmber, strokeWidth = 2.dp)
        Text(
            text = label,
            style = JawonTextStyle.mono.copy(color = LabelDim),
            modifier = Modifier.padding(top = 18.dp)
        )
    }
}

@Composable
private fun Problem(message: String, onBack: () -> Unit) {
    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(horizontal = 32.dp),
        verticalArrangement = Arrangement.Center,
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        Text(
            text = message,
            style = JawonTextStyle.mono.copy(color = LabelDim),
            lineHeight = 19.sp
        )
        Row(
            modifier = Modifier
                .padding(top = 20.dp)
                .border(1.dp, CrateEdge)
                .clickable(onClick = onBack)
                .padding(horizontal = 16.dp, vertical = 9.dp)
        ) {
            Text(
                "BACK TO THE ROOM",
                fontFamily = JetBrainsMonoFamily,
                fontWeight = FontWeight.Bold,
                fontSize = JawonType.Body,
                letterSpacing = 1.6.sp,
                color = LampAmber
            )
        }
    }
}

/**
 * What a scan or a folder of folders turned up.
 *
 * **No text fields, on purpose.** Someone importing a library is not going to type forty
 * titles, and the tags are almost always right — so this shows what was found, lets them untick
 * what they do not want, and imports the rest in one press. Anything wrong is fixable on the
 * album afterwards; making the user fill in a form forty times is not.
 */
@Composable
private fun MultiPreviewContent(
    state: AddAlbumUiState.MultiPreview,
    onToggle: (Int) -> Unit,
    onToggleAll: () -> Unit,
    onImport: () -> Unit
) {
    val allTicked = state.selectedCount == state.albums.size

    Column(modifier = Modifier.fillMaxSize()) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(start = 24.dp, end = 24.dp, top = 20.dp, bottom = 12.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Column(Modifier.weight(1f)) {
                Text(
                    text = "${state.albums.size} ALBUMS",
                    fontFamily = ArchivoFamily,
                    fontWeight = FontWeight.ExtraBold,
                    fontSize = JawonType.Heading,
                    color = LabelCream
                )
                Text(
                    text = "Tick what you want on your shelves.",
                    style = JawonTextStyle.mono.copy(color = LabelDim, fontSize = JawonType.Caption)
                )
            }
            // A phone scan can return hundreds. Ticking them one at a time is not a
            // reasonable thing to ask, and this is also the fastest route to "all of it",
            // which is what someone setting the app up for the first time wants.
            Row(
                modifier = Modifier
                    .border(1.dp, CrateEdge)
                    .clickable(onClick = onToggleAll)
                    .padding(horizontal = 12.dp, vertical = 8.dp)
            ) {
                Text(
                    text = if (allTicked) "NONE" else "ALL",
                    fontFamily = JetBrainsMonoFamily,
                    fontWeight = FontWeight.Bold,
                    fontSize = JawonType.Body,
                    letterSpacing = 1.6.sp,
                    color = LampAmber
                )
            }
        }

        LazyColumn(
            modifier = Modifier
                .weight(1f)
                .padding(horizontal = 24.dp)
        ) {
            itemsIndexed(state.albums) { index, album ->
                AlbumChoice(
                    title = album.suggestedTitle,
                    artist = album.suggestedArtist,
                    trackCount = album.tracks.size,
                    hasCover = album.embeddedCoverUri != null,
                    alreadyInLibrary = album.alreadyInLibrary,
                    checked = index in state.selected,
                    onToggle = { onToggle(index) }
                )
            }
        }

        Column(
            modifier = Modifier
                .fillMaxWidth()
                .navigationBarsPadding()
                .padding(horizontal = 24.dp, vertical = 16.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp)
        ) {
            // Said before it happens, not disclosed afterwards. This is the only thing the
            // app sends anywhere, and the user is looking at the screen that will send it.
            if (state.coversToLookUp > 0) {
                Text(
                    text = (if (state.coversToLookUp == 1) "1 of these has no artwork of its own. "
                    else "${state.coversToLookUp} of these have no artwork of their own. ") +
                        "Jawon will look " + (if (state.coversToLookUp == 1) "it" else "those") +
                        " up online, sending just the album and artist name.",
                    style = JawonTextStyle.mono.copy(color = LabelDim, fontSize = JawonType.Caption),
                    lineHeight = 16.sp
                )
            }
            val enabled = state.selectedCount > 0
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .border(1.dp, if (enabled) LampAmber else CrateEdge)
                    .background(
                        if (enabled) LampAmber.copy(alpha = 0.08f) else Color.Transparent
                    )
                    .clickable(enabled = enabled, onClick = onImport)
                    .padding(vertical = 14.dp),
                horizontalArrangement = Arrangement.Center
            ) {
                Text(
                    text = when {
                        state.selectedCount == 0 -> "NOTHING TICKED"
                        state.selectedCount == 1 -> "ADD 1 ALBUM"
                        else -> "ADD ${state.selectedCount} ALBUMS"
                    },
                    fontFamily = JetBrainsMonoFamily,
                    fontWeight = FontWeight.Bold,
                    fontSize = JawonType.Title,
                    letterSpacing = 1.8.sp,
                    color = if (enabled) LampAmber else LabelDim
                )
            }
        }
    }
}

/** One album in the found list. The whole row toggles; the box is an indicator, not a target. */
@Composable
private fun AlbumChoice(
    title: String,
    artist: String,
    trackCount: Int,
    hasCover: Boolean,
    /** The library already holds this title and artist. See [ImportPreview.alreadyInLibrary]. */
    alreadyInLibrary: Boolean,
    checked: Boolean,
    onToggle: () -> Unit
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onToggle)
            .padding(vertical = 11.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(14.dp)
    ) {
        Box(
            modifier = Modifier
                .size(20.dp)
                .border(1.dp, if (checked) LampAmber else CrateEdge)
                .background(if (checked) LampAmber else Color.Transparent),
            contentAlignment = Alignment.Center
        ) {
            if (checked) {
                Icon(
                    Icons.Default.Check,
                    contentDescription = null,
                    tint = CrateBlack,
                    modifier = Modifier.size(14.dp)
                )
            }
        }
        Column(Modifier.weight(1f)) {
            Text(
                text = title,
                fontFamily = ArchivoFamily,
                fontWeight = FontWeight.ExtraBold,
                fontSize = JawonType.Row,
                color = if (checked) LabelCream else LabelDim,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
            Text(
                // The duplicate note goes first, because it is the one thing here that would
                // change someone's mind about the row.
                text = (if (alreadyInLibrary) "ALREADY ON A SHELF · " else "") +
                    artist.uppercase(Locale.ROOT) + " · " + trackCount +
                    (if (trackCount == 1) " TRACK" else " TRACKS") +
                    (if (hasCover) " · COVER" else ""),
                style = JawonTextStyle.mono.copy(
                    color = if (alreadyInLibrary) LampAmber else LabelDim,
                    fontSize = JawonType.Caption
                ),
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
        }
    }
}

/** The single-album form, for someone who picked one folder and may want to correct it. */
@Composable
private fun PreviewContent(
    state: AddAlbumUiState.Preview,
    onTitleChanged: (String) -> Unit,
    onArtistChanged: (String) -> Unit,
    onYearChanged: (String) -> Unit,
    onCaseCoverPicked: (Uri?) -> Unit,
    onDiscFacePicked: (Uri?) -> Unit,
    onSpineHueChanged: (Float) -> Unit,
    onSave: () -> Unit
) {
    val caseCoverLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.PickVisualMedia()
    ) { uri -> onCaseCoverPicked(uri) }
    val discFaceLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.PickVisualMedia()
    ) { uri -> onDiscFacePicked(uri) }
    val imageOnlyRequest = PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly)

    LazyColumn(
        modifier = Modifier
            .fillMaxSize()
            .navigationBarsPadding(),
        contentPadding = PaddingValues(start = 24.dp, end = 24.dp, top = 20.dp, bottom = 40.dp),
        verticalArrangement = Arrangement.spacedBy(20.dp)
    ) {
        item {
            Row(horizontalArrangement = Arrangement.spacedBy(14.dp)) {
                CoverPicker(
                    label = "CASE COVER",
                    uri = state.caseCoverUri,
                    modifier = Modifier.weight(1f),
                    onPick = { caseCoverLauncher.launch(imageOnlyRequest) }
                )
                CoverPicker(
                    label = "DISC FACE",
                    uri = state.discFaceUri,
                    modifier = Modifier.weight(1f),
                    onPick = { discFaceLauncher.launch(imageOnlyRequest) }
                )
            }
        }
        item {
            Column(verticalArrangement = Arrangement.spacedBy(14.dp)) {
                LabelledField("TITLE", state.title, onTitleChanged)
                LabelledField("ARTIST", state.artist, onArtistChanged)
                LabelledField("YEAR", state.year, onYearChanged, numeric = true)
            }
        }
        item { UnderlightPicker(hue = state.spineHue, onHueChanged = onSpineHueChanged) }
        item {
            FieldLabel(
                "${state.preview.tracks.size} TRACKS" +
                    if (state.preview.failedCount > 0) " · ${state.preview.failedCount} SKIPPED"
                    else ""
            )
        }
        items(state.preview.tracks) { track -> TrackRow(track) }
        item {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(top = 8.dp)
                    .border(1.dp, LampAmber)
                    .background(LampAmber.copy(alpha = 0.08f))
                    .clickable(onClick = onSave)
                    .padding(vertical = 14.dp),
                horizontalArrangement = Arrangement.Center
            ) {
                Text(
                    "PUT IT ON THE SHELF",
                    fontFamily = JetBrainsMonoFamily,
                    fontWeight = FontWeight.Bold,
                    fontSize = JawonType.Title,
                    letterSpacing = 1.8.sp,
                    color = LampAmber
                )
            }
        }
    }
}

/**
 * The album's accent colour, which becomes the underlight it stands in on the shelf.
 *
 * **Off by default, and that is the point.** Jawon already takes the dominant hue from the
 * cover art, which is usually a better answer than anything picked off a slider and asks the
 * user for nothing. This exists for the album where that guess is wrong, so it opens on "from
 * the cover" and only starts overriding once the strip is actually touched.
 */
@Composable
private fun UnderlightPicker(hue: Float, onHueChanged: (Float) -> Unit) {
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
                modifier = if (chosen) Modifier.clickable { onHueChanged(0f) } else Modifier
            )
        }
        // The wheel itself, so the control shows what it does rather than needing a swatch
        // beside it to explain.
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
            // Nudged off zero on first touch, because zero is the sentinel for "unset" and a
            // user dragging to the far left should still get red, not the cover's colour.
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

@Composable
private fun CoverPicker(
    label: String,
    uri: Uri?,
    modifier: Modifier = Modifier,
    onPick: () -> Unit
) {
    Column(modifier = modifier, verticalArrangement = Arrangement.spacedBy(8.dp)) {
        FieldLabel(label)
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .aspectRatio(1f)
                .background(CrateRaised)
                .border(1.dp, CrateEdge)
                .clickable(onClick = onPick),
            contentAlignment = Alignment.Center
        ) {
            if (uri != null) {
                AsyncImage(
                    model = uri,
                    contentDescription = label,
                    contentScale = ContentScale.Crop,
                    modifier = Modifier.fillMaxSize()
                )
            } else {
                Text(
                    "TAP TO SET",
                    style = JawonTextStyle.mono.copy(color = LabelDim, fontSize = JawonType.Caption)
                )
            }
        }
    }
}

@Composable
private fun LabelledField(
    label: String,
    value: String,
    onValueChange: (String) -> Unit,
    numeric: Boolean = false
) {
    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
        FieldLabel(label)
        TextField(
            value = value,
            onValueChange = onValueChange,
            singleLine = true,
            keyboardOptions = KeyboardOptions(
                keyboardType = if (numeric) KeyboardType.Number else KeyboardType.Text
            ),
            textStyle = LocalTextStyle.current.copy(
                fontFamily = if (numeric) JetBrainsMonoFamily else ArchivoFamily,
                fontWeight = if (numeric) FontWeight.Medium else FontWeight.ExtraBold,
                fontSize = if (numeric) JawonType.Title else JawonType.Subhead,
                color = LabelCream
            ),
            colors = TextFieldDefaults.colors(
                focusedContainerColor = CrateRaised,
                unfocusedContainerColor = CrateRaised,
                focusedIndicatorColor = LampAmber,
                unfocusedIndicatorColor = CrateEdge,
                cursorColor = LampAmber
            ),
            modifier = Modifier.fillMaxWidth()
        )
    }
}

@Composable
private fun TrackRow(track: TrackMetadata) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 7.dp),
        horizontalArrangement = Arrangement.spacedBy(12.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Text(
            text = track.title,
            style = JawonTextStyle.mono.copy(color = LabelCream, fontSize = JawonType.Body),
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.weight(1f)
        )
        Text(
            text = formatDuration(track.durationMs),
            style = JawonTextStyle.mono.copy(color = LabelDim, fontSize = JawonType.Body)
        )
    }
}

/** The small amber caption that names a control. Shared shape with the edit screen. */
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
