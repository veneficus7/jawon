package com.jawon.app.ui.wallscene

import kotlin.math.exp
import kotlin.math.roundToInt

/**
 * How worn an album's case looks, from how much it has actually been listened to.
 *
 * `PLAN.md` §9 #6: heavily played cases show spine wear, so a collection carries a visible
 * record of itself. The data has been accumulating in `PlayEvent` since the resume work; this
 * is the curve that turns it into something the wall can draw.
 *
 * Pure arithmetic, no Android and no Filament types, so it is testable on its own — which the
 * three other files in this package that were written the same way still are not.
 */
object Patina {

    /**
     * Listening time at which a case is ~63% worn.
     *
     * Six hours, which is roughly eight plays of a normal album. Chosen so the effect is
     * *earned*: one listen leaves almost nothing, a record you keep coming back to is visibly
     * marked within a fortnight, and a lifetime favourite saturates rather than degenerating
     * into a black rectangle.
     */
    private const val TAU_MS = 6.0 * 60 * 60 * 1000

    /**
     * How many distinct appearances a case can have over its whole life.
     *
     * **This is not cosmetic quantisation, it is what stops the wall re-baking itself.**
     * `albumTotals()` emits on every write to `PlayEvent`, and the spine, cover and back
     * inlay are bitmaps baked on a background thread and uploaded as textures. A continuous
     * wear value would change the [CaseArtCache] signature on every emission and re-bake three
     * bitmaps per album, forever, while music is playing.
     *
     * Bucketing upstream means the value only changes [STEPS] times in an album's life. It
     * also makes the whole [WallLayout] compare equal between plays, so the `StateFlow` that
     * carries it conflates the emission away and the scene is never told at all.
     */
    private const val STEPS = 8

    /**
     * Wear for one album, `0f`..`1f`, quantised to [STEPS].
     *
     * Absolute rather than relative to the most-played album on the wall: a library where
     * everything has been heard once should show a wall of clean cases, not one artificially
     * distressed record because it happens to lead. Wear means "this has been played a lot",
     * not "this has been played more than its neighbours".
     */
    fun forListening(totalMs: Long): Float {
        if (totalMs <= 0L) return 0f
        val raw = 1.0 - exp(-totalMs / TAU_MS)
        return (raw * STEPS).roundToInt() / STEPS.toFloat()
    }

    /** The whole wall's wear, keyed by album id, ready for [WallLayout.from]. */
    fun byAlbum(totals: List<com.jawon.app.data.local.AlbumListening>): Map<String, Float> =
        totals.asSequence()
            .map { it.albumId to forListening(it.totalMs) }
            .filter { it.second > 0f }
            .toMap()
}
