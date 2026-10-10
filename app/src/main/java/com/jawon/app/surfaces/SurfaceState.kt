package com.jawon.app.surfaces

import com.jawon.app.data.local.Album
import com.jawon.app.data.playback.PlaybackRepository

/**
 * What the places outside the app show (0.7.6): the home-screen widget, the Quick Settings tile,
 * the shortcut held out of the icon. One answer for all of them, so they cannot disagree about
 * what is on.
 *
 * Three cases, and the middle one is the reason this is not simply "what the player says". A
 * widget is on the home screen whether Jawon is running or not, and when it is not there is no
 * player to ask — but there is still a record that was on last, and a widget that forgot it the
 * moment the app closed would be a blank card most of the day.
 */
data class SurfaceState(
    /** The record, if there is one to show. */
    val albumId: String?,
    /** The large line: the song while a record is loaded, the record's name while it is not. */
    val title: String,
    /** The small line under it. */
    val line: String,
    val coverPath: String?,
    /** The record's own colour as the wall has it, 0..1, or 0 when it was never set. */
    val spineHue: Float,
    val isPlaying: Boolean,
    /** A record is loaded in the player now — "next" means something. */
    val isLoaded: Boolean
) {
    companion object {
        /** Nothing has ever been played: the widget is an invitation. */
        val EMPTY = SurfaceState(
            albumId = null,
            title = "Jawon",
            line = "Open to play a record",
            coverPath = null,
            spineHue = 0f,
            isPlaying = false,
            isLoaded = false
        )

        fun of(onNow: PlaybackRepository.OnNow?, lastPlayed: Album?): SurfaceState = when {
            onNow != null -> SurfaceState(
                albumId = onNow.album.id,
                title = onNow.trackTitle?.takeIf { it.isNotBlank() } ?: onNow.album.title,
                line = "${onNow.album.title} · ${onNow.album.artist}",
                coverPath = onNow.album.caseCoverUri,
                spineHue = onNow.album.spineHue,
                isPlaying = onNow.isPlaying,
                isLoaded = true
            )
            // Not running, or nothing loaded: the record that was on, ready to go back on.
            lastPlayed != null -> SurfaceState(
                albumId = lastPlayed.id,
                title = lastPlayed.title,
                line = lastPlayed.artist,
                coverPath = lastPlayed.caseCoverUri,
                spineHue = lastPlayed.spineHue,
                isPlaying = false,
                isLoaded = false
            )
            else -> EMPTY
        }
    }
}

/**
 * The widget's ground: the room's dark, leaning toward the record's own colour.
 *
 * Only a lean. DESIGN.md's rule for colour taken from a cover is that it "can never break the
 * warm-dark palette", and a home screen is the one place Jawon sits beside other people's
 * design: a saturated card would shout there. So the hue is the record's, and the saturation and
 * lightness are fixed, low — two widgets for two records are recognisably different and both
 * recognisably Jawon.
 *
 * Pure (its own HSV arithmetic), so it can be tested off a phone.
 */
object SurfaceColors {
    /** crateBlack, a little lifted: what the card is with no colour to lean toward. */
    const val NEUTRAL: Int = 0xFF1E1713.toInt()

    private const val SATURATION = 0.42f
    private const val VALUE = 0.17f

    /** [hue] is 0..1 round the wheel, or null for a record with no colour of its own. */
    fun ground(hue: Float?): Int {
        if (hue == null) return NEUTRAL
        val h = ((hue % 1f) + 1f) % 1f * 6f
        val sector = h.toInt() % 6
        val f = h - h.toInt()
        val p = VALUE * (1f - SATURATION)
        val q = VALUE * (1f - SATURATION * f)
        val t = VALUE * (1f - SATURATION * (1f - f))
        val (r, g, b) = when (sector) {
            0 -> Triple(VALUE, t, p)
            1 -> Triple(q, VALUE, p)
            2 -> Triple(p, VALUE, t)
            3 -> Triple(p, q, VALUE)
            4 -> Triple(t, p, VALUE)
            else -> Triple(VALUE, p, q)
        }
        fun channel(x: Float) = (x * 255f + 0.5f).toInt().coerceIn(0, 255)
        return (0xFF shl 24) or (channel(r) shl 16) or (channel(g) shl 8) or channel(b)
    }
}
