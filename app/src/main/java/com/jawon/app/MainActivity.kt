package com.jawon.app

import android.graphics.Color
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.SystemBarStyle
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.Surface
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.jawon.app.data.prefs.AppPreferences
import com.jawon.app.navigation.JawonNavHost
import com.jawon.app.ui.intro.IntroOverlay
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

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

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
        setContent {
            val roomScheme by preferences.roomScheme.collectAsStateWithLifecycle()
            // Which room the app opens in. Read here, above the NavHost, because it decides the
            // start destination rather than anything inside one — see JawonNavHost's `format`.
            val format by preferences.format.collectAsStateWithLifecycle()
            JawonTheme(roomScheme = roomScheme) {
                Surface(modifier = Modifier.fillMaxSize()) {
                    JawonNavHost(
                        format = Format.openable(format, BuildConfig.DEBUG || BuildConfig.SHOW_FORMATS)
                    )

                    // Over the NavHost rather than before it, so the room is being built
                    // underneath while the card is up — the scene's cubemap, models and first
                    // artwork all take time, and this is what that time looks like.
                    //
                    // `rememberSaveable` so a rotation or a theme change does not replay it:
                    // the intro belongs to launching the app, not to composing this screen.
                    var introDone by rememberSaveable { mutableStateOf(false) }
                    if (!introDone) {
                        IntroOverlay(onFinished = { introDone = true })
                    }
                }
            }
        }
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
