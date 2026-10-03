package com.jawon.app.ui.showroom

import kotlin.math.floor

/**
 * A slow wander, for an exhibit floating over its plinth: smooth value noise over time — random keys
 * a [periodS] apart, eased between — so it drifts and never repeats, which a sum of sines does and
 * which is the tell of a rendered float (`docs/realism.md` §3). −1…1.
 *
 * Written for the pocket player resting on its carriage's table; that room is a face square on to
 * you now, and this stayed with the showroom, which floats both its exhibits on it.
 */
class Wander(seed: Int, private val periodS: Float) {
    private val keys = FloatArray(KEYS) { i -> hash(seed * 7919 + i) * 2f - 1f }

    fun at(timeS: Float): Float {
        val x = timeS / periodS
        val i = floor(x).toInt()
        val f = x - i
        val a = keys[Math.floorMod(i, KEYS)]
        val b = keys[Math.floorMod(i + 1, KEYS)]
        val s = f * f * (3f - 2f * f)
        return a + (b - a) * s
    }

    private fun hash(n: Int): Float {
        var h = n * 374761393 + 668265263
        h = (h xor (h ushr 13)) * 1274126177
        h = h xor (h ushr 16)
        return (h and 0xFFFFFF) / 16777216f
    }

    private companion object {
        const val KEYS = 257
    }
}
