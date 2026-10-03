package com.jawon.app.ui.pocketscene

/**
 * **What the line is made of**, ahead of the train: the tunnels it runs through, laid down as the
 * train goes from a seed, so a ride is the same line every time it is replayed and different from
 * the next room's (`docs/formats.md` §7, step 5a, phase 2).
 *
 * Distances are metres along the line, the train's own [TrainMotion.distance]. Nothing here moves
 * or draws: the train asks where the tunnels are, and clears the way for a station.
 */
class NightLine(seed: Long = 11L) {

    /** A stretch of the line, from [from] to [to] metres. */
    data class Span(val from: Double, val to: Double) {
        operator fun contains(x: Double): Boolean = x >= from && x <= to
    }

    private val random = java.util.Random(seed)
    private val tunnels = ArrayDeque<Span>()
    private var laidTo = 0.0
    private var first = true

    /** Lays the line down as far as [until], if it has not been already. */
    fun layTo(until: Double) {
        while (laidTo < until) {
            val gap = if (first) between(FIRST_MIN_M, FIRST_MAX_M) else {
                val u = random.nextDouble().coerceAtLeast(1e-6)
                (-kotlin.math.ln(u) * MEAN_GAP_M).coerceIn(MIN_GAP_M, MAX_GAP_M)
            }
            first = false
            val from = laidTo + gap
            val to = from + between(MIN_LENGTH_M, MAX_LENGTH_M)
            tunnels.addLast(Span(from, to))
            laidTo = to
        }
    }

    /** The tunnel [x] is in, or null. */
    fun tunnelAt(x: Double): Span? = tunnels.firstOrNull { x in it }

    /** The nearest tunnel with any part within [range] of [x], or null. */
    fun tunnelNear(x: Double, range: Double): Span? =
        tunnels.firstOrNull { it.to >= x - range && it.from <= x + range }

    /**
     * Takes away every tunnel that starts beyond [after] and reaches into [from]…[to] — to clear a
     * station's platform. Only ever ones not yet reached: a tunnel the train is in stays.
     */
    fun clear(after: Double, from: Double, to: Double) {
        tunnels.removeAll { it.from > after && it.to >= from && it.from <= to }
    }

    /** Forgets tunnels wholly more than [margin] behind [x]. */
    fun forgetBehind(x: Double, margin: Double = 200.0) {
        while (tunnels.isNotEmpty() && tunnels.first().to < x - margin) tunnels.removeFirst()
    }

    private fun between(lo: Double, hi: Double) = lo + (hi - lo) * random.nextDouble()

    companion object {
        /** A line laid by hand — these tunnels and no others. For tests. */
        fun of(vararg spans: Span): NightLine = NightLine().apply {
            spans.sortedBy { it.from }.forEach { tunnels.addLast(it) }
            laidTo = Double.MAX_VALUE
        }

        /** The first a little way down the line, so the room does not open into one. */
        const val FIRST_MIN_M = 700.0
        const val FIRST_MAX_M = 1_500.0

        /** Between tunnels: a couple of kilometres on average — a few minutes at speed. */
        const val MEAN_GAP_M = 2_400.0
        const val MIN_GAP_M = 900.0
        const val MAX_GAP_M = 6_000.0

        /** A tunnel's length: a quarter of a minute to three quarters, at speed. */
        const val MIN_LENGTH_M = 250.0
        const val MAX_LENGTH_M = 700.0
    }
}
