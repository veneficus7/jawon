package com.jawon.app.ui.settings

import androidx.lifecycle.ViewModel
import com.jawon.app.data.prefs.AppPreferences
import com.jawon.app.ui.theme.RoomScheme
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.StateFlow
import javax.inject.Inject

@HiltViewModel
class SettingsViewModel @Inject constructor(
    private val preferences: AppPreferences
) : ViewModel() {

    val fetchCoverArt: StateFlow<Boolean> = preferences.fetchCoverArt

    val roomScheme: StateFlow<RoomScheme> = preferences.roomScheme

    fun onFetchCoverArtChanged(enabled: Boolean) = preferences.setFetchCoverArt(enabled)

    fun onRoomSchemeChanged(scheme: RoomScheme) = preferences.setRoomScheme(scheme)
}
