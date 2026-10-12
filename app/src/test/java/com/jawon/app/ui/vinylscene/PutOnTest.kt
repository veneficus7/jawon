package com.jawon.app.ui.vinylscene

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.abs
import kotlin.math.hypot

/** A record being put on, the arm going somewhere, a platter under a motor, and what a needle sounds like. */
class PutOnTest {

    private fun at(t: Float) = PutOn.Pose().also { PutOn.at(t, it) }

    private val steps: List<Float> get() {
        val out = ArrayList<Float>()
        var t = 0f
        while (t <= PutOn.SETTLED_AT + 0.6f) {
            out += t
            t += 1f / 240f
        }
        return out
    }

    /** How far out of the sleeve's mouth the record's middle has come. */
    private fun along(p: PutOn.Pose): Float =
        (p.recordX - PutOn.SLEEVE_MIDDLE_X) * PutOn.MOUTH_X + (p.recordZ - PutOn.SLEEVE_MIDDLE_Z) * PutOn.MOUTH_Z

    // ---- The sequence ------------------------------------------------------------------------------

    @Test
    fun `it ends with everything where a record that is on has it`() {
        val end = PutOn.Pose().also { PutOn.rest(it) }
        assertEquals(PutOn.SLEEVE_X, end.sleeveX, 1e-6f)
        assertEquals(PutOn.SLEEVE_Y, end.sleeveY, 1e-6f)
        assertEquals(PutOn.SLEEVE_Z, end.sleeveZ, 1e-6f)
        assertEquals(PutOn.SLEEVE_YAW, end.sleeveYaw, 1e-5f)
        assertEquals(VinylDimens.SPINDLE_X, end.recordX, 1e-6f)
        assertEquals(VinylDimens.SPINDLE_Z, end.recordZ, 1e-6f)
        assertEquals(VinylDimens.RECORD_Y, end.recordY, 1e-6f)
        // The card where the room looks for a finger on a track: square to you, on the sleeve.
        assertEquals(PutOn.CARD_X, end.cardX, 1e-5f)
        assertEquals(PutOn.CARD_Y, end.cardY, 1e-5f)
        assertEquals(PutOn.CARD_Z, end.cardZ, 1e-5f)
        assertEquals(PutOn.CARD_YAW, end.cardYaw, 1e-4f)
        // Which is where the room looks for a finger on one of its lines.
        assertEquals(SLEEVE_BACK_X, end.cardX, 1e-5f)
        assertEquals(SLEEVE_FRONT_Z, end.cardZ, 1e-5f)
        assertTrue(end.sleeveSeen && end.recordSeen && end.cardSeen && end.seated && end.motor)
        // And it has got there by the time it says it has.
        val settled = at(PutOn.SETTLED_AT)
        assertEquals(end.cardX, settled.cardX, 1e-5f)
        assertEquals(end.cardZ, settled.cardZ, 1e-5f)
        assertEquals(end.recordY, settled.recordY, 1e-6f)
        // Before anything has begun, nothing is on the table.
        val before = at(0.1f)
        assertTrue(!before.sleeveSeen && !before.recordSeen && !before.cardSeen && !before.seated && !before.motor)
    }

    @Test
    fun `nothing jumps - whatever is in sight moves a little at a time`() {
        var last = at(0f)
        for (t in steps.drop(1)) {
            val now = at(t)
            if (now.sleeveSeen && last.sleeveSeen) {
                assertTrue("the sleeve at $t", hypot(now.sleeveX - last.sleeveX, now.sleeveZ - last.sleeveZ) < 0.012f && abs(now.sleeveY - last.sleeveY) < 0.004f)
                assertTrue(abs(now.sleeveYaw - last.sleeveYaw) < 0.4f)
            }
            if (now.recordSeen && last.recordSeen) {
                assertTrue("the record at $t", hypot(now.recordX - last.recordX, now.recordZ - last.recordZ) < 0.012f && abs(now.recordY - last.recordY) < 0.008f)
            }
            if (now.cardSeen && last.cardSeen) {
                assertTrue("the card at $t", hypot(now.cardX - last.cardX, now.cardZ - last.cardZ) < 0.012f && abs(now.cardY - last.cardY) < 0.004f)
                assertTrue(abs(now.cardYaw - last.cardYaw) < 0.4f)
            }
            last = now
        }
    }

    @Test
    fun `a record is drawn out of a sleeve that is lying on the table, and stays under its cover until it is out`() {
        // The sleeve is down, exactly, before the record moves.
        val down = at(PutOn.SLIDE_FROM)
        assertEquals(PutOn.SLEEVE_Z, down.sleeveZ, 1e-6f)
        assertEquals(PutOn.SLEEVE_Y, down.sleeveY, 1e-6f)
        assertEquals(PutOn.SLEEVE_YAW, down.sleeveYaw, 1e-5f)
        // It begins in the middle of the sleeve.
        assertEquals(0f, along(down), 1e-5f)
        var out = false
        for (t in steps) {
            val p = at(t)
            if (!p.recordSeen) continue
            if (along(p) < PutOn.CLEAR_M - 0.0005f && !out) {
                // Any of it still under the cover: all of it is lower than the cover's face.
                assertTrue("through the cover at $t", p.recordY + VinylDimens.LP_H <= VinylDimens.SLEEVE_T)
            } else out = true
        }
        assertTrue(out)
        // It goes out of the mouth and not across it: sideways of the way out, it has not moved.
        val half = at(PutOn.SLIDE_FROM + PutOn.SLIDE_S / 2)
        val across = -(half.recordX - PutOn.SLEEVE_MIDDLE_X) * PutOn.MOUTH_Z + (half.recordZ - PutOn.SLEEVE_MIDDLE_Z) * PutOn.MOUTH_X
        assertEquals(0f, across, 1e-5f)
        assertTrue(along(half) > 0.1f)
    }

    @Test
    fun `it is carried over the arm and the spindle and never through them, and comes straight down onto the mat`() {
        val stylus = FloatArray(2)
        VinylArm.stylusAt(VinylDimens.REST_RADIUS, stylus)
        val spindleTop = VinylDimens.TOP_Y + 0.0373f
        // The arm on its rest with its head up: the top of the tube, and a centimetre for the lift.
        val armTop = VinylDimens.PIVOT_Y + 0.017f + 0.012f
        var cameDown = false
        for (t in steps) {
            val p = at(t)
            if (!p.recordSeen) continue
            val fromSpindle = hypot(p.recordX - VinylDimens.SPINDLE_X, p.recordZ - VinylDimens.SPINDLE_Z)
            val centred = fromSpindle < 0.0005f
            // Over any of the deck, it is no lower than the mat.
            val overDeck = abs(p.recordX) < VinylDimens.DECK_W / 2 + VinylDimens.LP_R && abs(p.recordZ) < VinylDimens.DECK_D / 2 + VinylDimens.LP_R
            if (overDeck) assertTrue("under the mat at $t", p.recordY >= VinylDimens.RECORD_Y - 1e-5f)
            // With the spindle under it and not in its hole, it is above the spindle.
            if (!centred && fromSpindle < VinylDimens.LP_R) {
                assertTrue("on the spindle, ${fromSpindle * 1000}mm off, at $t", p.recordY >= spindleTop + 0.004f)
            }
            // Over the arm — anywhere along it from behind the pivot to past the head — it is above the arm.
            if (!centred) {
                var s = -0.3f
                while (s <= 1.08f) {
                    val ax = VinylDimens.PIVOT_X + (stylus[0] - VinylDimens.PIVOT_X) * s
                    val az = VinylDimens.PIVOT_Z + (stylus[1] - VinylDimens.PIVOT_Z) * s
                    if (hypot(p.recordX - ax, p.recordZ - az) < VinylDimens.LP_R + 0.012f) {
                        assertTrue("through the arm at $t", p.recordY >= armTop + 0.004f)
                    }
                    s += 0.04f
                }
            }
            if (p.recordY < spindleTop && overDeck && t > PutOn.CARRY_FROM) {
                cameDown = true
                assertTrue("came down ${fromSpindle * 1000}mm off the spindle", centred)
            }
        }
        assertTrue(cameDown)
        // On its rest, the arm is clear of a record that is on the spindle: that is why it may be lifted early.
        assertTrue(hypot(stylus[0] - VinylDimens.SPINDLE_X, stylus[1] - VinylDimens.SPINDLE_Z) > VinylDimens.LP_R + 0.015f)
    }

    @Test
    fun `the card comes out after the record and from under the cover, and is laid on top of it`() {
        // The record has left the mouth, and the table, before the card is in it.
        val then = at(PutOn.CARD_FROM)
        assertTrue("the record is ${along(then)} out and ${then.recordY} up", along(then) >= PutOn.CLEAR_M - 0.002f && then.recordY > 0.03f)
        for (t in steps) {
            val p = at(t)
            if (!p.cardSeen) continue
            if (t < PutOn.CARD_FROM + PutOn.CARD_OUT_S) {
                // Being drawn out: its upper face is under the cover's.
                assertTrue("through the cover at $t", p.cardY + VinylDimens.SLEEVE_T / 2 < PutOn.SLEEVE_Y + VinylDimens.SLEEVE_T / 2)
            }
            if (t > PutOn.CARD_FROM + PutOn.CARD_OUT_S + PutOn.CARD_LAY_S * 0.5f) {
                // Coming back over the sleeve: above it, never through it.
                assertTrue("into the sleeve at $t", p.cardY >= PutOn.CARD_Y - 1e-5f)
            }
        }
    }

    @Test
    fun `things happen in the order a hand does them, and the needle lands on a record that is up to speed`() {
        assertTrue(PutOn.SLEEVE_FROM + PutOn.SLEEVE_S <= PutOn.SLIDE_FROM)
        assertTrue(PutOn.SLIDE_FROM + PutOn.SLIDE_S <= PutOn.CARRY_FROM)
        assertTrue(PutOn.CARD_FROM > PutOn.CARRY_FROM)
        assertEquals(PutOn.CARRY_FROM + PutOn.CARRY_S, PutOn.SEATED_AT, 1e-6f)
        // The arm is lifted as the record comes down, and swings only once it is on the mat.
        val lead = VinylArm.yawDegrees(VinylDimens.GROOVE_OUT)
        val move = Needle.move(Needle.REST_YAW, Needle.UP_DEG, lead, quick = false)
        assertTrue(PutOn.ARM_AT < PutOn.SEATED_AT)
        val liftedRest = Needle.move(Needle.REST_YAW, 0f, lead, quick = false)
        assertTrue(PutOn.ARM_AT + liftedRest.liftS >= PutOn.SEATED_AT)
        // The motor starts when the record is down, and has it at speed by the time the needle is.
        val spin = PlatterSpin()
        var t = PutOn.SEATED_AT
        val lands = PutOn.ARM_AT + move.landsAt
        while (t < lands) {
            spin.step(1f / 120f, on = true)
            t += 1f / 120f
        }
        assertTrue("the platter is at ${spin.speed} when the needle lands", spin.speed > 0.95f)
        // From the touch to the music is a few seconds, and not more.
        assertTrue("the needle lands at $lands", lands > 2.4f && lands < 3.6f)
    }

    // ---- The arm -------------------------------------------------------------------------------------

    @Test
    fun `a needle is never dragged - it does not turn until it is up, nor come down until it is there`() {
        val lead = VinylArm.yawDegrees(VinylDimens.GROOVE_OUT)
        val inner = VinylArm.yawDegrees(VinylDimens.GROOVE_IN)
        val mid = VinylArm.yawDegrees(0.10f)
        for (quick in listOf(false, true)) {
            for ((from, lift, to) in listOf(
                Triple(Needle.REST_YAW, Needle.UP_DEG, lead), Triple(lead, 0f, inner), Triple(inner, 0f, lead),
                Triple(mid, 0f, mid + 1f), Triple(mid, 1.4f, lead)
            )) {
                val move = Needle.move(from, lift, to, quick)
                var t = 0f
                var last = move.yaw(0f)
                assertEquals(from, move.yaw(0f), 1e-5f)
                assertEquals(lift, move.lift(0f), 1e-4f)
                while (t <= move.landsAt + 0.2f) {
                    val yaw = move.yaw(t)
                    val up = move.lift(t)
                    // Anywhere between where it was and where it is going, the head is all the way up.
                    if (abs(yaw - from) > 0.01f && abs(yaw - to) > 0.01f) {
                        assertTrue("dragged at $t (yaw $yaw, lift $up)", up >= Needle.UP_DEG - 1e-3f)
                    }
                    assertTrue(up >= -1e-5f && up <= Needle.UP_DEG + 1e-5f)
                    assertTrue("turned back at $t", (yaw - last) * (to - from) >= -1e-5f)
                    last = yaw
                    t += 1f / 240f
                }
                assertEquals(to, move.yaw(move.landsAt), 1e-4f)
                assertEquals(0f, move.lift(move.landsAt), 1e-4f)
                // It comes down at the end and not before: half way through the lowering it is still in the air.
                assertTrue(move.lift(move.landsAt - move.lowerS / 2) > Needle.UP_DEG * 0.3f)
            }
        }
    }

    @Test
    fun `a hop between tracks is quick, a record put on is not hurried, and the arm goes home with its head up`() {
        val lead = VinylArm.yawDegrees(VinylDimens.GROOVE_OUT)
        val inner = VinylArm.yawDegrees(VinylDimens.GROOVE_IN)
        val hop = Needle.move(lead, 0f, inner, quick = true)
        val longHop = Needle.move(inner, 0f, lead, quick = true)
        val small = Needle.move(lead, 0f, lead + 2f, quick = true)
        assertTrue("a hop takes ${hop.landsAt}s", hop.landsAt <= 0.55f && longHop.landsAt <= 0.55f)
        // But it is still an arm being lifted and put down: not so quick that it is a picture changing.
        assertTrue(hop.landsAt >= 0.4f && hop.liftS > 0.08f && hop.lowerS > 0.12f)
        assertTrue(small.landsAt < hop.landsAt)
        val put = Needle.move(Needle.REST_YAW, Needle.UP_DEG, lead, quick = false)
        assertTrue("putting it down takes ${put.landsAt}s", put.landsAt in 0.7f..1.3f)
        assertEquals(0f, put.liftS, 0f)                       // already up, on its rest
        // Already where it is going and down: there is nothing to do.
        assertEquals(0f, Needle.move(lead, 0f, lead, quick = true).landsAt, 0f)
        // Over it with its head up: it is only lowered, from where it is.
        val lower = Needle.move(lead, Needle.UP_DEG, lead, quick = false)
        assertEquals(Needle.LOWER_S, lower.landsAt, 1e-5f)
        assertEquals(Needle.UP_DEG, lower.lift(0f), 1e-5f)
        assertEquals(0f, lower.lift(lower.landsAt), 1e-5f)
        // Home: lifted, swung to its rest, and left up.
        val home = Needle.move(inner, 0f, Needle.REST_YAW, quick = false, down = false)
        assertEquals(Needle.REST_YAW, home.yaw(home.landsAt), 1e-4f)
        assertEquals(Needle.UP_DEG, home.lift(home.landsAt), 1e-5f)
        assertEquals(Needle.UP_DEG, home.lift(home.landsAt + 5f), 1e-5f)
        assertTrue(!home.endsDown && put.endsDown)
        // The rest is off the record, and a hop is further than following: a track's gap is not one.
        assertTrue(VinylArm.radiusAt(Needle.REST_YAW) > VinylDimens.LP_R)
        assertTrue(Needle.HOP_DEG > 0.2f && Needle.HOP_DEG < 1f)
    }

    // ---- The record turned over ------------------------------------------------------------------------

    @Test
    fun `a record is turned over clear of the mat and the spindle, and comes down on its other side`() {
        val pose = TurnOver.Pose()
        TurnOver.at(0f, pose)
        assertTrue(pose.seated && pose.lift == 0f && pose.turned == 0f)
        TurnOver.at(TurnOver.TOTAL_S, pose)
        assertTrue(pose.seated)
        assertEquals(0f, pose.lift, 1e-6f)
        assertEquals(1f, pose.turned, 1e-6f)
        // How far the spindle stands above the mat a record lies on.
        val spindle = 0.0373f - (VinylDimens.PLATTER_H + VinylDimens.MAT_H)
        var t = 0f
        var lastLift = 0f
        var lastTurned = 0f
        var edgeOn = false
        while (t <= TurnOver.TOTAL_S) {
            TurnOver.at(t, pose)
            val angle = Math.PI * pose.turned
            // Half of it swings down as the other half swings up: the half coming down stays off the mat.
            val swing = VinylDimens.LP_R * Math.sin(angle).toFloat()
            // (Lying flat, at either end, it is on the mat and that is where it belongs.)
            if (swing > 0.0005f) assertTrue("into the mat at $t: up ${pose.lift}, swung ${swing}", pose.lift - swing >= 0.004f)
            // Edge on, its edge is over the spindle, and above it.
            if (VinylDimens.LP_R * kotlin.math.abs(Math.cos(angle)).toFloat() < 0.012f) {
                edgeOn = true
                assertTrue("on the spindle at $t", pose.lift - VinylDimens.LP_R >= spindle + 0.004f)
            }
            // It goes over once, and nothing about it jumps.
            assertTrue("turned back at $t", pose.turned >= lastTurned)
            assertTrue("jumped at $t", abs(pose.lift - lastLift) < 0.006f && pose.turned - lastTurned < 0.012f)
            if (t > 0f && t < TurnOver.TOTAL_S - 1e-3f) assertTrue(!pose.seated)
            lastLift = pose.lift
            lastTurned = pose.turned
            t += 1f / 240f
        }
        assertTrue(edgeOn)
        // About a second: long enough to be a hand turning a record, short enough to wait through.
        assertTrue(TurnOver.TOTAL_S in 0.9f..1.3f)
        // On its rest the arm is further from the spindle than a record reaches, whichever way up it is going.
        assertTrue(VinylDimens.REST_RADIUS > VinylDimens.LP_R + 0.02f)
        // Left to itself, it is turned soon after the side ends - but not on the instant.
        assertTrue(TurnOver.UNASKED_AFTER_S in 0.3f..1.2f)
    }

    // ---- The motor --------------------------------------------------------------------------------------

    @Test
    fun `a platter comes up to speed in under a second and runs down, and is never past either`() {
        val spin = PlatterSpin()
        var t = 0f
        var last = 0f
        while (spin.speed < 1f && t < 3f) {
            spin.step(1f / 120f, on = true)
            assertTrue(spin.speed >= last && spin.speed <= 1f)
            last = spin.speed
            t += 1f / 120f
        }
        assertTrue("at speed after ${t}s", t > 0.4f && t < 1.4f)
        assertEquals(1f, spin.speed, 0f)
        t = 0f
        while (spin.speed > 0f && t < 4f) {
            spin.step(1f / 120f, on = false)
            assertTrue(spin.speed <= last && spin.speed >= 0f)
            last = spin.speed
            t += 1f / 120f
        }
        assertTrue("stopped after ${t}s", t > 0.6f && t < 2.2f)
        assertEquals(0f, spin.speed, 0f)
        // A long frame is a long frame, not a jump past the end.
        spin.step(5f, on = true)
        assertEquals(1f, spin.speed, 0f)
        spin.set(false)
        assertEquals(0f, spin.speed, 0f)
    }

    // ---- The sound ----------------------------------------------------------------------------------------

    @Test
    fun `the needle's sound does not clip, does not click at either end, and is over in a second`() {
        for (rate in listOf(44_100, 48_000)) {
            val pcm = NeedleSound.render(rate)
            assertEquals((rate * NeedleSound.SECONDS).toInt(), pcm.size)
            val peak = pcm.maxOf { abs(it.toInt()) }
            // Exactly as loud as it is meant to be, which is well under the music it gives way to.
            assertEquals(NeedleSound.PEAK * Short.MAX_VALUE, peak.toFloat(), 2f)
            assertTrue(NeedleSound.PEAK <= 0.35f)
            // It starts from nothing and ends on nothing.
            assertTrue("starts at ${pcm[0]}", abs(pcm[0].toInt()) < peak * 0.03f)
            assertEquals(0, pcm[pcm.size - 1].toInt())
            // It is a thump and then a tail: most of it is in the first tenth of a second.
            fun energy(from: Float, to: Float): Double {
                var e = 0.0
                for (i in (from * rate).toInt() until (to * rate).toInt()) e += pcm[i].toDouble() * pcm[i]
                return e
            }
            assertTrue(energy(0f, 0.1f) > energy(0.1f, NeedleSound.SECONDS) * 3)
            // But the tail is there: a lead-in groove is not silence.
            assertTrue(energy(0.3f, 0.7f) > 0.0)
            // The loudest moment is the landing itself.
            assertTrue(pcm.indices.maxByOrNull { abs(pcm[it].toInt()) }!! < rate * 0.03f)
        }
        // The same every time, and a different seed is a different crackle on the same thump.
        assertArrayEquals(NeedleSound.render(48_000), NeedleSound.render(48_000))
        assertTrue(!NeedleSound.render(48_000, seed = 1).contentEquals(NeedleSound.render(48_000, seed = 2)))
    }
}
