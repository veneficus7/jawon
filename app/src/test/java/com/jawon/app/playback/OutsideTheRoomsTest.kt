package com.jawon.app.playback

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The two rules about the world outside the rooms: who may reach the player, and what — if
 * anything — may start it without a touch on the phone.
 */
class OutsideTheRoomsTest {

    // ---- Who may connect ---------------------------------------------------------------------

    @Test
    fun `Jawon itself, a car, and what the phone trusts with media are let in`() {
        assertTrue(SessionGate.admits(ownApp = true, car = false, trusted = false))
        assertTrue(SessionGate.admits(ownApp = false, car = true, trusted = false))
        assertTrue(SessionGate.admits(ownApp = false, car = false, trusted = true))
    }

    @Test
    fun `an app that only knows the service's name is turned away`() {
        assertFalse(SessionGate.admits(ownApp = false, car = false, trusted = false))
    }

    // ---- Headphones coming back --------------------------------------------------------------

    private fun resumes(
        optedIn: Boolean = true,
        hasQueue: Boolean = true,
        playing: Boolean = false,
        droppedAtMs: Long? = 1_000L,
        nowMs: Long = 60_000L
    ) = HeadphoneResume.resumes(optedIn, hasQueue, playing, droppedAtMs, nowMs)

    @Test
    fun `headphones that dropped out and came back carry the record on`() {
        assertTrue(resumes())
    }

    @Test
    fun `never without the switch`() {
        assertFalse(resumes(optedIn = false))
    }

    @Test
    fun `a record paused by hand stays paused - only the drop-out is undone`() {
        assertFalse(resumes(droppedAtMs = null))
    }

    @Test
    fun `nothing loaded, or already playing, and connecting headphones does nothing`() {
        assertFalse(resumes(hasQueue = false))
        assertFalse(resumes(playing = true))
    }

    @Test
    fun `too long after the drop-out it no longer means carry on`() {
        assertTrue(resumes(droppedAtMs = 0L, nowMs = HeadphoneResume.WINDOW_MS))
        assertFalse(resumes(droppedAtMs = 0L, nowMs = HeadphoneResume.WINDOW_MS + 1))
    }

    @Test
    fun `things worn on the head are headphones, and a phone's own speaker is not`() {
        // Wired headset and headphones, Bluetooth A2DP, USB headset, hearing aid, BLE headset.
        for (type in listOf(3, 4, 8, 22, 23, 26)) assertTrue("type $type", HeadphoneResume.isHeadphones(type))
        // Earpiece 1, built-in speaker 2, HDMI 9, a dock 13, the telephony uplink 18.
        for (type in listOf(1, 2, 9, 13, 18)) assertFalse("type $type", HeadphoneResume.isHeadphones(type))
    }
}
