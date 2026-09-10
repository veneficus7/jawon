package com.jawon.app.ui.listening

import com.jawon.app.data.local.PlayEvent
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId

/**
 * Everything the listening record can be asked, derived from `PlayEvent` rows and nothing else.
 *
 * ## Why this is one pure object rather than a dozen queries
 *
 * Every number here is a *question about history*, and questions about history are exactly the
 * kind that get quietly wrong: an hour histogram in the wrong time zone, a streak that counts UTC
 * days, a "this year" window that starts on the first of January somewhere else. Those are all
 * invisible on screen — the chart still looks like a chart — and none of them can be found by
 * looking at the app. Kept pure and given an explicit [ZoneId], they can be asserted on the JVM
 * against dates chosen to break them.
 *
 * It also means the period selector costs nothing. The rows come out of the database once and
 * every window is a filter over them in memory, so switching between four weeks and all time is
 * instant rather than a round trip — which is the whole feel of the thing being borrowed here.
 *
 * ## On ranking tracks
 *
 * `ListeningViewModel` used to say, correctly, that this screen is deliberately not a leaderboard
 * of tracks — `PLAN.md` §2 puts the album at the centre of the app, and a screen ranking songs
 * would be the first place that stopped being true. The owner has since asked for stats.fm-style
 * features, and top tracks are most of what that means, so the rule is worth restating rather
 * than deleting: **the album is the unit you play; a track is something the record can report on.**
 * Nothing here offers to play a single track, and the album leaderboard stays first on the screen.
 * The distinction is between a control and a report, and it is the reason this is allowed to exist
 * without contradicting the plan.
 */
object ListeningStats {

    /**
     * How far back a screenful of statistics reaches.
     *
     * The four stats.fm settled on, and they are well chosen: a month is "what I am into now", six
     * months is a season, a year is the shareable one, and all time is the collection. Anything
     * finer is noise on a library this size and anything coarser is the same answer twice.
     */
    enum class Period(val label: String) {
        FOUR_WEEKS("4 WEEKS"),
        SIX_MONTHS("6 MONTHS"),
        THIS_YEAR("THIS YEAR"),
        ALL_TIME("ALL TIME")
    }

    /**
     * The first instant a period includes.
     *
     * **Local dates, not millisecond arithmetic.** "Four weeks" is twenty-eight *days* back from
     * today's date, not `now - 28 * 86_400_000` — those differ by an hour twice a year, and the
     * difference lands exactly on the boundary where a play either counts or does not. Same for
     * "this year", which starts at local midnight on 1 January and nowhere else.
     */
    fun startOf(period: Period, now: Long, zone: ZoneId): Long {
        val today = Instant.ofEpochMilli(now).atZone(zone).toLocalDate()
        val from = when (period) {
            Period.FOUR_WEEKS -> today.minusDays(27)
            Period.SIX_MONTHS -> today.minusMonths(6).plusDays(1)
            Period.THIS_YEAR -> today.withDayOfYear(1)
            Period.ALL_TIME -> return Long.MIN_VALUE
        }
        return from.atStartOfDay(zone).toInstant().toEpochMilli()
    }

    /** The events inside a period. */
    fun within(events: List<PlayEvent>, period: Period, now: Long, zone: ZoneId): List<PlayEvent> {
        if (period == Period.ALL_TIME) return events
        val from = startOf(period, now, zone)
        return events.filter { it.startedAt >= from }
    }

    /** One thing that was listened to, and how much. */
    data class Tally(val key: String, val totalMs: Long, val plays: Int)

    /**
     * Totals per key, longest first.
     *
     * Ties broken by key so the order is stable: two artists on exactly the same total would
     * otherwise swap places between reads, which reads as a bug in a leaderboard even though
     * every number in it is right.
     */
    fun tally(events: List<PlayEvent>, key: (PlayEvent) -> String?): List<Tally> {
        val totals = HashMap<String, LongArray>()
        for (event in events) {
            val k = key(event) ?: continue
            val slot = totals.getOrPut(k) { longArrayOf(0L, 0L) }
            slot[0] += event.msPlayed
            slot[1] += 1
        }
        return totals.entries
            .map { Tally(it.key, it.value[0], it.value[1].toInt()) }
            .sortedWith(compareByDescending<Tally> { it.totalMs }.thenBy { it.key })
    }

    /**
     * Listening by hour of the local day — the shape of when you listen.
     *
     * Attributed to the hour a stretch *started*, not spread across the hours it covered. That is
     * the cruder of the two and the right one: what the chart is for is "I listen after midnight",
     * and a forty-minute side that began at 23:50 belongs to the late night rather than being
     * split ten minutes to one day and thirty to the next.
     */
    fun byHour(events: List<PlayEvent>, zone: ZoneId): LongArray {
        val hours = LongArray(24)
        for (event in events) {
            val hour = Instant.ofEpochMilli(event.startedAt).atZone(zone).hour
            hours[hour] += event.msPlayed
        }
        return hours
    }

    /** Listening by day of the week, Monday first — `DayOfWeek`'s own order. */
    fun byWeekday(events: List<PlayEvent>, zone: ZoneId): LongArray {
        val days = LongArray(7)
        for (event in events) {
            val index = Instant.ofEpochMilli(event.startedAt).atZone(zone).dayOfWeek.value - 1
            days[index] += event.msPlayed
        }
        return days
    }

    /** Every local date with listening on it, in order. */
    fun daysListened(events: List<PlayEvent>, zone: ZoneId): List<LocalDate> =
        events.map { Instant.ofEpochMilli(it.startedAt).atZone(zone).toLocalDate() }
            .distinct()
            .sorted()

    /**
     * The longest run of consecutive days with any listening on them.
     *
     * Counted over local dates rather than elapsed time, so a run is unbroken if you listened
     * late on Monday and early on Wednesday only when Tuesday has something too. Zero for an
     * empty history rather than one — no listening is not a streak of one day.
     */
    fun longestStreak(days: List<LocalDate>): Int {
        if (days.isEmpty()) return 0
        var best = 1
        var run = 1
        for (i in 1 until days.size) {
            run = if (days[i] == days[i - 1].plusDays(1)) run + 1 else 1
            if (run > best) best = run
        }
        return best
    }

    /**
     * The run of consecutive days ending today or yesterday, or zero if it has been broken.
     *
     * **Yesterday counts, and that is the whole design of a streak.** A run that resets the
     * instant the clock passes midnight would show zero every morning to somebody who listens
     * every evening, which is both wrong and dispiriting. The day is not over until it is over.
     */
    fun currentStreak(days: List<LocalDate>, today: LocalDate): Int {
        if (days.isEmpty()) return 0
        val last = days.last()
        if (last < today.minusDays(1)) return 0
        var run = 1
        for (i in days.indices.reversed()) {
            if (i == 0) break
            if (days[i - 1] == days[i].minusDays(1)) run++ else break
        }
        return run
    }

    /** The busiest hour, or null when nothing has been heard. Ties go to the earlier hour. */
    fun peakHour(hours: LongArray): Int? {
        val max = hours.maxOrNull() ?: return null
        if (max <= 0L) return null
        return hours.indexOfFirst { it == max }
    }
}
