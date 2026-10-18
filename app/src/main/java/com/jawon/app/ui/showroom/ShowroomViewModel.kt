package com.jawon.app.ui.showroom

import androidx.lifecycle.ViewModel
import com.jawon.app.Format
import com.jawon.app.data.local.AlbumDao
import com.jawon.app.data.prefs.AppPreferences
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.withContext
import javax.inject.Inject

/** The record the showroom's exhibits wear: its cover, and its name for the single's label. */
data class LastRecord(val coverPath: String?, val title: String, val artist: String)

/**
 * What the showroom needs from the rest of the app, and no more: which room you came from, the
 * switch that changes it, and a record for the CD's case to hold and the vinyl's single to be.
 */
@HiltViewModel
class ShowroomViewModel @Inject constructor(
    private val preferences: AppPreferences,
    private val albumDao: AlbumDao
) : ViewModel() {

    /** The room the app is in — the one you pinched out of. */
    val format: StateFlow<Format> = preferences.format

    /** The pocket player's colour, as its own Settings chose it — the plinth's wears the same. */
    val pocketFinish: StateFlow<String?> = preferences.pocketFinish

    /** Whether the showroom's first-run hint has been put away (0.7.4). */
    val hintsSeen: StateFlow<Set<String>> = preferences.hintsSeen

    fun onHintSeen(id: String) = preferences.markHintSeen(id)

    /** Whether a shake flips rooms (Settings → Room). */
    val shakeToSwitch: StateFlow<Boolean> = preferences.shakeToSwitch

    /** Opens [format]'s room from now on: the same switch Settings' picker throws. */
    fun choose(format: Format) = preferences.setFormat(format)

    /**
     * What the exhibits wear: **the record you played last** — so the case in the showroom and the
     * single beside it are yours, not stock ones — or failing that the one added last; of the
     * records with a cover, if there are any. Its cover is null for a library with no covers at
     * all, when the case stands there with its blank inlay and the single's sleeve has the
     * record's name printed on it. Null for an empty library.
     */
    suspend fun lastRecord(): LastRecord? = withContext(Dispatchers.IO) {
        val all = albumDao.listAll()
        fun latest(of: List<com.jawon.app.data.local.Album>) =
            of.filter { it.lastPlayedAt != null }.maxByOrNull { it.lastPlayedAt ?: 0L } ?: of.maxByOrNull { it.addedAt }
        val album = latest(all.filter { !it.caseCoverUri.isNullOrEmpty() }) ?: latest(all) ?: return@withContext null
        LastRecord(album.caseCoverUri?.takeIf { it.isNotEmpty() }, album.title, album.artist)
    }
}
