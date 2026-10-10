package com.jawon.app.ui.common

import android.os.SystemClock
import android.util.Log
import com.jawon.app.BuildConfig

/**
 * **Debug builds only**: counts named events — frames drawn and skipped, the wall's covers being
 * re-applied — and logs the counts every five seconds under the tag `JawonPerf` (0.7.5). The
 * cheapest way to see what the room is doing frame by frame without a trace:
 *
 *     adb logcat -s JawonPerf
 *
 * A no-op in a release build.
 */
object PerfProbe {
    private val counts = HashMap<String, Int>()
    private var since = 0L

    fun count(name: String) {
        if (!BuildConfig.DEBUG) return
        counts[name] = (counts[name] ?: 0) + 1
        val now = SystemClock.uptimeMillis()
        if (since == 0L) since = now
        if (now - since >= 5_000L) {
            Log.d("JawonPerf", counts.entries.sortedBy { it.key }.joinToString { "${it.key}=${it.value}" } + " in ${now - since}ms")
            counts.clear()
            since = now
        }
    }
}
