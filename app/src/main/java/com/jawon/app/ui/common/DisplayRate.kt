package com.jawon.app.ui.common

import android.content.Context
import android.os.Build
import android.view.WindowManager

/**
 * How many times a second this phone's screen is redrawn, on every Android this runs on.
 *
 * **`Context.getDisplay()` only exists from Android 11**, and all three rooms called it — on
 * Android 9 and 10, which are two of the versions this app claims to run on. It happened to work
 * there, because a method of that name was already in the platform, hidden, and the hidden-API
 * rules of those two versions let an app through with a warning. That is a phone-maker's change
 * away from a crash at the first frame of every room, and it is the kind of thing lint exists to
 * catch — it had been saying so; lint was not part of any check that was being run. Found on
 * 2026-10-10 by running it.
 *
 * So: the documented route for each version, and a fallback instead of an exception if a context
 * has no display to ask.
 */
@Suppress("DEPRECATION")
fun Context.displayRefreshHz(fallback: Float): Float = runCatching {
    val display = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
        display
    } else {
        (getSystemService(Context.WINDOW_SERVICE) as? WindowManager)?.defaultDisplay
    }
    display?.refreshRate?.takeIf { it > 0f }
}.getOrNull() ?: fallback
