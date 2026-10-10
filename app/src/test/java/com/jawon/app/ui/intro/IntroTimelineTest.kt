package com.jawon.app.ui.intro

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import kotlin.math.abs
import kotlin.math.hypot

/**
 * The opening's timing — what can be said about it without a screen.
 *
 * The look was judged from captures, frame by frame; these hold the rules the captures found:
 * nothing jumps, a tap reaches the way out from any moment, the hole is open onto a room that is
 * already drawing, and the card's glow picks up where the system's splash left it.
 */
class IntroTimelineTest {

    private val everyMs = generateSequence(0f) { it + 1f }.takeWhile { it <= IntroTimeline.TOTAL_MS }.toList()

    // ---- One sequence, of a length someone chose ---------------------------------------------

    @Test
    fun `the whole opening is long enough to be an opening and short enough to sit through`() {
        assertTrue(IntroTimeline.TOTAL_MS in 4_000f..6_000f)
    }

    @Test
    fun `the short opening is under two seconds`() {
        assertTrue(IntroTimeline.QUICK_EXIT_AT + IntroTimeline.EXIT_MS < 2_000f)
    }

    @Test
    fun `the beats arrive in the order the card reads - mark, light, name, rule, credit, out`() {
        val lastLetterDone = IntroTimeline.LETTERS_START +
            (IntroTimeline.LETTERS - 1) * IntroTimeline.LETTER_STAGGER + IntroTimeline.LETTER_MS
        assertTrue(IntroTimeline.RISE_START < IntroTimeline.LETTERS_START)
        assertTrue(IntroTimeline.LAP_START < IntroTimeline.LETTERS_START)
        // The rule draws under a name that is there, and the credit strikes under a finished rule.
        assertTrue(IntroTimeline.RULE_START >= IntroTimeline.LETTERS_START + IntroTimeline.LETTER_MS)
        assertTrue(IntroTimeline.BYLINE_START >= IntroTimeline.RULE_START + IntroTimeline.RULE_MS)
        assertTrue(lastLetterDone < IntroTimeline.BYLINE_START)
        // And the finished card is held, lit, before it goes.
        assertEquals(1f, IntroTimeline.byline(IntroTimeline.EXIT_AT - 600f), 0f)
    }

    // ---- Nothing jumps -----------------------------------------------------------------------

    @Test
    fun `the mark's move is one unbroken motion that never passes its resting place`() {
        var last = 0f
        for (t in everyMs) {
            val rise = IntroTimeline.rise(t)
            assertTrue("rise went back at $t", rise >= last - 1e-6f)
            assertTrue("rise overshot at $t", rise <= 1f)
            assertTrue("rise jumped at $t", rise - last < 0.01f)
            last = rise
        }
        assertEquals(0f, IntroTimeline.rise(IntroTimeline.RISE_START), 0f)
        assertTrue(IntroTimeline.rise(IntroTimeline.RISE_START + IntroTimeline.RISE_MS) > 0.99f)
    }

    @Test
    fun `the name, the rule and the light move without a step`() {
        for (i in 0 until IntroTimeline.LETTERS) noStep("letter $i", 0.02f) { IntroTimeline.letter(i, it) }
        noStep("rule", 0.02f, IntroTimeline::rule)
        noStep("lap head", 0.02f, IntroTimeline::lapHead)
        noStep("lap tail", 0.02f, IntroTimeline::lapTail)
        noStep("hole", 0.03f, IntroTimeline::holePulse)
        noStep("bloom", 0.03f, IntroTimeline::bloom)
    }

    @Test
    fun `the letters come in one after another, left to right`() {
        for (t in everyMs) {
            for (i in 1 until IntroTimeline.LETTERS) {
                assertTrue(IntroTimeline.letter(i, t) <= IntroTimeline.letter(i - 1, t))
            }
        }
        assertEquals(1f, IntroTimeline.letter(IntroTimeline.LETTERS - 1, IntroTimeline.RULE_START + IntroTimeline.RULE_MS), 0f)
    }

    @Test
    fun `the flicker and the strike are light going out, never past full`() {
        for (t in everyMs) {
            assertTrue(IntroTimeline.flicker(t) in 0f..1f)
            assertTrue(IntroTimeline.byline(t) in 0f..1f)
        }
        // The tube is whole at the instant it takes over, and holds after it has caught.
        assertEquals(1f, IntroTimeline.flicker(0f), 0f)
        assertEquals(1f, IntroTimeline.flicker(400f), 0f)
        // Dark until its turn.
        assertEquals(0f, IntroTimeline.byline(IntroTimeline.BYLINE_START), 0f)
    }

    // ---- The light's lap ---------------------------------------------------------------------

    @Test
    fun `the light has a head ahead of its tail, and both end in the hole`() {
        for (t in everyMs) assertTrue(IntroTimeline.lapHead(t) >= IntroTimeline.lapTail(t))
        assertEquals(0f, IntroTimeline.lapHead(IntroTimeline.LAP_START), 0f)
        assertEquals(1f, IntroTimeline.lapHead(IntroTimeline.LAP_END), 0f)
        val tailHome = IntroTimeline.LAP_END + IntroTimeline.LAP_MS * IntroTimeline.LAP_TAIL
        assertEquals(1f, IntroTimeline.lapTail(tailHome), 1e-4f)
        // The hole answers when the light lands, not before, and settles.
        assertEquals(1f, IntroTimeline.holePulse(IntroTimeline.LAP_END), 0f)
        assertTrue(IntroTimeline.holePulse(IntroTimeline.LAP_END + 100f) > 1.1f)
        assertEquals(1f, IntroTimeline.holePulse(IntroTimeline.LAP_END + 1500f), 0.01f)
    }

    @Test
    fun `the mark's line runs along the rim and then the J, with only the one small gap between them`() {
        val p = FloatArray(2)
        val q = FloatArray(2)
        val steps = 2000
        var gaps = 0
        MarkPath.point(0f, q)
        for (i in 1..steps) {
            val s = i / steps.toFloat()
            MarkPath.point(s, p)
            val fromCentre = hypot(p[0] - MarkPath.CENTRE, p[1] - MarkPath.CENTRE)
            if (!MarkPath.onJ(s)) {
                assertEquals("on the rim at $s", MarkPath.RIM_RADIUS, fromCentre, 0.01f)
            } else {
                // The stem stands right of the hole; the hook turns round it at its own radius.
                assertTrue("on the J at $s", abs(p[0] - MarkPath.STEM_X) < 0.01f || abs(fromCentre - MarkPath.HOOK_RADIUS) < 0.01f)
            }
            val step = hypot(p[0] - q[0], p[1] - q[1])
            if (step > MarkPath.LENGTH / steps * 1.5f) gaps++
            q[0] = p[0]; q[1] = p[1]
        }
        // Rim's end to the stem's top: the splash's artwork has the same gap.
        assertEquals(1, gaps)
        // It ends where the hook does, under and left of the hole.
        MarkPath.point(1f, p)
        assertEquals(46.19f, p[0], 0.05f)
        assertEquals(60.56f, p[1], 0.05f)
    }

    // ---- The way out -------------------------------------------------------------------------

    @Test
    fun `a tap goes straight to the way out from any moment, and nothing built so far moves`() {
        for (tap in listOf(0f, 137f, 900f, 1500f, 2400f, 3100f)) {
            // The card stops where it was...
            assertEquals(tap, IntroTimeline.beat(tap + 400f, exitAt = tap), 0f)
            // ...and the way out starts from its beginning, at once.
            assertEquals(0f, IntroTimeline.exit(tap, exitAt = tap), 0f)
            assertEquals(1f, IntroTimeline.exit(tap + IntroTimeline.EXIT_MS, exitAt = tap), 0f)
        }
    }

    @Test
    fun `the way out starts at rest, ends with the hole past the corner, and only ever grows after its breath`() {
        val end = 87f
        assertEquals(1f, IntroTimeline.zoom(0f, end), 0f)
        assertEquals(end, IntroTimeline.zoom(1f, end), 0.01f)
        var last = 0f
        var lowest = 1f
        for (i in 0..1000) {
            val x = i / 1000f
            val zoom = IntroTimeline.zoom(x, end)
            lowest = minOf(lowest, zoom)
            if (x > 0.2f) assertTrue("zoom fell back at $x", zoom >= last)
            last = zoom
        }
        // It gathers itself: a little smaller first, and only a little.
        assertTrue(lowest < 1f && lowest > 0.9f)
    }

    @Test
    fun `the hole does not open before the room under it is drawing again`() {
        // Untouched, the room is uncovered a lead ahead of the way out.
        assertTrue(IntroTimeline.UNCOVER_LEAD_MS >= 200f)
        // Tapped, it is uncovered as the way out begins — and the hole stays shut a few frames more.
        val shutFor = (0..1000).map { it / 1000f }.takeWhile { IntroTimeline.open(it) == 0f }.last()
        assertTrue(shutFor * IntroTimeline.EXIT_MS >= 100f)
        assertEquals(1f, IntroTimeline.open(0.5f), 0f)
    }

    @Test
    fun `the words are gone before the hole opens, and the strokes before the hole fills the screen`() {
        val opens = (0..1000).map { it / 1000f }.first { IntroTimeline.open(it) > 0.5f }
        assertTrue(IntroTimeline.wordsOut(opens) < 0.05f)
        assertEquals(1f, IntroTimeline.strokesOut(0.2f), 0f)
        assertEquals(0f, IntroTimeline.strokesOut(0.8f), 0f)
        assertEquals(0f, IntroTimeline.edgeOut(1f), 0f)
    }

    // ---- Taking over from the system's splash ------------------------------------------------

    @Test
    fun `the glow the card picks up is the one the splash's own animator runs`() {
        // The animator, read as text: this is the pair of numbers that must not drift apart.
        val xml = File("src/main/res/animator/splash_glow_in.xml").readText()
        assertTrue(xml.contains("android:duration=\"${IntroTimeline.SPLASH_WRITE_MS.toInt()}\""))
        assertTrue(xml.contains("android:duration=\"${IntroTimeline.SPLASH_BREATH_MS.toInt()}\""))
        assertTrue(xml.contains("android:valueTo=\"${IntroTimeline.SPLASH_BREATH_LOW}\""))
        assertTrue(xml.contains("accelerate_decelerate"))
        assertTrue(xml.contains("android:repeatMode=\"reverse\""))
    }

    @Test
    fun `the splash's glow breathes between full and its low, there and back`() {
        val write = IntroTimeline.SPLASH_WRITE_MS
        val breath = IntroTimeline.SPLASH_BREATH_MS
        assertEquals(1f, IntroTimeline.splashGlow(write), 0f)
        assertEquals(IntroTimeline.SPLASH_BREATH_LOW, IntroTimeline.splashGlow(write + breath), 1e-4f)
        assertEquals(1f, IntroTimeline.splashGlow(write + 2 * breath), 1e-4f)
        assertEquals(IntroTimeline.SPLASH_BREATH_LOW, IntroTimeline.splashGlow(write + 3 * breath), 1e-4f)
        // Halfway down and halfway back are the same brightness.
        assertEquals(
            IntroTimeline.splashGlow(write + breath * 0.5f),
            IntroTimeline.splashGlow(write + breath * 1.5f),
            1e-4f
        )
    }

    @Test
    fun `the card starts its glow where the splash left it and brings it up without a step`() {
        val from = 0.6f
        assertEquals(from, IntroTimeline.glowSettle(from, 0f), 0f)
        assertEquals(1f, IntroTimeline.glowSettle(from, 400f), 0f)
        noStep("glow settle", 0.01f) { IntroTimeline.glowSettle(from, it) }
    }

    private fun noStep(what: String, most: Float, value: (Float) -> Float) {
        var last = value(0f)
        for (t in everyMs) {
            val now = value(t)
            assertTrue("$what stepped by ${abs(now - last)} at $t", abs(now - last) <= most)
            last = now
        }
    }
}
