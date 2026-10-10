package com.jawon.app.ui.addalbum

import android.net.Uri
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.jawon.app.data.importer.AlbumImportRepository
import com.jawon.app.data.importer.ImportPreview
import com.jawon.app.data.importer.ScanTidy
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import javax.inject.Inject

@HiltViewModel
class AddAlbumViewModel @Inject constructor(
    private val repository: AlbumImportRepository,
    private val opened: com.jawon.app.data.importer.OpenedMusic,
    private val playback: com.jawon.app.data.playback.PlaybackRepository
) : ViewModel() {

    private val _uiState = MutableStateFlow<AddAlbumUiState>(AddAlbumUiState.Idle)
    val uiState: StateFlow<AddAlbumUiState> = _uiState.asStateFlow()

    /** The scan in flight, so going back can stop it rather than have it land on the chooser later. */
    private var scanJob: kotlinx.coroutines.Job? = null

    /** What is being added was handed over by another app, and is played once it is on a shelf. */
    private var fromOutside = false

    init {
        // "Open with Jawon": whatever is waiting when this screen comes up, and whatever arrives
        // while it is up — a second file opened from the file manager lands here too.
        viewModelScope.launch {
            opened.pending.collect { waiting ->
                if (waiting.isNotEmpty()) onOpened(opened.take())
            }
        }
    }

    /**
     * Music handed over by another app. One song is simply put on a shelf and played — nobody who
     * taps a song in a file manager wants a form first. Anything more is shown, as a scan is.
     */
    private fun onOpened(uris: List<Uri>) {
        if (uris.isEmpty()) return
        fromOutside = true
        _uiState.value = AddAlbumUiState.Unpacking(0)
        scanJob?.cancel()
        scanJob = viewModelScope.launch {
            _uiState.value = try {
                val found = repository.scanOpened(uris) { songs ->
                    _uiState.value = AddAlbumUiState.Unpacking(songs)
                }
                val only = found.singleOrNull()
                when {
                    found.isEmpty() -> AddAlbumUiState.Error("There was no music in that")
                    only != null && only.tracks.size == 1 -> {
                        _uiState.value = AddAlbumUiState.Saving
                        val albumId = repository.commitAsFound(only)
                        if (albumId == null) {
                            AddAlbumUiState.Error("Couldn't add that song")
                        } else {
                            playback.playOpened(albumId, only.tracks.first().title)
                            AddAlbumUiState.Done(albumId)
                        }
                    }
                    only != null -> singlePreview(only)
                    else -> {
                        val (ordered, singlesStart) = ordered(found)
                        val selected = ordered.indices.filterNot { ordered[it].alreadyInLibrary }.toSet()
                        AddAlbumUiState.MultiPreview(
                            albums = ordered,
                            selected = selected,
                            singlesStart = singlesStart,
                            coversToLookUp = repository.coversToLookUp(selected.map { ordered[it] })
                        )
                    }
                }
            } catch (e: kotlinx.coroutines.CancellationException) {
                throw e
            } catch (e: Exception) {
                AddAlbumUiState.Error(e.message ?: "Couldn't open that")
            }
        }
    }

    fun onFolderPicked(uri: Uri) {
        _uiState.value = AddAlbumUiState.Scanning
        scanJob?.cancel()
        scanJob = viewModelScope.launch {
            _uiState.value = try {
                val found = repository.scanTree(uri)
                when {
                    found.isEmpty() ->
                        AddAlbumUiState.Error("No audio files in that folder")
            // More than one album under the picked folder — offer the batch, since
            // nobody is going to fill in a form forty times.
                    // Everything is ticked by default except what the library already has.
                    // Pointing at a folder you have imported before is the ordinary way to end
                    // up with two copies of a record, and the two are indistinguishable
                    // afterwards. Still listed and still tickable — the user asked for this
                    // folder specifically, and the match is a heuristic that can be wrong.
                    found.size > 1 -> {
                        val (ordered, singlesStart) = ordered(found)
                        val selected = ordered.indices.filterNot { ordered[it].alreadyInLibrary }.toSet()
                        AddAlbumUiState.MultiPreview(
                            albums = ordered,
                            selected = selected,
                            singlesStart = singlesStart,
                            coversToLookUp = repository.coversToLookUp(selected.map { ordered[it] })
                        )
                    }
                    else -> singlePreview(found.first())
                }
            } catch (e: Exception) {
                AddAlbumUiState.Error(e.message ?: "Couldn't scan that folder")
            }
        }
    }

    /**
     * Find everything on the phone, rather than asking the user to point at folders.
     *
     * Always lands on the checklist, even for a single album found, because the user did not
     * choose this album — the phone did — and the first thing they need is to see what was
     * found and untick what they do not want. That is a different question from "here is the
     * folder you picked, confirm its details".
     *
     * Nothing is ticked by default. A folder someone deliberately navigated to is a statement
     * of intent; a whole-device scan is a proposal, and pre-ticking two hundred albums on
     * someone's behalf makes the safe move the laborious one.
     */
    fun onScanDevice() {
        _uiState.value = AddAlbumUiState.Scanning
        scanJob?.cancel()
        scanJob = viewModelScope.launch {
            _uiState.value = try {
                val found = repository.scanDevice()
                if (found.isEmpty()) {
                    AddAlbumUiState.Error("No new music found on this phone")
                } else {
                    val (ordered, singlesStart) = ordered(found)
                    AddAlbumUiState.MultiPreview(albums = ordered, selected = emptySet(), singlesStart = singlesStart)
                }
            } catch (e: Exception) {
                AddAlbumUiState.Error(e.message ?: "Couldn't read this phone's music")
            }
        }
    }

    /**
     * Tick or untick every album at once — the only sane control for a list this long. The albums
     * only: the singles under them have a switch of their own ([onToggleSingles]), since "all of my
     * records" rarely means every stray song on the phone too.
     */
    fun onToggleAll() = toggleRange { (0 until it.singlesStart).toList() }

    /** Tick or untick every single. */
    fun onToggleSingles() = toggleRange { (it.singlesStart until it.albums.size).toList() }

    private fun toggleRange(range: (AddAlbumUiState.MultiPreview) -> List<Int>) {
        val current = _uiState.value
        if (current !is AddAlbumUiState.MultiPreview) return
        val these = range(current)
        val selected = if (these.all { it in current.selected }) current.selected - these.toSet()
        else current.selected + these
        _uiState.value = current.copy(selected = selected, coversToLookUp = coverCount(current, selected))
    }

    /**
     * Back to the four ways in (0.7.4). Back from what a scan found used to leave for the room,
     * which threw the scan away to try a folder instead.
     */
    fun backToStart() {
        scanJob?.cancel()
        scanJob = null
        _uiState.value = AddAlbumUiState.Idle
    }

    private fun ordered(found: List<ImportPreview>): Pair<List<ImportPreview>, Int> =
        ScanTidy.albumsThenSingles(found, { it.tracks.size }, { it.suggestedTitle })

    fun onToggleAlbum(index: Int) {
        val current = _uiState.value
        if (current !is AddAlbumUiState.MultiPreview) return
        val selected = if (index in current.selected) current.selected - index
        else current.selected + index
        _uiState.value = current.copy(selected = selected, coversToLookUp = coverCount(current, selected))
    }

    fun onImportSelected() {
        val current = _uiState.value
        if (current !is AddAlbumUiState.MultiPreview || current.selected.isEmpty()) return
        val chosen = current.selected.sorted().mapNotNull { current.albums.getOrNull(it) }
        _uiState.value = AddAlbumUiState.Saving
        viewModelScope.launch {
            _uiState.value = try {
                AddAlbumUiState.DoneMany(repository.commitAll(chosen), chosen.size)
            } catch (e: Exception) {
                AddAlbumUiState.Error(e.message ?: "Couldn't import those albums")
            }
        }
    }

    /** How many of the ticked albums would need their cover fetched. Zero when off. */
    private fun coverCount(state: AddAlbumUiState.MultiPreview, selected: Set<Int>): Int =
        repository.coversToLookUp(selected.mapNotNull { state.albums.getOrNull(it) })

    private fun singlePreview(preview: ImportPreview): AddAlbumUiState.Preview =
        AddAlbumUiState.Preview(
            preview = preview,
            title = preview.suggestedTitle,
            artist = preview.suggestedArtist,
            year = preview.suggestedYear?.toString().orEmpty(),
            // Already filled in when the tracks carried their own art, which is the
            // common case for anything ripped or downloaded. The user can replace it;
            // they just no longer have to go and find it.
                    //
            // Only the case cover. The disc face is left unset on purpose — the disc
            // already falls back to the cover when it renders (see DiscArt.bakeFace),
            // so seeding it here would buy nothing and would destroy the distinction
            // between "no disc face" and "a disc face that happens to match".
            caseCoverUri = preview.embeddedCoverUri,
            discFaceUri = null,
            spineHue = 0f
        )

    fun onTitleChanged(value: String) = updatePreview { it.copy(title = value) }
    fun onArtistChanged(value: String) = updatePreview { it.copy(artist = value) }
    fun onYearChanged(value: String) = updatePreview { it.copy(year = value) }
    fun onCaseCoverPicked(uri: Uri?) = updatePreview { it.copy(caseCoverUri = uri) }
    fun onDiscFacePicked(uri: Uri?) = updatePreview { it.copy(discFaceUri = uri) }

    /** The album's accent, which becomes its underlight on the shelf. */
    fun onSpineHueChanged(value: Float) = updatePreview { it.copy(spineHue = value) }

    private fun updatePreview(transform: (AddAlbumUiState.Preview) -> AddAlbumUiState.Preview) {
        val current = _uiState.value
        if (current is AddAlbumUiState.Preview) _uiState.value = transform(current)
    }

    fun onSave() {
        val current = _uiState.value
        if (current !is AddAlbumUiState.Preview) return
        _uiState.value = AddAlbumUiState.Saving
        viewModelScope.launch {
            _uiState.value = try {
                val albumId = repository.commit(
                    preview = current.preview,
                    title = current.title,
                    artist = current.artist,
                    year = current.year.toIntOrNull(),
                    caseCoverUri = current.caseCoverUri,
                    discFaceUri = current.discFaceUri,
                    spineHue = current.spineHue
                )
                // An album opened from outside was opened to be heard.
                if (fromOutside) playback.play(albumId, com.jawon.app.data.playback.QueueBuilder.From.TheTop)
                AddAlbumUiState.Done(albumId)
            } catch (e: Exception) {
                AddAlbumUiState.Error(e.message ?: "Couldn't save that album")
            }
        }
    }
}
