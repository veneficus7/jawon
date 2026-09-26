package com.jawon.app.ui.pocketscene

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.abs

/**
 * The wheel's click. What can go wrong with a 12ms sound is mostly what cannot be heard on a
 * laptop speaker: a click that clips, one that leaves the cone parked off-centre, or one that
 * ends on a step and adds a second click of its own.
 */
class ClickSoundTest {

    private val rate = 48_000
    private val pcm = ClickSound.render(rate)

    @Test
    fun `it is as long as it says`() {
        assertEquals((rate * ClickSound.LENGTH_MS / 1000f).toInt(), pcm.size)
    }

    @Test
    fun `it peaks where it should and never clips`() {
        val peak = pcm.maxOf { abs(it.toInt()) }
        assertEquals(ClickSound.PEAK * Short.MAX_VALUE, peak.toFloat(), 2f)
    }

    @Test
    fun `it is a click - the energy is at the front`() {
        val firstMs = pcm.take(rate / 1000).maxOf { abs(it.toInt()) }
        val lastMs = pcm.takeLast(rate / 1000).maxOf { abs(it.toInt()) }
        assertTrue("the first millisecond should carry the transient", firstMs > Short.MAX_VALUE * 0.3f)
        assertTrue("the last millisecond should be all but silent", lastMs < Short.MAX_VALUE * 0.02f)
    }

    @Test
    fun `it ends on silence, so the buffer's edge is not a second click`() {
        assertEquals(0, pcm.last().toInt())
    }

    @Test
    fun `it carries no DC`() {
        val mean = pcm.sumOf { it.toLong() }.toDouble() / pcm.size
        assertTrue("mean was $mean", abs(mean) < Short.MAX_VALUE * 0.01)
    }

    @Test
    fun `it renders at whatever rate the output runs`() {
        assertEquals((44_100 * ClickSound.LENGTH_MS / 1000f).toInt(), ClickSound.render(44_100).size)
    }
}
