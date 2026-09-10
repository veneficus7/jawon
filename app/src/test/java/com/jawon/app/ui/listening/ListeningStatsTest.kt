package com.jawon.app.ui.listening

import com.jawon.app.data.local.PlayEvent
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.ZoneId

/**
 * The arithmetic behind every number on the listening screen.
 *
 * **Worth testing because every failure here is invisible.** A histogram in the wrong time zone
 * still looks like a histogram; a streak that counts UTC days still shows a number; a "this year"
 * window that starts in the wrong place still fills the screen with plausible rows. None of it
 * can be found by looking at the app, and all of it can be found by choosing dates that break it.
 *
 * Seoul is used throughout rather than UTC, deliberately: it is +9, so a late-evening play there
 * is the *previous* day in UTC and an implementation that forgot the zone gets a different answer
 * for every test in this file.
 */
class ListeningStatsTest {

    private val seoul: ZoneId = ZoneId.of("Asia/Seoul")

    private fun at(
        year: Int, month: Int, day: Int, hour: Int, minute: Int = 0,
        minutes: Long = 10L,
        album: String = "album",
        track: String = "track"
    ) = PlayEvent(
        albumId = album,
        trackId = track,
        startedAt = LocalDateTime.of(year, month, day, hour, minute)
            .atZone(seoul).toInstant().toEpochMilli(),
        msPlayed = minutes * 60_000L
    )

    private fun nowAt(year: Int, month: Int, day: Int, hour: Int = 12): Long =
        LocalDateTime.of(year, month, day, hour, 0).atZone(seoul).toInstant().toEpochMilli()

    // ---- Periods -----------------------------------------------------------------------

    @Test
    fun `four weeks is twenty-eight local days including today`() {
        val now = nowAt(2026, 9, 10)

        val from = ListeningStats.startOf(ListeningStats.Period.FOUR_WEEKS, now, seoul)

        // Local midnight on 14 August: 28 days counting today.
        assertEquals(
            LocalDate.of(2026, 8, 14).atStartOfDay(seoul).toInstant().toEpochMilli(),
            from
        )
    }

    @Test
    fun `this year starts at local midnight on the first of January`() {
        val now = nowAt(2026, 9, 10)

        val from = ListeningStats.startOf(ListeningStats.Period.THIS_YEAR, now, seoul)

        assertEquals(
            LocalDate.of(2026, 1, 1).atStartOfDay(seoul).toInstant().toEpochMilli(),
            from
        )
        // **The zone is the point.** Local midnight in Seoul is 15:00 on 31 December UTC, so an
        // implementation that used UTC would include nine hours of the previous year — and the
        // one place anybody looks closely at a year total is the last week of December.
        assertTrue(from < nowAt(2026, 1, 1, hour = 9))
    }

    @Test
    fun `all time has no floor and filters nothing`() {
        val events = listOf(at(2019, 1, 1, 10), at(2026, 9, 10, 10))

        val within = ListeningStats.within(
            events, ListeningStats.Period.ALL_TIME, nowAt(2026, 9, 10), seoul
        )

        assertEquals(events, within)
    }

    @Test
    fun `a play just before the window opens is excluded and one just after is kept`() {
        val now = nowAt(2026, 9, 10)
        // 13 August is one day outside four weeks; 14 August at one minute past midnight is in.
        val out = at(2026, 8, 13, 23, 59)
        val inside = at(2026, 8, 14, 0, 1)

        val within = ListeningStats.within(
            listOf(out, inside), ListeningStats.Period.FOUR_WEEKS, now, seoul
        )

        assertEquals(listOf(inside), within)
    }

    // ---- Tallies -----------------------------------------------------------------------

    @Test
    fun `tallies add time and count plays, longest first`() {
        val events = listOf(
            at(2026, 9, 1, 10, minutes = 30, album = "a"),
            at(2026, 9, 1, 11, minutes = 10, album = "b"),
            at(2026, 9, 2, 10, minutes = 15, album = "a")
        )

        val ranked = ListeningStats.tally(events) { it.albumId }

        assertEquals(listOf("a", "b"), ranked.map { it.key })
        assertEquals(45 * 60_000L, ranked[0].totalMs)
        assertEquals(2, ranked[0].plays)
    }

    @Test
    fun `a tie keeps a stable order rather than shuffling between reads`() {
        // Two artists on exactly the same total is not rare on a small library, and an order that
        // changed between reads reads as a bug even though every number in it is right.
        val events = listOf(
            at(2026, 9, 1, 10, minutes = 10, album = "zeta"),
            at(2026, 9, 1, 11, minutes = 10, album = "alpha")
        )

        val once = ListeningStats.tally(events) { it.albumId }.map { it.key }
        val again = ListeningStats.tally(events.reversed()) { it.albumId }.map { it.key }

        assertEquals(listOf("alpha", "zeta"), once)
        assertEquals(once, again)
    }

    @Test
    fun `a null key is skipped rather than counted as its own row`() {
        // Track artists are nullable in this app — the importer leaves them null when the file's
        // tag carried only an album artist. Those must not collect into an "unknown" leader.
        val events = listOf(at(2026, 9, 1, 10, album = "a"), at(2026, 9, 1, 11, album = "b"))

        val ranked = ListeningStats.tally(events) { if (it.albumId == "a") null else it.albumId }

        assertEquals(listOf("b"), ranked.map { it.key })
    }

    // ---- When you listen ----------------------------------------------------------------

    @Test
    fun `the hour histogram is in local time`() {
        // 01:30 in Seoul is 16:30 the previous day in UTC. A histogram built on UTC would put
        // this in the afternoon — the opposite end of the chart from where it belongs.
        val events = listOf(at(2026, 9, 10, 1, 30))

        val hours = ListeningStats.byHour(events, seoul)

        assertEquals(10 * 60_000L, hours[1])
        assertEquals(0L, hours[16])
    }

    @Test
    fun `a stretch counts against the hour it started in`() {
        // Deliberately not spread across the hours it covered: the question the chart answers is
        // "when do I put a record on", and a forty-minute side that began at 23:50 belongs to the
        // late night rather than being split across midnight.
        val events = listOf(at(2026, 9, 10, 23, 50, minutes = 40))

        val hours = ListeningStats.byHour(events, seoul)

        assertEquals(40 * 60_000L, hours[23])
        assertEquals(0L, hours[0])
    }

    @Test
    fun `weekdays run Monday first`() {
        // 2026-09-10 is a Thursday.
        val hours = ListeningStats.byWeekday(listOf(at(2026, 9, 10, 12)), seoul)

        assertEquals(10 * 60_000L, hours[3])
        assertEquals(0L, hours[0])
    }

    @Test
    fun `the peak hour is the busiest one, and null when nothing was heard`() {
        val events = listOf(
            at(2026, 9, 10, 9, minutes = 5),
            at(2026, 9, 10, 22, minutes = 50)
        )

        assertEquals(22, ListeningStats.peakHour(ListeningStats.byHour(events, seoul)))
        assertNull(ListeningStats.peakHour(LongArray(24)))
    }

    // ---- Streaks -------------------------------------------------------------------------

    @Test
    fun `days listened are distinct local dates in order`() {
        val events = listOf(
            at(2026, 9, 10, 23),
            at(2026, 9, 10, 1),
            at(2026, 9, 8, 12)
        )

        val days = ListeningStats.daysListened(events, seoul)

        assertEquals(listOf(LocalDate.of(2026, 9, 8), LocalDate.of(2026, 9, 10)), days)
    }

    @Test
    fun `the longest streak counts consecutive days only`() {
        val days = listOf(
            LocalDate.of(2026, 9, 1),
            LocalDate.of(2026, 9, 2),
            LocalDate.of(2026, 9, 3),
            // gap
            LocalDate.of(2026, 9, 6),
            LocalDate.of(2026, 9, 7)
        )

        assertEquals(3, ListeningStats.longestStreak(days))
    }

    @Test
    fun `no listening is a streak of zero, not of one`() {
        assertEquals(0, ListeningStats.longestStreak(emptyList()))
        assertEquals(0, ListeningStats.currentStreak(emptyList(), LocalDate.of(2026, 9, 10)))
    }

    @Test
    fun `a streak survives the night before it has been continued`() {
        // **The one people notice.** A run that reset the instant the clock passed midnight would
        // show zero every morning to somebody who listens every evening.
        val days = listOf(
            LocalDate.of(2026, 9, 8),
            LocalDate.of(2026, 9, 9)
        )

        assertEquals(2, ListeningStats.currentStreak(days, LocalDate.of(2026, 9, 10)))
    }

    @Test
    fun `a streak that has actually lapsed reads zero`() {
        val days = listOf(LocalDate.of(2026, 9, 1), LocalDate.of(2026, 9, 2))

        assertEquals(0, ListeningStats.currentStreak(days, LocalDate.of(2026, 9, 10)))
    }

    @Test
    fun `today alone is a streak of one`() {
        val days = listOf(LocalDate.of(2026, 9, 10))

        assertEquals(1, ListeningStats.currentStreak(days, LocalDate.of(2026, 9, 10)))
    }

    @Test
    fun `a streak across a month boundary is unbroken`() {
        val days = listOf(
            LocalDate.of(2026, 8, 30),
            LocalDate.of(2026, 8, 31),
            LocalDate.of(2026, 9, 1)
        )

        assertEquals(3, ListeningStats.longestStreak(days))
        assertEquals(3, ListeningStats.currentStreak(days, LocalDate.of(2026, 9, 1)))
    }

    @Test
    fun `an empty history answers everything without throwing`() {
        val none = emptyList<PlayEvent>()
        val now = nowAt(2026, 9, 10)

        assertTrue(ListeningStats.tally(none) { it.albumId }.isEmpty())
        assertTrue(ListeningStats.byHour(none, seoul).all { it == 0L })
        assertTrue(ListeningStats.byWeekday(none, seoul).all { it == 0L })
        assertTrue(ListeningStats.daysListened(none, seoul).isEmpty())
        assertTrue(
            ListeningStats.within(none, ListeningStats.Period.THIS_YEAR, now, seoul).isEmpty()
        )
    }
}
