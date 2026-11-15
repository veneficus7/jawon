package com.jawon.app

import android.app.SearchManager
import android.content.Intent
import android.graphics.Color
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.provider.MediaStore
import android.widget.Toast
import androidx.annotation.RequiresApi
import androidx.activity.ComponentActivity
import androidx.activity.SystemBarStyle
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.Surface
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.lifecycleScope
import com.jawon.app.data.importer.OpenedMusic
import com.jawon.app.data.playback.LibrarySearch
import com.jawon.app.data.playback.PlaybackRepository
import kotlinx.coroutines.launch
import com.jawon.app.data.prefs.AppPreferences
import com.jawon.app.navigation.JawonNavHost
import com.jawon.app.ui.common.LocalRoomParked
import com.jawon.app.ui.common.RoomCalm
import com.jawon.app.ui.intro.IntroKind
import com.jawon.app.ui.intro.IntroOverlay
import com.jawon.app.ui.intro.SplashHandOver
import com.jawon.app.ui.theme.JawonTheme
import dagger.hilt.android.AndroidEntryPoint
import javax.inject.Inject

@AndroidEntryPoint
class MainActivity : ComponentActivity() {

    /**
     * Injected here rather than reached through a ViewModel because the theme wraps the whole
     * NavHost — there is no screen above it to own the state.
     */
    @Inject
    lateinit var preferences: AppPreferences

    /**
     * The player, for the ways in that only start music — a shortcut's Resume, a spoken request.
     * They are done here rather than in a room because no room may be on screen yet, and the
     * rooms follow the player whoever set it going.
     */
    @Inject
    lateinit var playback: PlaybackRepository

    /** Music another app hands over, held until Add music takes it up. */
    @Inject
    lateinit var opened: OpenedMusic

    /** What this launch was for, when it needs a screen. Taken by the stage, once. */
    private var request by mutableStateOf<LaunchRequest?>(null)

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        // Only a launch that is new: a rebuilt activity must not play its shortcut again.
        if (savedInstanceState == null) act(intent)
        // A measuring build's switches (RoomSwitches); inert in every other build.
        if (BuildConfig.BENCH) com.jawon.app.ui.wallscene.RoomSwitches.read(intent)

        // **Ask the display for 60Hz, not 120.**
        //
        // This is the single largest thing the app can do about heat, and it is a whole-window
        // decision rather than a scene one, which is why it lives here.
        //
        // Jawon is not a feed that draws when you scroll and then stops: it renders a lit room
        // every frame for as long as it is open, so it pays the refresh rate continuously in a
        // way an ordinary app never does. On this phone that meant 120 frames a second of scene
        // traversal and draw-call submission — measured at a flat ~0.39 of a core on Filament's
        // own thread whatever else was happening. Halving the frames halves that submission,
        // and the GPU's fragment work with it.
        //
        // The room is the wrong thing to spend a high refresh rate on in any case. It drifts,
        // bobs and glows; nothing in it moves fast enough for 120 to read differently from 60,
        // and the two gestures that *are* fast — the scratch and the pan — are tracking a finger
        // that samples at the touch rate rather than the display rate. Android 15 makes the same
        // judgement for games by default, for the same reasons.
        //
        // A request, not a command: the platform is free to ignore it, and does when something
        // else on screen needs more. Harmless if so.
        window.attributes = window.attributes.apply { preferredRefreshRate = TARGET_REFRESH_HZ }

        // **Dark bars, whatever the phone is set to.** The defaults follow the system theme, and
        // Jawon is dark on every screen whatever the system says: on a phone in light mode the
        // status icons came out dark on the dark room, nearly invisible, and three-button
        // navigation got a 90%-white scrim across the foot of it. Light icons on nothing, always.
        enableEdgeToEdge(
            statusBarStyle = SystemBarStyle.dark(Color.TRANSPARENT),
            navigationBarStyle = SystemBarStyle.dark(Color.TRANSPARENT)
        )

        // **One opening, once.** The title sequence belongs to launching Jawon, not to building
        // this activity: it plays the first time the process puts a window up, and coming back to
        // an app that is still in memory — Back out of the showroom and in again, a theme change —
        // goes straight to the room. A phone that had put Jawon to sleep and is restoring it gets
        // the short one, as does anyone who asked for it in Settings.
        val intro = when {
            openingPlayed -> null
            savedInstanceState != null || preferences.shortIntro.value -> IntroKind.QUICK
            else -> IntroKind.FULL
        }
        openingPlayed = true
        val systemSplash = Build.VERSION.SDK_INT >= Build.VERSION_CODES.S
        val handOver = SplashHandOver(waiting = intro != null && systemSplash, markShown = true)
        if (intro != null && systemSplash) takeOverSplash(handOver)

        setContent {
            val roomScheme by preferences.roomScheme.collectAsStateWithLifecycle()
            // Which room the app opens in. Read here, above the NavHost, because it decides the
            // start destination rather than anything inside one — see JawonNavHost's `format`.
            val format by preferences.format.collectAsStateWithLifecycle()
            JawonTheme(roomScheme = roomScheme) {
                Surface(modifier = Modifier.fillMaxSize()) {
                    var introUp by remember { mutableStateOf(intro != null) }
                    var parked by remember { mutableStateOf(false) }

                    // Under the card the room is out of TalkBack's reach: it would otherwise read
                    // out a wall nobody can see yet.
                    Box(
                        Modifier
                            .fillMaxSize()
                            .then(if (introUp) Modifier.clearAndSetSemantics { } else Modifier)
                    ) {
                        // Parked under the opening card, or because the app is on its way out
                        // of sight: either way a room has no reason to draw. See RoomCalm.
                        CompositionLocalProvider(LocalRoomParked provides (parked || RoomCalm.away.value)) {
                            JawonNavHost(
                                format = Format.openable(format, BuildConfig.DEBUG || BuildConfig.SHOW_FORMATS),
                                request = request,
                                onRequestTaken = { request = null }
                            )
                        }
                    }

                    // Over the stage rather than before it, so the room is being built underneath
                    // while the card is up — the scene's cubemap, models and first artwork all
                    // take time, and this is what that time looks like.
                    if (introUp && intro != null) {
                        IntroOverlay(
                            kind = intro,
                            handOver = handOver,
                            onPark = { parked = it },
                            onFinished = {
                                parked = false
                                introUp = false
                            }
                        )
                    }
                }
            }
        }
    }

    /** An intent arriving at a Jawon that is already open: `singleTask` brings it here. */
    // The rooms stand aside while Android is sliding the app in or out — see RoomCalm.

    override fun onWindowFocusChanged(hasFocus: Boolean) {
        super.onWindowFocusChanged(hasFocus)
        RoomCalm.onFocus(hasFocus, sideBySide = isInMultiWindowMode, nowMs = android.os.SystemClock.uptimeMillis())
    }

    override fun onResume() {
        super.onResume()
        RoomCalm.onResumed(android.os.SystemClock.uptimeMillis())
    }

    override fun onPause() {
        RoomCalm.onPaused()
        super.onPause()
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        act(intent)
        if (BuildConfig.BENCH) com.jawon.app.ui.wallscene.RoomSwitches.read(intent)
    }

    /**
     * Does what a launch was for — see [Launch]. A plain launch does nothing here.
     *
     * Coming back from recents re-delivers the intent that first started the task; that is a
     * return, not a second request, so it is left alone.
     */
    private fun act(intent: Intent?) {
        intent ?: return
        if (intent.flags and Intent.FLAG_ACTIVITY_LAUNCHED_FROM_HISTORY != 0) return
        when (intent.action) {
            Launch.ACTION_RESUME -> play("Nothing to resume yet — open a record first.") { playback.resume() }
            Launch.ACTION_PLAY_SOMETHING -> play("Your shelves are empty — add some music first.") {
                playback.playSomething()
            }
            Launch.ACTION_ADD_MUSIC -> request = LaunchRequest.AddMusic
            // "Open with Jawon", or shared to it: a song, or an album as a ZIP.
            Intent.ACTION_VIEW -> open(listOfNotNull(intent.data))
            Intent.ACTION_SEND -> open(listOfNotNull(streamOf(intent)))
            Intent.ACTION_SEND_MULTIPLE -> open(streamsOf(intent))
            // "Play ⟨something⟩ on Jawon", asked of the phone itself.
            MediaStore.INTENT_ACTION_MEDIA_PLAY_FROM_SEARCH -> {
                val words = intent.getStringExtra(SearchManager.QUERY)
                val hints = LibrarySearch.Hints(
                    album = intent.getStringExtra(MediaStore.EXTRA_MEDIA_ALBUM),
                    artist = intent.getStringExtra(MediaStore.EXTRA_MEDIA_ARTIST),
                    title = intent.getStringExtra(MediaStore.EXTRA_MEDIA_TITLE)
                )
                val asked = words?.takeIf { it.isNotBlank() } ?: hints.album ?: hints.title ?: hints.artist
                play(
                    if (asked == null) "Your shelves are empty — add some music first."
                    else "Nothing on your shelves matches “$asked”."
                ) { playback.playFromSearch(words, hints) }
            }
        }
    }

    private fun open(uris: List<Uri>) {
        if (uris.isEmpty()) return
        opened.offer(uris)
        request = LaunchRequest.Open(uris)
    }

    @Suppress("DEPRECATION")
    private fun streamOf(intent: Intent): Uri? =
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            intent.getParcelableExtra(Intent.EXTRA_STREAM, Uri::class.java)
        } else {
            intent.getParcelableExtra(Intent.EXTRA_STREAM)
        }

    @Suppress("DEPRECATION")
    private fun streamsOf(intent: Intent): List<Uri> =
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            intent.getParcelableArrayListExtra(Intent.EXTRA_STREAM, Uri::class.java)
        } else {
            intent.getParcelableArrayListExtra(Intent.EXTRA_STREAM)
        }.orEmpty()

    /** Starts something, and says so in a line if there was nothing to start. */
    private fun play(otherwise: String, start: suspend () -> Boolean) {
        lifecycleScope.launch {
            val started = runCatching { start() }.getOrDefault(false)
            if (!started) Toast.makeText(this@MainActivity, otherwise, Toast.LENGTH_LONG).show()
        }
    }

    /**
     * Takes the system's splash in hand, so that the opening carries on from it instead of
     * following it.
     *
     * Left to itself the platform cross-fades its splash into the app's first frame, which is what
     * made the mark and the title card read as two intros. With an exit listener the splash is
     * the app's to remove: it stays up until the mark has finished writing itself and the room
     * has finished building, the card under it draws the same mark in the same place
     * ([SplashHandOver.rect], read off the splash's own icon), and then the splash is simply taken
     * away — with nothing on the screen changing as it goes.
     */
    @RequiresApi(Build.VERSION_CODES.S)
    private fun takeOverSplash(handOver: SplashHandOver) {
        splashScreen.setOnExitAnimationListener { splash ->
            val icon = splash.iconView
            if (icon != null && icon.width > 0 && icon.height > 0) {
                val at = IntArray(2).also(icon::getLocationInWindow)
                handOver.rect = Rect(
                    at[0].toFloat(),
                    at[1].toFloat(),
                    (at[0] + icon.width).toFloat(),
                    (at[1] + icon.height).toFloat()
                )
            } else {
                // A launch the system drew no mark for — from a notification, say: the card
                // writes it instead.
                handOver.markShown = false
            }
            val start = splash.iconAnimationStart
            val length = splash.iconAnimationDuration
            val left = if (start != null && length != null) {
                java.time.Duration.between(java.time.Instant.now(), start.plus(length)).toMillis()
            } else {
                0L
            }
            // Never sooner than a few frames: the card has to have drawn the mark under the
            // splash before the splash goes.
            val now = android.os.SystemClock.uptimeMillis()
            handOver.writtenAt = now + left.coerceIn(SPLASH_MIN_HOLD_MS, SPLASH_MAX_HOLD_MS)
            if (start != null) {
                handOver.startedAt = now - java.time.Duration.between(start, java.time.Instant.now()).toMillis()
            }
            if (handOver.waiting) {
                // The card takes it off when the room under it has finished building.
                handOver.uncover = { splash.alpha = 0f }
                handOver.release = { splash.remove() }
                handOver.claimed = true
            } else {
                // The card gave up waiting and has started without it.
                splash.remove()
            }
        }
    }

    private companion object {
        /** Whether this process has played its opening — see `onCreate`. */
        var openingPlayed = false

        const val SPLASH_MIN_HOLD_MS = 80L
        const val SPLASH_MAX_HOLD_MS = 1200L
    }
}

/**
 * The refresh rate the room asks for.
 *
 * **The panel's full rate, on the owner's call, and the thermal argument in `onCreate` loses.**
 *
 * That argument is not wrong about heat — it is wrong about what it costs. The claim underneath
 * it was that nothing in this room moves fast enough for 120 to read differently from 60, and
 * that turns out to be false in practice: dropped to 60 the room was reported as visibly worse,
 * immediately, without being told what had changed.
 *
 * Part of that was a bug now fixed — [com.jawon.app.ui.wallscene.RoomCadence] expressed its
 * intervals as vsync counts chosen for a 120Hz panel, so halving the refresh rate silently
 * halved every animation rate underneath it as well, and the drifting wall fell to 7.5fps. But
 * only part. The rest is that this app's entire proposition is how the room feels, and a room
 * that renders continuously is exactly the case where the difference between 60 and 120 is
 * visible rather than theoretical.
 *
 * The heat is real and is accepted. If it has to be paid down later, the honest lever is the
 * cadence — drawing the *room* less often while the panel stays at 120 — not the panel, because
 * the panel also carries the deck, the tracklist and every gesture.
 */
private const val TARGET_REFRESH_HZ = 120f
