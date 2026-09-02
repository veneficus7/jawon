package com.jawon.app.playback

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The bypass, which is the one piece of logic on the rack's face worth pinning down.
 *
 * Everything here is about the distinction between where a fader is sitting and what the audio
 * is being given — the two numbers a channel holds. Getting that wrong is not a visual bug: it
 * either leaves an effect audible after you switched it out, or throws away a setting you were
 * in the middle of making.
 */
class RackStateTest {

    private fun rack() = RackState()

    @Test
    fun `everything starts in circuit`() {
        val r = rack()
        RackChannel.values().forEach { assertTrue(it.name, r.isEngaged(it)) }
    }

    @Test
    fun `a bypassed channel goes quiet but keeps its fader`() {
        val r = rack()
        r.setDrive(0.8f)
        assertEquals(0.8f, r.drivePosition, 1e-6f)

        r.setEngaged(RackChannel.DRIVE, false)

        // The audio hears nothing...
        assertEquals(0f, r.drivePosition, 1e-6f)
        // ...and the fader has not moved.
        assertEquals(0.8f, r.drive.value, 1e-6f)
    }

    @Test
    fun `slamming it back in returns to exactly where it was`() {
        // The whole reason target and effective are separate. A DJ sets the amount, takes it
        // out, and throws it back in on the beat — landing anywhere else is a different effect.
        val r = rack()
        r.setEchoMix(0.42f)
        r.setEngaged(RackChannel.ECHO, false)
        r.setEngaged(RackChannel.ECHO, true)
        assertEquals(0.42f, r.echoMixPosition, 1e-6f)
    }

    @Test
    fun `moving a fader while bypassed sets it up without being heard`() {
        val r = rack()
        r.setEngaged(RackChannel.FILTER, false)
        r.setFilter(-0.9f)

        assertEquals(0f, r.filterPosition, 1e-6f)   // still silent
        assertEquals(-0.9f, r.filter.value, 1e-6f)  // but armed and ready

        r.setEngaged(RackChannel.FILTER, true)
        assertEquals(-0.9f, r.filterPosition, 1e-6f)
    }

    @Test
    fun `the filter rests at centre and the drive at zero`() {
        // Their neutrals are different, so a single "off" value would be wrong for one of them.
        val r = rack()
        r.setFilter(0.7f)
        r.setDrive(0.7f)
        r.setEngaged(RackChannel.FILTER, false)
        r.setEngaged(RackChannel.DRIVE, false)

        assertEquals(RackState.CENTRE, r.filterPosition, 1e-6f)
        assertEquals(0f, r.drivePosition, 1e-6f)
    }

    @Test
    fun `echo time and feedback ignore the bypass`() {
        // They are shape, not presence. Resetting them on a bypass would discard a setting the
        // user is halfway through dialling in, and they cost nothing while the mix is at zero.
        val r = rack()
        r.setEchoTime(0.2f)
        r.setEchoFeedback(0.7f)
        r.setEngaged(RackChannel.ECHO, false)

        assertEquals(0.2f, r.echoTimePosition, 1e-6f)
        assertEquals(0.7f, r.echoFeedbackPosition, 1e-6f)
    }

    @Test
    fun `speed reaches the player through the bypass too`() {
        val r = rack()
        r.setSpeed(0.5f)
        assertEquals(0.5f, r.speedApplied.value, 1e-6f)

        r.setEngaged(RackChannel.SPEED, false)
        assertEquals(RackState.CENTRE, r.speedApplied.value, 1e-6f)
        assertEquals(0.5f, r.speed.value, 1e-6f)

        r.setEngaged(RackChannel.SPEED, true)
        assertEquals(0.5f, r.speedApplied.value, 1e-6f)
    }

    @Test
    fun `pitch is not a channel and is untouched by any bypass`() {
        // Pitch lives in the drawer: it commits on release and is set rather than played, so no
        // pad owns it. Nothing on the face should be able to move it.
        val r = rack()
        r.setPitch(0.6f)
        r.commitPitch()
        RackChannel.values().forEach { r.setEngaged(it, false) }
        assertEquals(0.6f, r.pitchApplied.value, 1e-6f)
    }

    @Test
    fun `toggling flips and reports`() {
        val r = rack()
        r.toggleEngaged(RackChannel.BASS)
        assertFalse(r.isEngaged(RackChannel.BASS))
        assertFalse(RackChannel.BASS in r.engaged.value)

        r.toggleEngaged(RackChannel.BASS)
        assertTrue(r.isEngaged(RackChannel.BASS))
    }

    @Test
    fun `bypassing one channel leaves the others alone`() {
        val r = rack()
        r.setFilter(-0.5f)
        r.setDrive(0.6f)
        r.setEchoMix(0.3f)

        r.setEngaged(RackChannel.DRIVE, false)

        assertEquals(-0.5f, r.filterPosition, 1e-6f)
        assertEquals(0.3f, r.echoMixPosition, 1e-6f)
        assertEquals(0f, r.drivePosition, 1e-6f)
    }

    @Test
    fun `setting the state it is already in changes nothing`() {
        // Guards the republish path against a pad being mashed: repeated taps in the same
        // direction must not keep handing the player new playback parameters.
        val r = rack()
        r.setSpeed(0.4f)
        r.setEngaged(RackChannel.SPEED, true)
        assertEquals(0.4f, r.speedApplied.value, 1e-6f)

        r.setEngaged(RackChannel.SPEED, false)
        val flow = r.speedApplied
        val before = flow.value
        r.setEngaged(RackChannel.SPEED, false)
        assertEquals(before, flow.value, 1e-6f)
    }

    @Test
    fun `values are clamped whether engaged or not`() {
        val r = rack()
        r.setFilter(-4f)
        assertEquals(-1f, r.filter.value, 1e-6f)

        r.setEngaged(RackChannel.DRIVE, false)
        r.setDrive(9f)
        r.setEngaged(RackChannel.DRIVE, true)
        assertEquals(1f, r.drivePosition, 1e-6f)
    }
}
