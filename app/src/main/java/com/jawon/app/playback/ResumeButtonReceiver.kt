package com.jawon.app.playback

import android.content.Context
import android.content.Intent
import androidx.media3.common.util.UnstableApi
import androidx.media3.session.MediaButtonReceiver

/**
 * Whether a button may wake a closed player (0.7.6).
 *
 * Android sends "play" — a headset's button, a car stereo that asks for music the moment it
 * connects — to whichever app played last, **even after that app has gone**, if the app declares
 * a receiver for it. Jawon declared none until now, so a closed Jawon stayed closed. Declaring one
 * is what lets the system offer "resume" in its media controls after the app is gone, which is
 * wanted; it is also what lets a car start a record nobody asked for, which is not.
 *
 * So the receiver is declared, and answers only when the owner has said headphones may start the
 * music (`AppPreferences.resumeWithHeadphones`, off by default). With the switch off this is
 * exactly the behaviour of every version before it: a button reaches a running Jawon through its
 * session as it always did, and a closed one does not stir.
 *
 * It reads the preference straight from its file: a receiver is built by the system with nothing
 * of the app around it, and this one may be the first code the process runs.
 */
@UnstableApi
class ResumeButtonReceiver : MediaButtonReceiver() {

    override fun shouldStartForegroundService(context: Context, intent: Intent): Boolean =
        allowed(context)

    companion object {
        /** `AppPreferences`' file, and the key the switch is saved under. */
        const val PREFS_FILE = "jawon"
        const val KEY = "resume_with_headphones"

        fun allowed(context: Context): Boolean =
            context.getSharedPreferences(PREFS_FILE, Context.MODE_PRIVATE).getBoolean(KEY, false)
    }
}
