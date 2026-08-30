package com.jawon.app.data.prefs

import android.content.Context
import androidx.core.content.edit
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import javax.inject.Inject
import javax.inject.Singleton

/**
 * The handful of settings that are genuinely user preferences rather than library data.
 *
 * `SharedPreferences` rather than DataStore: there are two booleans here, they are read on a
 * background path, and adding a dependency and a serializer for that would be ceremony. If
 * this grows past a few flags it should move.
 */
@Singleton
class AppPreferences @Inject constructor(@param:ApplicationContext context: Context) {

    private val prefs = context.getSharedPreferences("jawon", Context.MODE_PRIVATE)

    private val _fetchCoverArt = MutableStateFlow(prefs.getBoolean(KEY_FETCH_COVER_ART, true))

    /**
     * Whether to look an album's cover up online when its own files do not carry one.
     *
     * **On by default, and this is the one default in the app worth arguing about.** Jawon is
     * local-first, and this is the only thing it does that leaves the device — so the case for
     * defaulting it off is real. It is on because the alternative is worse in practice: a user
     * who imports their library and gets a wall of blank cases has been failed by a feature
     * that exists and was switched off for them, and the request that prompted it was
     * explicitly "take album cover arts from some database".
     *
     * What makes that defensible is that it is never hidden. The import screen says it is
     * going to happen before it happens, the switch is in Settings, and what gets sent is one
     * album title and one artist name for records that have no art — no library listing, no
     * device id, no account. See [com.jawon.app.data.importer.CoverArtFetcher].
     */
    val fetchCoverArt: StateFlow<Boolean> = _fetchCoverArt.asStateFlow()

    fun setFetchCoverArt(enabled: Boolean) {
        prefs.edit { putBoolean(KEY_FETCH_COVER_ART, enabled) }
        _fetchCoverArt.value = enabled
    }

    private companion object {
        const val KEY_FETCH_COVER_ART = "fetch_cover_art"
    }
}
