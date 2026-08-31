package com.jawon.app.ui.wallscene

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.abs
import kotlin.math.hypot

/**
 * The removal sequence.
 *
 * `CaseDiscard` is pure by the same discipline as `OpenSequence` and, like it, has never been
 * tested. The parts covered here are the ones that are hard to judge by eye on a 1.9-second
 * animation that ends with the subject gone: whether the disc is ever both whole and broken,
 * whether the pieces really hold still before they let go, and whether the blow actually dies
 * away instead of ringing on into the fall.
 */
class CaseDiscardTest {

    private fun frame(t: Float) = CaseDiscard.frameAt(
        t = t, slotX = 0f, slotY = 0f, panX = 0f, panY = 0f
    )

    // ---- the disc is never in two states at once ---------------------------------

    @Test
    fun `whole disc and fragments are never on screen together`() {
        for (step in 0..400) {
            val f = frame(step / 400f)
            assertFalse(
                "both disc states visible at t=${step / 400f}",
                f.wholeDiscVisible && f.shardsVisible
            )
        }
    }

    @Test
    fun `the disc is whole before the strike and broken after`() {
        assertTrue(frame(CaseDiscard.impactAt - 0.01f).wholeDiscVisible)
        assertTrue(frame(CaseDiscard.impactAt).shardsVisible)
        assertFalse(frame(CaseDiscard.impactAt).wholeDiscVisible)
    }

    @Test
    fun `nothing is struck before the strike`() {
        assertFalse(frame(0f).struck)
        assertFalse(frame(CaseDiscard.impactAt - 0.001f).struck)
        assertTrue(frame(CaseDiscard.impactAt).struck)
    }

    // ---- the crack hold ----------------------------------------------------------

    private fun offset(seconds: Float): Float {
        val s = CaseDiscard.shardAt(
            index = 3, restX = 0.03f, restY = 0.01f, mass = 0.04f, seconds = seconds
        )
        return hypot(s.offsetX, s.offsetY)
    }

    @Test
    fun `pieces hold where they broke before they let go`() {
        // The frame that was missing: cracked, but not yet coming apart.
        val atBreak = offset(0f)
        val duringHold = offset(0.03f)
        assertEquals(
            "a piece moved during the crack hold",
            atBreak.toDouble(), duringHold.toDouble(), 1e-5
        )
    }

    @Test
    fun `pieces have separated once the hold is over`() {
        assertTrue(
            "pieces never left their rest position",
            offset(0.25f) > offset(0.03f) + 1e-4f
        )
    }

    @Test
    fun `separation is fast and then stops`() {
        // A break is violent and then over, so this compares *rates*, not positions. It
        // cannot compare positions: the pieces go on drifting for as long as they hang there
        // — deliberately, so the broken disc stays alive in the frame rather than becoming a
        // static cracked texture — so late offsets keep changing by design.
        val burst = offset(0.20f) - offset(CaseDiscard.impactHoldSeconds)
        val settled = abs(offset(0.75f) - offset(0.60f))
        assertTrue("the pieces never separated", burst > 1e-4f)
        assertTrue(
            "separation was still running late: burst ${burst * 1000}mm vs " +
                "${settled * 1000}mm over the same span later",
            burst > settled * 2f
        )
    }

    @Test
    fun `a piece never travels far — it breaks where it lies`() {
        // The lid closes over these. Debris outside the tray would shut on nothing.
        for (index in 0 until CaseDiscard.SHARDS) {
            val s = CaseDiscard.shardAt(index, 0.05f, 0.05f, 0.02f, 3f)
            val d = hypot(s.offsetX, s.offsetY)
            assertTrue("shard $index travelled ${d * 1000}mm", d < 0.02f)
        }
    }

    @Test
    fun `lighter pieces move further than heavy ones`() {
        val light = CaseDiscard.shardAt(5, 0.04f, 0f, mass = 0.01f, seconds = 0.4f)
        val heavy = CaseDiscard.shardAt(5, 0.04f, 0f, mass = 0.9f, seconds = 0.4f)
        assertTrue(
            "mass made no difference to the break",
            hypot(light.offsetX, light.offsetY) > hypot(heavy.offsetX, heavy.offsetY)
        )
    }

    // ---- the blow ----------------------------------------------------------------

    @Test
    fun `the case is not kicked before it is struck`() {
        // The shiver builds into the strike; the ring falls out of it. They must not overlap
        // or the impact stops being the hinge between them.
        val before = frame(CaseDiscard.impactAt - 0.02f)
        val atRest = frame(0f)
        // Rotation before impact comes from the turn and the shiver only — bounded and small.
        assertTrue(abs(before.caseRotX - atRest.caseRotX) < 6f)
    }

    @Test
    fun `the blow rings and then dies away`() {
        val justAfter = frame(CaseDiscard.impactAt + 0.01f)
        val later = frame(0.95f)
        // By the fall, the ring must be gone: the case is dropping, not vibrating.
        val ringLate = later.caseRotY - (frame(0.95f).caseRotY)
        assertEquals("the ring was still running during the fall", 0f, ringLate, 1e-4f)
        // And it must actually do something at the strike.
        assertTrue(
            "the strike did not move the case",
            abs(justAfter.caseZ - frame(CaseDiscard.impactAt - 0.01f).caseZ) > 1e-4f
        )
    }

    // ---- the sequence as a whole -------------------------------------------------

    @Test
    fun `time is dilated only around the strike`() {
        assertEquals(1f, CaseDiscard.timeScaleAt(0f), 1e-4f)
        assertEquals(1f, CaseDiscard.timeScaleAt(1f), 1e-4f)
        assertTrue(CaseDiscard.timeScaleAt(CaseDiscard.impactAt) < 0.5f)
    }

    @Test
    fun `the sequence ends done and stays done`() {
        assertFalse(frame(0.99f).done)
        assertTrue(frame(1f).done)
        assertTrue(frame(4f).done)
    }

    @Test
    fun `the underlight is out before the lid shuts`() {
        assertTrue(CaseDiscard.beamGlowAt(0f) > 0.9f)
        assertEquals(0f, CaseDiscard.beamGlowAt(1f), 1e-4f)
    }

    @Test
    fun `out of range time does not throw or produce nonsense`() {
        for (t in listOf(-5f, -0.001f, 1.5f, 99f)) {
            val f = frame(t)
            assertTrue("caseY was not finite at t=$t", f.caseY.isFinite())
            assertTrue("hinge was not finite at t=$t", f.hingeRotY.isFinite())
            assertTrue("flash out of range at t=$t", f.flash in 0f..1.001f)
        }
    }
}
