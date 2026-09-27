package com.jawon.app.ui.pocketscene

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The line the train runs on, and what it does to a stop: a train that stood in a tunnel, or ran a
 * platform into one, would be the room getting the world wrong.
 */
class NightLineTest {

    private fun tunnelsOf(line: NightLine, to: Double): List<NightLine.Span> {
        val found = mutableListOf<NightLine.Span>()
        var x = 0.0
        while (x < to) {
            line.tunnelAt(x)?.let { if (found.lastOrNull() != it) found += it }
            x += 5.0
        }
        return found
    }

    @Test
    fun `tunnels come a few minutes apart, never overlap, and are the same for the same seed`() {
        val line = NightLine(seed = 3L)
        line.layTo(40_000.0)
        val tunnels = tunnelsOf(line, 40_000.0)
        assertTrue("${tunnels.size} tunnels", tunnels.size in 8..25)
        assertTrue(tunnels.first().from >= NightLine.FIRST_MIN_M)
        tunnels.zipWithNext { a, b -> assertTrue("gap ${b.from - a.to}", b.from - a.to >= NightLine.MIN_GAP_M - 1e-6) }
        tunnels.forEach { assertTrue(it.to - it.from in NightLine.MIN_LENGTH_M..NightLine.MAX_LENGTH_M) }
        val again = NightLine(seed = 3L).also { it.layTo(40_000.0) }
        assertEquals(tunnels, tunnelsOf(again, 40_000.0))
    }

    @Test
    fun `clearing takes away only tunnels not yet reached`() {
        val line = NightLine.of(NightLine.Span(100.0, 400.0), NightLine.Span(900.0, 1_200.0))
        line.clear(after = 150.0, from = 150.0, to = 1_000.0)
        assertNotNull("the one the train is in stays", line.tunnelAt(200.0))
        assertNull(line.tunnelAt(1_000.0))
    }

    @Test
    fun `paused in a tunnel, it runs out of it and stands at a platform clear of the mouth`() {
        val tunnel = NightLine.Span(40.0, 600.0)
        val train = TrainMotion(line = NightLine.of(tunnel))
        repeat(2 * 60) { train.advance(1f / 60f) }
        while (train.distance < 200.0) train.advance(1f / 60f)
        assertTrue(train.tunnel > 0.99f)
        train.setRunning(false)
        var stoppedIn = false
        repeat(90 * 60) {
            train.advance(1f / 60f)
            if (train.stopped && train.distance < tunnel.to) stoppedIn = true
        }
        assertFalse("it stood in the tunnel", stoppedIn)
        assertTrue(train.stopped)
        assertEquals(0f, train.stationAhead, 1e-3f)
        assertTrue("platform ${train.distance}", train.distance >= tunnel.to + TrainMotion.PLATFORM_CLEAR_M - 1e-6)
        assertEquals(0f, train.tunnel, 0f)
    }

    @Test
    fun `a tunnel the stop would run into is taken off the line before it is reached`() {
        val train = TrainMotion(line = NightLine.of(NightLine.Span(120.0, 500.0)))
        repeat(2 * 60) { train.advance(1f / 60f) }
        // Some way short of the mouth, with the stop inside the tunnel's reach.
        assertTrue(train.distance < 100.0)
        train.setRunning(false)
        repeat(20 * 60) { train.advance(1f / 60f) }
        assertTrue(train.stopped)
        assertTrue(train.tunnelEntryAhead.isNaN())
        assertEquals(0f, train.tunnel, 0f)
    }

    @Test
    fun `going through, the carriage goes from night to tunnel and back, over a few metres at each mouth`() {
        val train = TrainMotion(line = NightLine.of(NightLine.Span(300.0, 700.0)))
        var sawNight = false
        var sawTunnel = false
        var between = 0
        while (train.distance < 900.0) {
            train.advance(1f / 120f)
            val k = train.tunnel
            if (k == 0f) sawNight = true
            if (k == 1f) sawTunnel = true
            if (k > 0f && k < 1f) between++
        }
        assertTrue(sawNight && sawTunnel)
        // A few metres at each mouth, at ~16m/s and 120fps: a handful of frames each, not one.
        assertTrue("$between frames between", between in 6..200)
    }

    @Test
    fun `through a tunnel, the window passes one of its lamps every fourteen metres`() {
        val train = TrainMotion(line = NightLine.of(NightLine.Span(200.0, 620.0)))
        while (train.distance < 800.0) train.advance(1f / 60f)
        assertEquals(30L, train.tunnelLampsPassed)
    }
}
