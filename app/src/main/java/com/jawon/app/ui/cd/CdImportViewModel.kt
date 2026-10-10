package com.jawon.app.ui.cd

import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.hardware.usb.UsbDevice
import android.hardware.usb.UsbManager
import android.net.Uri
import androidx.core.content.ContextCompat
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.jawon.app.data.cd.CdDrive
import com.jawon.app.data.cd.CdRip
import com.jawon.app.data.cd.CdRipper
import com.jawon.app.data.cd.CdToc
import dagger.hilt.android.lifecycle.HiltViewModel
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import javax.inject.Inject

/**
 * Where the CD screen is, start to finish.
 *
 * Modelled as one sealed type rather than a handful of booleans because the states are genuinely
 * exclusive and the awkward ones are the ones a flag set would blur: a drive that is attached but
 * not yet permitted, and a drive that is permitted but has no disc in it, look identical to
 * "nothing plugged in" unless the screen can tell them apart — and they need three different
 * sentences.
 */
/** Where on the journey a state is — see [CdSteps]. */
internal fun CdState.stage(): CdSteps.Stage = when (this) {
    is CdState.NoDrive -> CdSteps.Stage.NO_DRIVE
    is CdState.NeedsPermission -> CdSteps.Stage.NEEDS_PERMISSION
    is CdState.Reading -> CdSteps.Stage.READING
    is CdState.NoDisc -> CdSteps.Stage.NO_DISC
    is CdState.Ready -> CdSteps.Stage.READY
    is CdState.Ripping -> CdSteps.Stage.RIPPING
    is CdState.Done -> CdSteps.Stage.DONE
    is CdState.Failed -> CdSteps.Stage.FAILED
}

sealed interface CdState {
    /** Nothing plugged in, or nothing that answers as a drive. */
    data object NoDrive : CdState

    /** A drive is there; Android has not been asked yet whether we may talk to it. */
    data class NeedsPermission(val device: UsbDevice) : CdState

    data object Reading : CdState

    /** A drive we can talk to, with nothing readable in it. */
    data object NoDisc : CdState

    /** A disc, its plan, and what MusicBrainz called it if it knew. */
    data class Ready(
        val toc: CdToc,
        val plan: List<CdRip.RipTrack>,
        val release: CdRip.DiscRelease?,
        val identifying: Boolean
    ) : CdState

    data class Ripping(val progress: CdRipper.Progress) : CdState

    /** The folder of WAVs, waiting to be handed to the importer. */
    data class Done(val folder: Uri, val tracks: Int) : CdState

    data class Failed(val reason: String) : CdState
}

/**
 * Driving a USB CD drive from the phone.
 *
 * **Nothing here has ever met hardware.** `ENGINEERING.md` says so about `data/cd` and it is still
 * true; the owner has the discs and the DAC but no reader yet. So this is written to fail
 * legibly rather than to assume success — every step that can go wrong says which step it was,
 * because the first run will be somebody holding a drive that does not enumerate and needing to
 * know whether the problem is the cable, the power, the disc or the code.
 *
 * The most likely first failure is not a bug: a bus-powered drive draws more than a phone will
 * give it, and the symptom is a device that appears in the USB list and then stops answering.
 * That is why [CdRipper]'s read failure names power as a cause.
 */
@HiltViewModel
class CdImportViewModel @Inject constructor(
    @param:ApplicationContext private val context: Context,
    private val ripper: CdRipper
) : ViewModel() {

    private val usb = context.getSystemService(Context.USB_SERVICE) as UsbManager

    private val _state = MutableStateFlow<CdState>(CdState.NoDrive)
    val state: StateFlow<CdState> = _state.asStateFlow()

    private var drive: CdDrive? = null
    private var ripJob: Job? = null

    /**
     * Android's answer to the permission request.
     *
     * Registered `NOT_EXPORTED`: the system sends this broadcast to our package specifically, and
     * from Android 13 a receiver must say which it expects or the registration is rejected
     * outright at runtime.
     */
    private val permissionReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            if (intent?.action != ACTION_PERMISSION) return
            val granted = intent.getBooleanExtra(UsbManager.EXTRA_PERMISSION_GRANTED, false)
            if (granted) look() else _state.value =
                CdState.Failed("Jawon needs permission to use the drive.")
        }
    }

    /**
     * **The drive, the moment it is plugged in — and the moment it is pulled out** (0.7.6).
     *
     * The screen used to learn about a drive only when LOOK AGAIN was pressed, which made the
     * first thing anyone did with it — plug the drive in while looking at "NO DRIVE" — appear to
     * do nothing. Android announces both; listening is all it takes. A drive pulled out mid-copy
     * says so at once, instead of the copy failing a read later with something less plain.
     */
    private val plugReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            when (intent?.action) {
                UsbManager.ACTION_USB_DEVICE_ATTACHED -> {
                    // Only when it is a drive being waited for: one plugged in mid-copy is not
                    // a reason to start again.
                    val waiting = _state.value
                    if (waiting is CdState.NoDrive || waiting is CdState.Failed) look()
                }
                UsbManager.ACTION_USB_DEVICE_DETACHED -> {
                    if (CdDrive.findDrive(usb) != null) return
                    val was = _state.value
                    ripJob?.cancel()
                    ripJob = null
                    runCatching { drive?.close() }
                    drive = null
                    _state.value = if (was is CdState.Ripping) {
                        CdState.Failed(
                            "The drive was unplugged. What was already copied is still in the " +
                                "folder you chose."
                        )
                    } else {
                        CdState.NoDrive
                    }
                }
            }
        }
    }

    init {
        ContextCompat.registerReceiver(
            context,
            permissionReceiver,
            IntentFilter(ACTION_PERMISSION),
            ContextCompat.RECEIVER_NOT_EXPORTED
        )
        ContextCompat.registerReceiver(
            context,
            plugReceiver,
            IntentFilter().apply {
                addAction(UsbManager.ACTION_USB_DEVICE_ATTACHED)
                addAction(UsbManager.ACTION_USB_DEVICE_DETACHED)
            },
            // The system's own announcements; nothing else may send them.
            ContextCompat.RECEIVER_NOT_EXPORTED
        )
        look()
    }

    /** Re-scan the USB bus. Called on arrival, after a permission grant, and from RETRY. */
    fun look() {
        val device = CdDrive.findDrive(usb)
        if (device == null) {
            _state.value = CdState.NoDrive
            return
        }
        if (!usb.hasPermission(device)) {
            _state.value = CdState.NeedsPermission(device)
            return
        }
        openAndRead(device)
    }

    /** Asks Android for the drive. The dialog is the system's; we only hear the answer. */
    fun requestPermission(device: UsbDevice) {
        usb.requestPermission(
            device,
            PendingIntent.getBroadcast(
                context,
                0,
                Intent(ACTION_PERMISSION).setPackage(context.packageName),
                // Mutable because the system writes the device and the grant into it. Immutable
                // is the safer default everywhere else and is simply wrong here.
                PendingIntent.FLAG_MUTABLE
            )
        )
    }

    private fun openAndRead(device: UsbDevice) {
        _state.value = CdState.Reading
        viewModelScope.launch {
            val opened = withContext(Dispatchers.IO) { CdDrive.open(usb, device) }
            if (opened == null) {
                _state.value = CdState.Failed("The drive is there but would not open.")
                return@launch
            }
            drive = opened

            val toc = withContext(Dispatchers.IO) {
                // A drive that has just been given a disc reports not-ready for a few seconds
                // while it spins up, and answering "no disc" during that window is the single
                // most annoying thing this screen could do.
                repeat(READY_ATTEMPTS) {
                    if (opened.isReady()) return@withContext opened.readToc()
                    kotlinx.coroutines.delay(READY_WAIT_MS)
                }
                null
            }

            if (toc == null || toc.audioTracks.isEmpty()) {
                _state.value = CdState.NoDisc
                return@launch
            }

            // Shown immediately with numbered tracks, then filled in if the lookup answers. The
            // rip is fully usable in the meantime — waiting on the network before showing a disc
            // that is already in the drive would be borrowing the network's failure modes.
            _state.value = CdState.Ready(toc, CdRip.plan(toc), release = null, identifying = true)

            val release = ripper.identify(toc.discId())
            val current = _state.value
            if (current is CdState.Ready) {
                _state.value = CdState.Ready(
                    toc = toc,
                    plan = CdRip.plan(toc, release?.trackTitles.orEmpty()),
                    release = release,
                    identifying = false
                )
            }
        }
    }

    /** Rips the disc into a folder the user has just chosen. */
    fun rip(destination: Uri) {
        val ready = _state.value as? CdState.Ready ?: return
        val open = drive ?: return
        ripJob?.cancel()
        ripJob = viewModelScope.launch {
            _state.value = CdState.Ripping(
                CdRipper.Progress(0f, ready.plan.first().number, ready.plan.first().title, 0, ready.plan.size)
            )
            when (val result = ripper.rip(open, ready.plan, destination) { progress ->
                _state.value = CdState.Ripping(progress)
            }) {
                is CdRipper.Result.Done -> _state.value = CdState.Done(result.folder, result.tracks)
                is CdRipper.Result.Failed -> _state.value = CdState.Failed(result.reason)
            }
        }
    }

    fun cancelRip() {
        ripJob?.cancel()
        ripJob = null
        look()
    }

    override fun onCleared() {
        ripJob?.cancel()
        runCatching { context.unregisterReceiver(permissionReceiver) }
        runCatching { context.unregisterReceiver(plugReceiver) }
        // Releasing the interface matters more than usual: a claimed USB device stays claimed
        // until the process lets go, so a drive left open here cannot be used by anything else —
        // including this app on its next visit to the screen.
        runCatching { drive?.close() }
        drive = null
        super.onCleared()
    }

    private companion object {
        const val ACTION_PERMISSION = "com.jawon.app.USB_PERMISSION"

        /** How long to wait for a disc to spin up, in total: eight attempts half a second apart. */
        const val READY_ATTEMPTS = 8
        const val READY_WAIT_MS = 500L
    }
}
