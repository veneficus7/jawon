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

/**
 * What the showroom needs from the rest of the app, and no more: which room you came from, the
 * switch that changes it, and a record for the CD's case to hold.
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

    /** Whether a shake flips rooms (Settings → Room). */
    val shakeToSwitch: StateFlow<Boolean> = preferences.shakeToSwitch

    /** Opens [format]'s room from now on: the same switch Settings' picker throws. */
    fun choose(format: Format) = preferences.setFormat(format)

    /**
     * The cover in the CD's case on its plinth: **the record you played last** — so the case in
     * the showroom is yours, not a stock one — or failing that the one added last. Null for a
     * library with no covers at all, when the case stands there with its blank inlay.
     */
    suspend fun caseCoverPath(): String? = withContext(Dispatchers.IO) {
        val covered = albumDao.listAll().filter { !it.caseCoverUri.isNullOrEmpty() }
        (covered.filter { it.lastPlayedAt != null }.maxByOrNull { it.lastPlayedAt ?: 0L }
            ?: covered.maxByOrNull { it.addedAt })?.caseCoverUri
    }
}
