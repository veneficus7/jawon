package com.jawon.app.ui.wallscene

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.abs

/**
 * The five-beat open, and the curves it is built from.
 *
 * The last of the four files `ENGINEERING.md` names as pure and untested. The properties pinned
 * here are the ones the sequence's own documentation says it depends on and which are close to
 * impossible to confirm by eye on a 1.9-second animation: that the eases start and end exactly
 * where they claim, that the camera's channels genuinely do not overshoot when the objects' do,
 * and that the anticipation joins the main curve without a step.
 */
class OpenSequenceTest {

    // ---- the curves ------------------------------------------------------------------

    @Test
    fun `smootherStep spans zero to one exactly`() {
        assertEquals(0f, OpenSequence.smootherStep(0f), 1e-6f)
        assertEquals(1f, OpenSequence.smootherStep(1f), 1e-6f)
        assertEquals(0.5f, OpenSequence.smootherStep(0.5f), 1e-6f)
    }

    @Test
    fun `smootherStep never goes backwards`() {
        var previous = -1f
        for (i in 0..1000) {
            val v = OpenSequence.smootherStep(i / 1000f)
            assertTrue("went backwards at $i", v >= previous - 1e-6f)
            previous = v
        }
    }

    @Test
    fun `smootherStep leaves and arrives with no speed`() {
        // The whole reason it is used for the camera: a cubic leaves a step change in
        // acceleration at each end, felt as a kick on every move.
        val d = 1e-4f
        assertTrue(OpenSequence.smootherStep(d) < 1e-9f)
        assertTrue(1f - OpenSequence.smootherStep(1f - d) < 1e-9f)
    }

    @Test
    fun `easeSmoothBack still lands exactly on one`() {
        // It overshoots in the middle; it must not overshoot at the end, or the case settles
        // somewhere other than its final position.
        for (overshoot in listOf(0f, 0.04f, 0.16f, 0.5f)) {
            assertEquals(
                "overshoot $overshoot did not land",
                1f, OpenSequence.easeSmoothBack(1f, overshoot), 1e-5f
            )
            assertEquals(
                "overshoot $overshoot did not start at rest",
                0f, OpenSequence.easeSmoothBack(0f, overshoot), 1e-5f
            )
        }
    }

    @Test
    fun `easeSmoothBack with no overshoot is exactly smootherStep`() {
        for (i in 0..100) {
            val t = i / 100f
            assertEquals(
                OpenSequence.smootherStep(t),
                OpenSequence.easeSmoothBack(t, 0f),
                1e-6f
            )
        }
    }

    @Test
    fun `easeSmoothBack actually overshoots on the way`() {
        // If it never exceeds 1 the settle is not there and the motion reads as a dead stop.
        val peak = (0..1000).maxOf { OpenSequence.easeSmoothBack(it / 1000f, 0.16f) }
        assertTrue("no overshoot: peak was $peak", peak > 1.0f)
    }

    // ---- the anticipation bump ---------------------------------------------------------

    @Test
    fun `the bump is zero at both ends and outside its window`() {
        assertEquals(0f, OpenSequence.bump(0f, 0.3f), 1e-6f)
        assertEquals(0f, OpenSequence.bump(0.3f, 0.3f), 1e-6f)
        assertEquals(0f, OpenSequence.bump(0.9f, 0.3f), 1e-6f)
        assertEquals(0f, OpenSequence.bump(-1f, 0.3f), 1e-6f)
    }

    @Test
    fun `the bump peaks at one in the middle of its window`() {
        assertEquals(1f, OpenSequence.bump(0.15f, 0.3f), 1e-4f)
    }

    @Test
    fun `the bump joins the main curve without a step`() {
        // Value *and slope* zero at each end, so it can be added to another curve and leave
        // no seam. A threshold on the value alone would not show that: what makes the join
        // invisible is that the curve leaves zero quadratically rather than linearly, so
        // doubling the distance from the end should roughly quadruple the value. A curve
        // with a non-zero slope would merely double it.
        val w = 0.3f
        val d = 1e-3f
        val near = OpenSequence.bump(d, w)
        val twiceAsFar = OpenSequence.bump(2 * d, w)
        assertEquals(
            "the bump does not leave zero quadratically, so it steps in",
            4.0, (twiceAsFar / near).toDouble(), 0.1
        )

        val nearEnd = OpenSequence.bump(w - d, w)
        val twiceAsFarFromEnd = OpenSequence.bump(w - 2 * d, w)
        assertEquals(
            "the bump does not return to zero quadratically, so it steps out",
            4.0, (twiceAsFarFromEnd / nearEnd).toDouble(), 0.1
        )

        // And it really is negligible there, just not to 1e-4.
        assertTrue("bump was not small at the join", abs(near) < 1e-3f)
    }

    // ---- the beats ---------------------------------------------------------------------

    @Test
    fun `nothing has happened at the start and everything has by the end`() {
        val start = OpenSequence.beatsAt(0f)
        assertEquals(0f, start.pull, 1e-5f)
        assertEquals(0f, start.turn, 1e-5f)
        assertEquals(0f, start.open, 1e-5f)
        assertEquals(0f, start.lift, 1e-5f)

        val end = OpenSequence.beatsAt(1f)
        assertEquals(1f, end.pull, 1e-4f)
        assertEquals(1f, end.turn, 1e-4f)
        assertEquals(1f, end.open, 1e-4f)
        assertEquals(1f, end.lift, 1e-4f)
    }

    @Test
    fun `the camera channels never overshoot even though the objects do`() {
        // ENGINEERING.md and the source both call this out: the camera must not share a curve
        // with the objects, or it bounces along with the case. Keeping them as separate
        // fields is what stops someone re-easing at the call site.
        for (i in 0..1000) {
            val b = OpenSequence.beatsAt(i / 1000f)
            assertTrue("turnCamera overshot to ${b.turnCamera}", b.turnCamera <= 1f + 1e-5f)
            assertTrue("liftCamera overshot to ${b.liftCamera}", b.liftCamera <= 1f + 1e-5f)
            assertTrue("turnCamera went negative", b.turnCamera >= -1e-5f)
            assertTrue("liftCamera went negative", b.liftCamera >= -1e-5f)
        }
    }

    @Test
    fun `progress outside zero to one does not produce nonsense`() {
        for (p in listOf(-3f, -0.001f, 1.001f, 12f)) {
            val b = OpenSequence.beatsAt(p)
            assertTrue("pull not finite at $p", b.pull.isFinite())
            assertTrue("spin not finite at $p", b.spin.isFinite())
            assertTrue("open not finite at $p", b.open.isFinite())
        }
    }

    @Test
    fun `the anticipation happens early and is gone by the settle`() {
        val early = (0..200).maxOf { OpenSequence.beatsAt(it / 1000f).pullNudge }
        assertTrue("no anticipation at the start: $early", early > 0f)
        assertEquals("still nudging at the end", 0f, OpenSequence.beatsAt(1f).pullNudge, 1e-5f)
    }
}
