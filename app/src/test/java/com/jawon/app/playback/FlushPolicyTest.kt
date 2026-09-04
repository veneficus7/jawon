package com.jawon.app.playback

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * When an audio processor should throw its state away.
 *
 * Small, and the reason it is a separate file at all: the decision it encodes was wrong for two
 * releases and the symptom was a click nobody could point at. The processors themselves cannot
 * be unit-tested — they are `BaseAudioProcessor` subclasses that need a real pipeline — so the
 * judgement is pulled out here where it can be.
 */
class FlushPolicyTest {

    // Period ids are opaque objects from the Timeline; identity is all that matters.
    private val trackOne = Any()
    private val trackTwo = Any()

    @Test
    fun `a seek within the same track clears the state`() {
        // The case the original code was written for, and the one it got right. Whatever the
        // filter is ringing with belongs to a moment the listener never heard, and letting it
        // decay into the new position is an audible click.
        assertTrue(FlushPolicy.shouldResetState(trackOne, trackOne))
    }

    @Test
    fun `a track transition keeps the state`() {
        // The bug. Media3 flushes on every media-item transition, not only on a seek — verified
        // on a device, two flushes about 600ms ahead of a natural un-seeked advance. Zeroing the
        // filter's integrators while the knob stays put is a step in the output: a click at
        // every track boundary of an album whenever the filter is off centre.
        assertFalse(FlushPolicy.shouldResetState(trackOne, trackTwo))
    }

    @Test
    fun `the first flush of a session clears the state`() {
        // Nothing has played, so there is no tail worth keeping and a clean start is free.
        assertTrue(FlushPolicy.shouldResetState(null, trackOne))
    }

    @Test
    fun `an unknown period clears the state`() {
        // StreamMetadata.DEFAULT carries a null period, and Media3 does not always know which
        // one it is flushing to. Without an identity there is no way to tell a seek from a
        // transition, and the conservative answer is the one that cannot leave stale audio
        // ringing over unrelated music.
        assertTrue(FlushPolicy.shouldResetState(trackOne, null))
        assertTrue(FlushPolicy.shouldResetState(null, null))
    }

    @Test
    fun `it is identity, not equality of some value`() {
        // Period uids are opaque objects. Two distinct ones must never be conflated just
        // because they happen to be the same kind of thing.
        assertFalse(FlushPolicy.shouldResetState(Any(), Any()))
    }

    @Test
    fun `walking an album through does not clear once`() {
        // The shape of the real complaint: an album played straight through, one flush per
        // boundary, filter engaged. Every one of these used to reset.
        val album = listOf(Any(), Any(), Any(), Any())
        var last: Any? = album.first()
        val resets = album.drop(1).count { period ->
            val reset = FlushPolicy.shouldResetState(last, period)
            last = period
            reset
        }
        assertTrue("no boundary in an album should clear the filter, got $resets", resets == 0)
    }

    @Test
    fun `scrubbing the disc still clears every time`() {
        // The scratch gesture seeks repeatedly inside one track, and every one of those is a
        // genuine discontinuity. This must not have been traded away for the fix above.
        var last: Any? = trackOne
        repeat(20) {
            assertTrue(FlushPolicy.shouldResetState(last, trackOne))
            last = trackOne
        }
    }
}
