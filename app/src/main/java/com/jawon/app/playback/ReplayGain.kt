package com.jawon.app.playback

import kotlin.math.pow

/**
 * Volume levelling from the tags a collection already carries.
 *
 * Anyone who ripped their own CDs has ReplayGain values written into the files by whatever
 * did the ripping, and expects a serious player to honour them. Without it, a quiet 1970s
 * master and a loudness-war remaster sit on the same shelf and one of them takes your head
 * off — which on a wall you browse by *looking* is a worse experience than on a list, because
 * you pick records by eye and get hit by whatever the mastering engineer decided.
 *
 * **Album gain is the default, and that is a product decision rather than a preference.**
 * `PLAN.md` §2 makes the album the atomic unit. Track gain normalises every track to the same
 * loudness, which flattens the dynamics *between* tracks that the person who sequenced the
 * record chose — the quiet closer after the loud one stops being quiet. Album gain moves the
 * whole record by one number and leaves its internal shape alone. For an app built around
 * playing albums whole, track gain is the wrong answer even though it is the more common
 * default elsewhere.
 *
 * Pure arithmetic and string handling, so it is testable without a device or a player.
 */
object ReplayGain {

    /** What to level by. */
    enum class Mode {
        /** Honour nothing; play files at whatever level they were written at. */
        OFF,

        /** One adjustment for the whole record, preserving the gaps the artist sequenced. */
        ALBUM,

        /** Every track to the same loudness. Flattens an album's own dynamics. */
        TRACK;

        companion object {
            fun fromName(name: String?): Mode =
                entries.firstOrNull { it.name == name } ?: ALBUM
        }
    }

    /** The keys writers actually use, in the casing they actually use. */
    const val KEY_ALBUM_GAIN = "REPLAYGAIN_ALBUM_GAIN"
    const val KEY_TRACK_GAIN = "REPLAYGAIN_TRACK_GAIN"

    /**
     * How far the volume may be pushed either way, in decibels.
     *
     * A tag is only a claim, and a wrong one is capable of asking for +30dB. Clamping is what
     * stops a mistyped or maliciously written tag from producing a sudden painfully loud
     * track through headphones — this is the one place in the app where bad data has a
     * physical consequence.
     */
    const val MAX_ABS_DB = 15f

    /**
     * Parse a ReplayGain tag value into decibels.
     *
     * The format is loose in practice: `-6.54 dB`, `-6.54dB`, `-6.54`, `+3.2 dB`, sometimes
     * with stray whitespace. Anything that does not yield a finite number is no adjustment at
     * all rather than a guess — silence is the right failure for a value whose only job is to
     * change how loud something is.
     */
    fun parseDb(raw: String?): Float? {
        if (raw.isNullOrBlank()) return null
        val cleaned = raw.trim()
            .removeSuffix("dB").removeSuffix("DB").removeSuffix("db")
            .trim()
        val value = cleaned.toFloatOrNull() ?: return null
        return if (value.isFinite()) value else null
    }

    /**
     * The linear volume multiplier for a gain in decibels, clamped to [MAX_ABS_DB].
     *
     * Returns `1f` for no adjustment, which is also what an absent or unparseable tag gives,
     * so a caller never has to special-case "no tag".
     */
    fun volumeFor(db: Float?): Float {
        if (db == null || !db.isFinite()) return 1f
        val clamped = db.coerceIn(-MAX_ABS_DB, MAX_ABS_DB)
        return 10f.pow(clamped / 20f)
    }

    /**
     * The volume to play at, given the tags found on a track and what the user asked for.
     *
     * Album gain falls back to track gain when a record carries only the latter — a file with
     * some levelling information is better served by using it than by ignoring it because the
     * preferred flavour is missing. The reverse fallback is deliberately *not* here: someone
     * who asked for track gain and got album gain would have every track in a record moved by
     * the same amount, which is not what they asked for and is indistinguishable from the
     * feature not working.
     */
    fun volumeFor(mode: Mode, albumGainDb: Float?, trackGainDb: Float?): Float = when (mode) {
        Mode.OFF -> 1f
        Mode.ALBUM -> volumeFor(albumGainDb ?: trackGainDb)
        Mode.TRACK -> volumeFor(trackGainDb)
    }
}
