package com.jawon.app.data.diagnostics

import android.content.Context
import android.net.Uri
import android.os.Build
import androidx.core.content.FileProvider
import dagger.hilt.android.qualifiers.ApplicationContext
import java.io.File
import java.io.PrintWriter
import java.io.StringWriter
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import javax.inject.Inject
import javax.inject.Singleton

/**
 * What went wrong, written somewhere the owner can actually get at it.
 *
 * **The problem this solves is not "crashes are unhandled" — it is that nobody ever hears about
 * them.** Jawon has been on fifteen-odd phones belonging to other people, and the total signal
 * that has ever come back is three testers saying it was "not that smooth". If it had crashed
 * for any of them, there was no route by which anyone would have found out: the app has no
 * analytics by design, the testers are not going to run `adb`, and logcat is useless here
 * anyway — see trap 15 in `ENGINEERING.md`, Samsung's telephony spam buries app logs entirely.
 *
 * So: write it to a file, and give the user a button that shares it.
 *
 * ## Two kinds of thing land here
 *
 * **Fatal** — an uncaught exception, caught on its way out of the process. [install] hooks
 * `Thread.setDefaultUncaughtExceptionHandler` for this.
 *
 * **Non-fatal** — something that went wrong, was handled, and left the app in a state the user
 * will experience as broken without any exception reaching them. `onPlayerError` is the
 * motivating case and the reason this class has [record] at all: a wedged audio pipeline shows
 * up as a deck stuck at `0:00` and a phone that gets hot, and until now the throwable behind it
 * went to `Log.e` and therefore nowhere. Trap 19 is the long version of that story.
 *
 * ## Constraints that shaped this
 *
 * The fatal path runs **while the process is dying**, which rules out most things. No
 * coroutines, no injection at call time, no work that can block: just format a string and
 * append it, synchronously, with every step wrapped so that a failure to record a crash cannot
 * itself become the crash. The previous handler is always called afterwards, so the system
 * still shows its dialog and anything upstream still sees the exception — this observes, it
 * does not swallow.
 *
 * Reports are capped rather than rotated ([CrashReport.trim]). An unbounded log on a phone
 * whose storage the user did not volunteer is its own small bug, and the oldest crash is the
 * least interesting one.
 */
@Singleton
class CrashLog @Inject constructor(
    @ApplicationContext private val context: Context
) {

    /**
     * Inside `filesDir`, not `cacheDir`: a crash report the system evicts before the user gets
     * round to sending it is worse than useless, because it looks like nothing went wrong.
     *
     * Its own directory because of the FileProvider. `res/xml/file_paths.xml` deliberately
     * exposes one narrow path at a time rather than `filesDir` wholesale — the covers, the
     * database and the listening history all live there too and no other app has any business
     * reading them.
     */
    private val dir: File get() = File(context.filesDir, DIR).apply { mkdirs() }
    private val file: File get() = File(dir, FILE)

    /**
     * Resolved once, here, rather than inside the handler.
     *
     * From `PackageManager` rather than `BuildConfig` because nothing in this project uses
     * `BuildConfig` — `buildConfig` is not among the enabled `buildFeatures`, and the debug
     * check in `PlaybackRepository` and `CoverArtFetcher` goes through `ApplicationInfo` for
     * the same reason. Turning the feature on for one diagnostic string would be a build
     * change in service of a convention this codebase does not have.
     *
     * Eager, and this is the load-bearing part: the alternative is a `by lazy` whose *first*
     * evaluation happens inside the uncaught-exception handler, on a dying process, which is
     * the one moment to be doing no work that can fail.
     */
    private val version: String = runCatching {
        val info = context.packageManager.getPackageInfo(context.packageName, 0)
        // longVersionCode is API 28+, and minSdk is 28 — no branch needed.
        "${info.versionName} (${info.longVersionCode})"
    }.getOrDefault("unknown build")

    /**
     * Installs the uncaught-exception handler. Call once, from `Application.onCreate`.
     *
     * Chaining to the existing handler is not optional politeness — it is what keeps the
     * platform's own behaviour intact. Without the delegate the process would be left running
     * in an undefined state with no dialog, which is a considerably worse experience than the
     * crash.
     */
    fun install() {
        val previous = Thread.getDefaultUncaughtExceptionHandler()
        Thread.setDefaultUncaughtExceptionHandler { thread, error ->
            // Nothing in here may throw. A handler that dies inside the handler loses both the
            // report and the platform's dialog.
            runCatching { write(FATAL, thread.name, error) }
            previous?.uncaughtException(thread, error)
        }
    }

    /**
     * Records something that went wrong but did not kill the process.
     *
     * [tag] is what the owner will scan for — keep it short and specific ("playback", "import").
     */
    fun record(tag: String, error: Throwable) {
        runCatching { write(tag, Thread.currentThread().name, error) }
    }

    private fun write(kind: String, thread: String, error: Throwable) {
        val report = CrashReport.format(
            kind = kind,
            thread = thread,
            stackTrace = stackTraceOf(error),
            app = version,
            device = "${Build.MANUFACTURER} ${Build.MODEL}",
            android = "Android ${Build.VERSION.RELEASE} (API ${Build.VERSION.SDK_INT})",
            at = TIMESTAMP.format(Date(System.currentTimeMillis()))
        )
        val existing = if (file.exists()) file.readText() else ""
        file.writeText(CrashReport.trim(existing + report))
    }

    /** Everything recorded so far, or null when there is nothing — which is the normal state. */
    fun report(): String? = runCatching {
        file.takeIf { it.exists() && it.length() > 0 }?.readText()
    }.getOrNull()

    fun hasReport(): Boolean = runCatching { file.exists() && file.length() > 0 }.getOrDefault(false)

    fun clear() {
        runCatching { file.delete() }
    }

    /**
     * A URI another app may read, for the share sheet.
     *
     * Goes through the same FileProvider the updater uses, with its own declared path. Returns
     * null rather than throwing if there is nothing to share.
     */
    fun shareUri(): Uri? = runCatching {
        file.takeIf { it.exists() && it.length() > 0 }?.let {
            FileProvider.getUriForFile(context, "${context.packageName}.updates", it)
        }
    }.getOrNull()

    private fun stackTraceOf(error: Throwable): String = StringWriter().also { sw ->
        // printStackTrace walks the cause chain, which is where the useful half usually is:
        // Media3 wraps the real failure in a PlaybackException, and the wrapper alone says
        // almost nothing.
        PrintWriter(sw).use { error.printStackTrace(it) }
    }.toString()

    private companion object {
        const val DIR = "diagnostics"
        const val FILE = "crash.log"
        const val FATAL = "fatal"
        val TIMESTAMP = SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.US)
    }
}

/**
 * The parts of a crash report that are just string handling, kept away from Android so they can
 * be tested. Which matters more than usual here: the one path that must not misbehave is the
 * one that only ever runs while the process is already dying.
 */
internal object CrashReport {

    /** Separates reports in the file, and is what [trim] splits on. */
    const val DELIMITER = "\n——————————————————————————————\n"

    /**
     * Roughly sixteen reports' worth. Big enough that a burst of non-fatals does not evict the
     * fatal that caused them, small enough to paste into a message.
     */
    const val MAX_CHARS = 32_000

    fun format(
        kind: String,
        thread: String,
        stackTrace: String,
        app: String,
        device: String,
        android: String,
        at: String
    ): String = buildString {
        append(DELIMITER)
        // The header answers what the owner asks first, in that order: when, which build, and
        // on what. A stack trace without the build it came from is a guess.
        appendLine("$at  ·  $kind  ·  thread $thread")
        appendLine("Jawon $app")
        appendLine("$device  ·  $android")
        appendLine()
        append(stackTrace.trimEnd())
        appendLine()
    }

    /**
     * Drops whole reports from the front until the log fits.
     *
     * Whole reports, never a character cap: half a stack trace is not a smaller clue, it is a
     * misleading one. If a single report is somehow bigger than the budget it is kept anyway —
     * one complete oversized trace beats an empty file.
     */
    fun trim(log: String): String {
        if (log.length <= MAX_CHARS) return log

        // The leading delimiter means the first fragment is always empty; keep the split
        // aligned with what format() writes rather than reconstructing it.
        val reports = log.split(DELIMITER).filter { it.isNotBlank() }
        var kept = reports

        while (kept.size > 1 && sizeOf(kept) > MAX_CHARS) {
            kept = kept.drop(1)
        }
        return joined(kept)
    }

    private fun sizeOf(reports: List<String>): Int = joined(reports).length

    private fun joined(reports: List<String>): String =
        reports.joinToString(separator = "") { DELIMITER + it }
}
