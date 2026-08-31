package com.jawon.app.ui.wallscene

import com.jawon.app.data.local.AlbumListening
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The wear curve, which decides how a collection ages on the shelf.
 *
 * Worth testing rather than eyeballing for two reasons. The quantisation is not cosmetic —
 * it is what stops `albumTotals()` re-baking every spine on the wall while music plays — and
 * a curve that is wrong at the low end distresses records nobody has really listened to,
 * which is the exact failure that would make the feature look like a rendering bug.
 */
class PatinaTest {

    private val minute = 60_000L
    private val hour = 60 * minute

    // ---- the floor ---------------------------------------------------------------

    @Test
    fun `never played is perfectly clean`() {
        assertEquals(0f, Patina.forListening(0L), 0f)
    }

    @Test
    fun `negative or nonsense totals do not wear a case`() {
        assertEquals(0f, Patina.forListening(-1L), 0f)
        assertEquals(0f, Patina.forListening(Long.MIN_VALUE), 0f)
    }

    @Test
    fun `a single listen leaves almost nothing`() {
        // ~45 minutes is one album. It should read as new, not as loved.
        assertTrue(
            "one play should stay in the lowest step or two",
            Patina.forListening(45 * minute) <= 0.25f
        )
    }

    // ---- the shape ---------------------------------------------------------------

    @Test
    fun `wear increases with listening and never decreases`() {
        var previous = -1f
        for (hours in 0..40) {
            val wear = Patina.forListening(hours * hour)
            assertTrue("wear went backwards at ${hours}h", wear >= previous)
            previous = wear
        }
    }

    @Test
    fun `wear is bounded to one however much it is played`() {
        assertTrue(Patina.forListening(10_000 * hour) <= 1f)
        assertEquals(1f, Patina.forListening(10_000 * hour), 0.0001f)
    }

    @Test
    fun `a record played for a fortnight is visibly worn but not destroyed`() {
        val wear = Patina.forListening(20 * hour)
        assertTrue("20h should be clearly worn, was $wear", wear >= 0.75f)
    }

    // ---- the quantisation, which is the part that protects the frame rate ---------

    @Test
    fun `wear only ever takes one of nine values`() {
        val seen = (0..600).map { Patina.forListening(it * 6 * minute) }.toSet()
        assertTrue(
            "expected at most 9 distinct steps, got ${seen.size}: ${seen.sorted()}",
            seen.size <= 9
        )
    }

    @Test
    fun `a few more minutes of listening does not change the value`() {
        // The guard against re-baking three bitmaps per album on every PlayEvent write.
        val base = 3 * hour
        val wear = Patina.forListening(base)
        for (extra in listOf(1L, 30 * 1000L, minute, 2 * minute)) {
            assertEquals(
                "wear moved after only ${extra}ms more listening",
                wear, Patina.forListening(base + extra), 0f
            )
        }
    }

    @Test
    fun `every step is a clean multiple of one eighth`() {
        for (hours in 0..60) {
            val wear = Patina.forListening(hours * hour)
            val steps = wear * 8f
            assertEquals(
                "step $wear is not on the grid",
                steps.toDouble(), Math.round(steps).toDouble(), 0.0001
            )
        }
    }

    // ---- the map the wall actually consumes ---------------------------------------

    @Test
    fun `byAlbum omits albums with no meaningful listening`() {
        val map = Patina.byAlbum(
            listOf(
                AlbumListening("played", 20 * hour, 30),
                AlbumListening("barely", 1L, 1),
                AlbumListening("never", 0L, 0)
            )
        )
        assertTrue(map.containsKey("played"))
        assertNull(
            "an album with a millisecond of history should not be aged",
            map["barely"]
        )
        assertNull(map["never"])
    }

    @Test
    fun `wear is absolute, not relative to the most played album`() {
        // A library where everything has been heard once should be a wall of clean cases,
        // not one artificially distressed record because it happens to lead.
        val lonely = Patina.byAlbum(listOf(AlbumListening("a", 45 * minute, 1)))
        val crowded = Patina.byAlbum(
            listOf(
                AlbumListening("a", 45 * minute, 1),
                AlbumListening("b", 300 * hour, 400)
            )
        )
        assertEquals(
            "the same album aged differently because of its neighbours",
            lonely["a"] ?: 0f, crowded["a"] ?: 0f, 0f
        )
    }

    @Test
    fun `empty history produces an empty map rather than throwing`() {
        assertTrue(Patina.byAlbum(emptyList()).isEmpty())
    }
}
