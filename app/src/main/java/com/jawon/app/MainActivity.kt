package com.jawon.app

import android.os.Bundle
import androidx.activity.ComponentActivity
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
        enableEdgeToEdge()
        setContent {
            val roomScheme by preferences.roomScheme.collectAsStateWithLifecycle()
            JawonTheme(roomScheme = roomScheme) {
                Surface(modifier = Modifier.fillMaxSize()) {
                    JawonNavHost()

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
