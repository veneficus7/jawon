package com.jawon.app.ui.settings

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import android.content.Context
import android.net.Uri
import com.jawon.app.data.backup.LibraryBackup
import com.jawon.app.Format
import com.jawon.app.data.diagnostics.CrashLog
import com.jawon.app.data.local.SpotifyCleanup
import com.jawon.app.data.prefs.AppPreferences
import com.jawon.app.data.remote.RemoteLibrarySync
import com.jawon.app.data.remote.ServerInvite
import com.jawon.app.data.remote.ServerProbe
import com.jawon.app.data.remote.SubsonicClient
import com.jawon.app.data.remote.SubsonicServer
import com.jawon.app.data.remote.SyncState
import com.jawon.app.playback.StreamCache
import com.jawon.app.data.update.AvailableUpdate
import com.jawon.app.data.update.UpdateChecker
import com.jawon.app.data.update.UpdateState
import com.jawon.app.playback.Crossfade
import com.jawon.app.ui.theme.RoomScheme
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import javax.inject.Inject

/**
 * `@UnstableApi` because it holds a [StreamCache], which is built on Media3's cache and data
 * source APIs. Declared here for the same reason `PlaybackService` declares it: the annotation is
 * lint-enforced rather than compiler-enforced, so an un-annotated holder builds green and fails
 * the release lint pass instead.
 */
@androidx.media3.common.util.UnstableApi
@HiltViewModel
class SettingsViewModel @Inject constructor(
    private val preferences: AppPreferences,
    private val updateChecker: UpdateChecker,
    private val backup: LibraryBackup,
    private val diagnostics: com.jawon.app.ui.wallscene.RoomDiagnostics,
    private val crashLog: CrashLog,
    /** Cleared when the lyrics source preference changes. */
    private val lyricsRepository: com.jawon.app.data.lyrics.LyricsRepository,
    /** Tidies what older builds left behind. See [LibraryRepair]. */
    private val libraryRepair: com.jawon.app.data.local.LibraryRepair,
    private val subsonic: SubsonicClient,
    private val librarySync: RemoteLibrarySync,
    private val streamCache: StreamCache,
    private val spotifyCleanup: SpotifyCleanup
) : ViewModel() {

    // ---- the music server ------------------------------------------------------------------

    val musicServer: StateFlow<SubsonicServer> = preferences.musicServer

    val syncState: StateFlow<SyncState> = librarySync.state

    private val _probe = MutableStateFlow<ServerProbe?>(null)

    /** What the last connection test found, or null if none has been run this session. */
    val probe: StateFlow<ServerProbe?> = _probe.asStateFlow()

    private val _probing = MutableStateFlow(false)
    val probing: StateFlow<Boolean> = _probing.asStateFlow()

    /**
     * How many albums the server currently has on the wall.
     *
     * Drawn in the server section's closed heading, so the answer to "did that work" is visible
     * without opening anything or running another test.
     */
    val serverAlbumCount: StateFlow<Int> = librarySync.albumCount
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), 0)

    /**
     * Saves the server without testing it.
     *
     * Deliberately separate from [onTestServer]: typing a password should not fire a request on
     * every keystroke, and a user who knows their details should not have to pass a test before
     * the app will remember them.
     */
    fun onServerChanged(server: SubsonicServer) {
        preferences.setMusicServer(server)
        // The old result described the old server, and leaving it on screen would say a
        // freshly-typed address was verified when nothing has asked it anything.
        _probe.value = null
    }

    private val _inviteMessage = MutableStateFlow<String?>(null)

    /** What happened to the last pasted link, so a bad paste says so rather than doing nothing. */
    val inviteMessage: StateFlow<String?> = _inviteMessage.asStateFlow()

    /** `true` when this build already came pointed at a server. */
    val hasBundledServer: Boolean get() = preferences.hasBundledServer

    /**
     * Fills in a whole server from one pasted link, and tests it straight away.
     *
     * Testing without being asked is the right call *here* specifically: someone pasting a link
     * has not chosen an address, a username or a password, so there is nothing for them to
     * check over. Either it works or the link is bad, and they want to know which.
     */
    fun onInvitePasted(text: String) {
        val server = ServerInvite.decode(text)
        if (server == null) {
            _inviteMessage.value = "That does not look like a Jawon link."
            return
        }
        _inviteMessage.value = null
        preferences.setMusicServer(server)
        _probe.value = null
        onTestServer()
    }

    /** The current server as a link, for handing to someone else. */
    fun inviteForCurrentServer(): String? =
        preferences.musicServer.value.takeIf { it.isConfigured }?.let { ServerInvite.encode(it) }

    fun onTestServer() = viewModelScope.launch {
        _probing.value = true
        _probe.value = subsonic.ping(preferences.musicServer.value)
        _probing.value = false
    }

    /**
     * Pulls the catalogue in.
     *
     * No music is downloaded here — this is titles, artists, tracklists and one cover per album.
     * See [RemoteLibrarySync].
     */
    fun onSyncLibrary() = viewModelScope.launch { librarySync.sync() }

    /**
     * Forgets the server, leaving its albums where they are.
     *
     * The cases stay on the wall and simply stop being playable, which is the honest state:
     * silently deleting a few hundred records because a password was cleared would be a
     * catastrophic reading of a small action. Removing them is a separate, deliberate act.
     */
    fun onForgetServer() {
        preferences.setMusicServer(SubsonicServer.NONE)
        _probe.value = null
    }

    // ---- tidying up ------------------------------------------------------------------------

    private val _repairBusy = MutableStateFlow(false)
    val repairBusy: StateFlow<Boolean> = _repairBusy.asStateFlow()

    private val _repairMessage = MutableStateFlow<String?>(null)
    val repairMessage: StateFlow<String?> = _repairMessage.asStateFlow()

    /**
     * Removes duplicate tracks from every album.
     *
     * Reports what it did even when that is nothing, because "0 removed" is the answer somebody
     * pressing this wants — a button that goes quiet is indistinguishable from one that failed.
     */
    fun onRemoveDuplicateTracks() = viewModelScope.launch {
        _repairBusy.value = true
        _repairMessage.value = null
        val result = runCatching { libraryRepair.removeDuplicateTracks() }
        _repairMessage.value = result.fold(
            onSuccess = { done ->
                when {
                    done.duplicatesRemoved == 0 -> "No duplicates found."
                    done.albumsTouched == 1 ->
                        "Removed ${done.duplicatesRemoved} duplicate tracks from one album."
                    else -> "Removed ${done.duplicatesRemoved} duplicate tracks from " +
                        "${done.albumsTouched} albums."
                }
            },
            onFailure = { "Could not tidy the library · ${it.message ?: "unknown"}" }
        )
        _repairBusy.value = false
    }

    val streamCacheMb: StateFlow<Int> = preferences.streamCacheMb

    fun onStreamCacheMbChanged(mb: Int) = preferences.setStreamCacheMb(mb)

    /** Megabytes currently held, for the readout. */
    fun cacheUsedMb(): Int = (streamCache.usedBytes() / (1024L * 1024L)).toInt()

    fun onClearStreamCache() = viewModelScope.launch { streamCache.clear() }

    val fetchCoverArt: StateFlow<Boolean> = preferences.fetchCoverArt

    /** See [com.jawon.app.data.lyrics.LrclibClient] for why LRCLIB and not a commercial one. */
    val fetchLyrics: StateFlow<Boolean> = preferences.fetchLyrics
    val shakeToSwitch: StateFlow<Boolean> = preferences.shakeToSwitch
    fun onShakeToSwitchChanged(enabled: Boolean) = preferences.setShakeToSwitch(enabled)

    val roomScheme: StateFlow<RoomScheme> = preferences.roomScheme

    fun onFetchCoverArtChanged(enabled: Boolean) = preferences.setFetchCoverArt(enabled)

    fun onFetchLyricsChanged(enabled: Boolean) {
        preferences.setFetchLyrics(enabled)
        // Turning the lookup on has to un-remember the tracks that were never looked up.
        viewModelScope.launch { lyricsRepository.forgetLookups() }
    }

    val preferOnlineLyrics: StateFlow<Boolean> = preferences.preferOnlineLyrics

    fun onPreferOnlineLyricsChanged(enabled: Boolean) {
        preferences.setPreferOnlineLyrics(enabled)
        // The session cache holds whichever source won last time, and the database holds every
        // "we asked and there were none" — so without both, the switch appears to do nothing
        // for precisely the tracks somebody turned it on for.
        viewModelScope.launch { lyricsRepository.forgetLookups() }
    }

    fun onRoomSchemeChanged(scheme: RoomScheme) = preferences.setRoomScheme(scheme)

    /** Which room the app opens in. See [com.jawon.app.Format]. */
    val format: StateFlow<Format> = preferences.format

    fun onFormatChanged(format: Format) = preferences.setFormat(format)

    val caseSpin: StateFlow<Float> = preferences.caseSpin

    /** Snapped to twentieths, so the readout lands on round numbers and zero is reachable. */
    fun onCaseSpinChanged(fraction: Float) =
        preferences.setCaseSpin((Math.round(fraction * 20f) / 20f))

    // The equaliser used to be exposed here too, for a panel in Settings. It has moved to the
    // deck's SOUND page, where it sits with the rest of the rack and belongs to the record being
    // played rather than to the app's preferences. The equaliser controller itself is unchanged and
    // still shared — the deck's ViewModel drives it now.

    val crossfadeMs: StateFlow<Int> = preferences.crossfadeMs

    /** Snapped to whole tenths of a second, since the readout is shown to one decimal. */
    fun onCrossfadeChanged(ms: Int) =
        preferences.setCrossfadeMs(if (ms < Crossfade.MIN_MS) 0 else (ms / 100) * 100)

    // ---- Updates -------------------------------------------------------------------

    val updateState: StateFlow<UpdateState> = updateChecker.state

    /** Re-read on every recomposition of the screen: the user may grant it and come back. */
    fun canRequestInstall(): Boolean = updateChecker.canRequestInstall()

    fun onCheckForUpdates() = viewModelScope.launch { updateChecker.check() }

    fun onDownloadUpdate(update: AvailableUpdate) =
        viewModelScope.launch { updateChecker.download(update) }

    // ---- Volume levelling ----------------------------------------------------------

    val replayGainMode: StateFlow<String> = preferences.replayGainMode

    fun onReplayGainModeChanged(mode: com.jawon.app.playback.ReplayGain.Mode) =
        preferences.setReplayGainMode(mode.name)

    // ---- Backup --------------------------------------------------------------------

    /**
     * What the last backup or restore did, for the line under the buttons.
     *
     * Stays until the next one replaces it, and is not persisted — it lives as long as this
     * screen's ViewModel and no longer. There was a `dismiss` for it, which nothing ever
     * called: a result line that clears itself while someone is still reading it is worse
     * than one that sits there until they do something else.
     */
    private val _backupMessage = MutableStateFlow<String?>(null)
    val backupMessage: StateFlow<String?> = _backupMessage.asStateFlow()

    private val _backupBusy = MutableStateFlow(false)
    val backupBusy: StateFlow<Boolean> = _backupBusy.asStateFlow()

    /** The name offered to the file picker. Dated, because people keep more than one. */
    fun suggestedBackupName(): String {
        val stamp = java.text.SimpleDateFormat("yyyy-MM-dd", java.util.Locale.US)
            .format(java.util.Date())
        return "jawon-library-$stamp.zip"
    }

    fun onWriteBackup(context: Context, destination: Uri) = viewModelScope.launch {
        _backupBusy.value = true
        _backupMessage.value = runCatching { backup.writeTo(context, destination) }.fold(
            onSuccess = { w ->
                "Saved ${w.albums} ${plural(w.albums, "album")} on " +
                    "${w.shelves} ${plural(w.shelves, "shelf", "shelves")}, " +
                    "${w.tracks} ${plural(w.tracks, "track")} and " +
                    "${w.covers} ${plural(w.covers, "image")}."
            },
            // The message, not the class name. A backup failing is one of the few places a
            // user genuinely needs to know what went wrong.
            onFailure = { "Could not write the backup: ${it.message ?: "unknown error"}" }
        )
        _backupBusy.value = false
    }

    fun onReadBackup(context: Context, source: Uri) = viewModelScope.launch {
        _backupBusy.value = true
        _backupMessage.value = runCatching {
            // A backup written while the Spotify route existed can carry its albums, which this
            // build cannot play. Taken straight back out rather than left for the next launch to
            // find, so the wall never shows records that do nothing when opened.
            backup.readFrom(context, source) to spotifyCleanup.run()
        }.fold(
            onSuccess = { (r, spotify) ->
                "Restored ${r.albums} ${plural(r.albums, "album")} on " +
                    "${r.shelves} ${plural(r.shelves, "shelf", "shelves")}, " +
                    "${r.tracks} ${plural(r.tracks, "track")}, " +
                    "${r.covers} ${plural(r.covers, "image")} and " +
                    "${r.plays} ${plural(r.plays, "play")} of history." +
                    if (spotify > 0) {
                        " $spotify of those albums came from Spotify and were taken back " +
                            "out — this version cannot play them."
                    } else {
                        ""
                    }
            },
            onFailure = { "Could not restore: ${it.message ?: "unknown error"}" }
        )
        _backupBusy.value = false
    }

    // ---- Diagnostics ---------------------------------------------------------------

    /**
     * Read on demand rather than observed.
     *
     * The frame loop writes plain fields at up to 120Hz; turning that into a flow would
     * recompose this screen every frame for a readout nobody is watching change.
     */
    fun diagnosticsSnapshot() = diagnostics.snapshot()

    fun onResetDiagnostics() = diagnostics.reset()

    /** What the phone says it is, so a screenshot identifies the hardware it came from. */
    fun deviceLine(): String =
        "${android.os.Build.MANUFACTURER} ${android.os.Build.MODEL} · " +
            "ANDROID ${android.os.Build.VERSION.RELEASE} (API ${android.os.Build.VERSION.SDK_INT})"

    // ---- the crash log -------------------------------------------------------------------
    //
    // Read on demand rather than held in a StateFlow. Crashes are rare, the file is small, and
    // a flow would mean watching a file that changes at most once per process death — for a
    // section most people will never see say anything but "nothing to report".

    /**
     * The whole log, for the preview.
     *
     * Shown rather than merely offered for sending, deliberately: someone about to hand over a
     * file is entitled to see what is in it first, and here that is the honest demonstration
     * that it contains a stack trace and a device model and nothing about them.
     */
    fun crashReport(): String? = crashLog.report()

    /** For the share sheet. Null when there is nothing to share. */
    fun crashReportUri(): Uri? = crashLog.shareUri()

    fun onClearCrashReport() = crashLog.clear()

    /** "1 shelf", "2 shelves". A backup of one shelf reporting "1 shelves" reads as a stub. */
    private fun plural(count: Int, one: String, many: String = one + "s") =
        if (count == 1) one else many
}
