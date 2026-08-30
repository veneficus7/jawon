package com.jawon.app.ui.settings

import androidx.lifecycle.ViewModel
import com.jawon.app.data.prefs.AppPreferences
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.StateFlow
import javax.inject.Inject

@HiltViewModel
class SettingsViewModel @Inject constructor(
    private val preferences: AppPreferences
) : ViewModel() {

    val fetchCoverArt: StateFlow<Boolean> = preferences.fetchCoverArt

    fun onFetchCoverArtChanged(enabled: Boolean) = preferences.setFetchCoverArt(enabled)
}
