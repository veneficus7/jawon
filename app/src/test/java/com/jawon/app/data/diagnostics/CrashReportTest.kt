package com.jawon.app.data.diagnostics

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The crash log's string handling.
 *
 * Worth testing more than most string handling is, because of *when* it runs: [CrashLog] calls
 * [CrashReport.format] and [CrashReport.trim] from inside the uncaught-exception handler, with
 * the process already on its way down. There is no second chance and nowhere for a failure to
 * be reported — a bug here costs the report of the bug that caused it.
 */
class CrashReportTest {

    private fun report(at: String = "2026-09-04 05:42:00", trace: String = "java.lang.IllegalStateException: boom") =
        CrashReport.format(
            kind = "fatal",
            thread = "main",
            stackTrace = trace,
            app = "0.5.1 beta (11)",
            device = "samsung SM-S928B",
            android = "Android 15 (API 35)",
            at = at
        )

    // ---- format --------------------------------------------------------------------------

    @Test
    fun `a report says when, which build, and on what`() {
        val text = report()

        // The three things the owner asks first. A stack trace without the build it came from
        // is a guess — the app updates often enough that "which version" is never obvious.
        assertTrue(text.contains("2026-09-04 05:42:00"))
        assertTrue(text.contains("0.5.1 beta (11)"))
        assertTrue(text.contains("samsung SM-S928B"))
        assertTrue(text.contains("Android 15 (API 35)"))
    }

    @Test
    fun `a report says what kind it was and on which thread`() {
        val text = report()
        assertTrue(text.contains("fatal"))
        assertTrue(text.contains("thread main"))
    }

    @Test
    fun `the stack trace survives intact`() {
        val trace = """
            java.lang.IllegalStateException: boom
            	at com.jawon.app.Thing.method(Thing.kt:42)
            Caused by: java.io.IOException: the real one
            	at com.jawon.app.Other.method(Other.kt:7)
        """.trimIndent()

        val text = report(trace = trace)

        // Including the cause chain, which is usually where the answer is — Media3 wraps the
        // real failure in a PlaybackException and the wrapper alone says almost nothing.
        assertTrue(text.contains("Caused by: java.io.IOException: the real one"))
        assertTrue(text.contains("Other.kt:7"))
    }

    @Test
    fun `every report starts with the delimiter, so appending needs no separator logic`() {
        assertTrue(report().startsWith(CrashReport.DELIMITER))

        // Two appended back to back split into exactly two.
        val log = report() + report(at = "2026-09-04 06:00:00")
        assertEquals(2, log.split(CrashReport.DELIMITER).filter { it.isNotBlank() }.size)
    }

    // ---- trim ----------------------------------------------------------------------------

    @Test
    fun `a log under the cap is left completely alone`() {
        val log = report() + report(at = "2026-09-04 06:00:00")
        assertTrue(log.length <= CrashReport.MAX_CHARS)
        assertEquals(log, CrashReport.trim(log))
    }

    @Test
    fun `an oversized log loses its oldest reports and keeps its newest`() {
        // Each report carries its timestamp, so which ones survived is directly checkable.
        val log = buildString {
            repeat(400) { append(report(at = "2026-09-04 00:00:%02d".format(it % 60), trace = "trace $it")) }
        }
        assertTrue("the fixture needs to exceed the cap to test anything", log.length > CrashReport.MAX_CHARS)

        val trimmed = CrashReport.trim(log)

        assertTrue(trimmed.length <= CrashReport.MAX_CHARS)
        // The newest is the point of keeping any of them.
        assertTrue("the most recent report must survive", trimmed.contains("trace 399"))
        // And the oldest is the one worth losing.
        assertFalse("the oldest report should have been dropped", trimmed.contains("trace 0\n"))
    }

    @Test
    fun `trimming drops whole reports, never half of one`() {
        val log = buildString {
            repeat(400) { append(report(trace = "trace $it")) }
        }

        val trimmed = CrashReport.trim(log)

        // Half a stack trace is not a smaller clue, it is a misleading one: it reads as a crash
        // that terminated somewhere it did not. Every surviving fragment must be a complete
        // report, which means each one still carries a full header.
        val reports = trimmed.split(CrashReport.DELIMITER).filter { it.isNotBlank() }
        assertTrue(reports.isNotEmpty())
        reports.forEach {
            assertTrue("a fragment lost its header: ${it.take(60)}", it.contains("Jawon 0.5.1 beta (11)"))
            assertTrue("a fragment lost its trace: ${it.take(60)}", it.contains("trace "))
        }
    }

    @Test
    fun `one report bigger than the whole budget is kept rather than dropped`() {
        // The pathological case: a single enormous trace, from a deep recursion or a
        // StackOverflowError. One complete oversized report beats an empty file — an empty file
        // reads as "nothing went wrong", which is the one thing it must never say.
        val huge = CrashReport.format(
            kind = "fatal",
            thread = "main",
            stackTrace = "java.lang.StackOverflowError\n" + "\tat com.jawon.app.Deep.method(Deep.kt:1)\n".repeat(4000),
            app = "0.5.1 beta (11)",
            device = "samsung SM-S928B",
            android = "Android 15 (API 35)",
            at = "2026-09-04 05:42:00"
        )
        assertTrue("the fixture needs to exceed the cap", huge.length > CrashReport.MAX_CHARS)

        val trimmed = CrashReport.trim(huge)

        assertTrue(trimmed.contains("StackOverflowError"))
        assertTrue(trimmed.contains("Jawon 0.5.1 beta (11)"))
    }

    @Test
    fun `a burst of non-fatals does not evict the fatal underneath them`() {
        // The realistic failure this cap has to survive. A wedged audio pipeline emits
        // onPlayerError repeatedly (see trap 15), and if that burst pushed the originating
        // crash out of the file, the log would describe the symptom and lose the cause.
        //
        // The cap is sized for roughly sixteen reports, so a dozen non-fatals must not be
        // enough to lose the fatal.
        val log = buildString {
            append(report(trace = "the original fatal"))
            repeat(12) { append(report(at = "2026-09-04 05:43:00", trace = "playback error $it")) }
        }

        val trimmed = CrashReport.trim(log)

        assertTrue("the fatal that started it must survive a dozen follow-ups", trimmed.contains("the original fatal"))
    }
}
