package com.jawon.app.ui.vinylscene

import com.jawon.app.ui.wallscene.CaseLayout
import com.jawon.app.ui.wallscene.RowLayout
import com.jawon.app.ui.wallscene.WallLayout
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.sin
import kotlin.math.tan

/** The crate's arithmetic: what stands in it, how it stands, and how a hand goes through it. */
class CrateTest {

    private fun case(id: String, row: Int, col: Int) = CaseLayout(
        albumId = id, title = "T$id", artist = "A$id", storedHue = 0f, fallbackHue = 0f,
        coverPath = null, discFacePath = null, row = row, col = col, x = 0f, y = 0f
    )

    private fun row(id: String, name: String, row: Int) =
        RowLayout(shelfId = id, name = name, row = row, centerY = 0f, boardY = 0f, plateX = 0f)

    /** Shelves of the given sizes, in order. */
    private fun library(vararg sizes: Int): WallLayout {
        val cases = ArrayList<CaseLayout>()
        val rows = ArrayList<RowLayout>()
        sizes.forEachIndexed { r, n ->
            rows += row("s$r", "SHELF $r", r)
            // Out of order on purpose: the crate is in the shelves' order, not the list's.
            for (c in n - 1 downTo 0) cases += case("$r-$c", r, c)
        }
        return WallLayout(cases = cases, rows = rows, loaded = true)
    }

    private fun motion(items: List<CrateItem>, at: Int = Crate.start(items)) =
        CrateMotion().apply { reset(Crate.stops(items), at) }

    // ---- What stands in it ----------------------------------------------------------------------

    @Test
    fun `every shelf's records stand behind its card in the shelves' own order, and the card to add one is last`() {
        val items = Crate.items(library(2, 0, 3))
        val shape = items.map {
            when (it) {
                is CrateItem.Divider -> "[${it.name}:${it.records}]"
                is CrateItem.Record -> it.albumId
                CrateItem.Add -> "+"
            }
        }
        // The empty shelf has no card: a card with nothing behind it says nothing.
        assertEquals(listOf("[SHELF 0:2]", "0-0", "0-1", "[SHELF 2:3]", "2-0", "2-1", "2-2", "+"), shape)
        assertEquals(4, Crate.indexOf(items, "2-0"))
        assertEquals(-1, Crate.indexOf(items, "nobody"))
        assertEquals(-1, Crate.indexOf(items, null))
    }

    @Test
    fun `a library that has not been read yet is an empty crate, and an empty library is the one card`() {
        assertTrue(Crate.items(WallLayout()).isEmpty())
        assertEquals(listOf<CrateItem>(CrateItem.Add), Crate.items(WallLayout(loaded = true)))
        assertEquals(0, Crate.start(listOf(CrateItem.Add)))
    }

    @Test
    fun `a hand rests on records and on the last card, never on a shelf's`() {
        val items = Crate.items(library(2, 3))
        val stops = Crate.stops(items)
        assertEquals(listOf(false, true, true, false, true, true, true, true), stops.toList())
        assertEquals(1, Crate.start(items))
        // Cards are numbered among themselves, for the pool that draws them and for where their tabs are cut.
        assertEquals(listOf(0, -1, -1, 1, -1, -1, -1, 2), Crate.cardOrdinals(items).toList())
        assertEquals(listOf(-1f, 0f, 1f, -1f), (0..3).map { CrateLayout.tabX(it) / CrateLayout.TAB_STEP })
    }

    @Test
    fun `a sideways swipe goes to the next shelf's first record, or back to this one's and then the one before`() {
        val items = Crate.items(library(3, 2, 4))   // [0] 1 2 3 [4] 5 6 [7] 8 9 10 11 +(12)
        assertEquals(5, Crate.shelfStart(items, 2, forward = true))
        assertEquals(8, Crate.shelfStart(items, 5, forward = true))
        assertEquals(12, Crate.shelfStart(items, 9, forward = true))     // past the last shelf: the back of the crate
        assertEquals(12, Crate.shelfStart(items, 12, forward = true))
        assertEquals(8, Crate.shelfStart(items, 10, forward = false))    // the start of where you are
        assertEquals(5, Crate.shelfStart(items, 8, forward = false))     // and from there, the shelf before
        assertEquals(1, Crate.shelfStart(items, 1, forward = false))     // before the first there is nowhere
        assertEquals(8, Crate.shelfStart(items, 12, forward = false))
        assertEquals(0, Crate.shelfStart(emptyList(), 0, forward = true))
    }

    // ---- How it stands ---------------------------------------------------------------------------------

    private fun pose(d: Float, position: Float, count: Int) = CrateLayout.Pose().also { CrateLayout.pose(d, position, count, it) }

    /** The most anything moves for a hundredth of a record: the crate's whole depth in one record's fall. */
    private val STEP = CrateLayout.WALL * 2 / 100 + 0.0003f

    private val positions = listOf(0f, 0.4f, 1f, 2.5f, 5f, 6f, 6.01f, 9.7f, 31f, 60.5f, 82f, 83.3f, 97f, 98.6f, 99f)

    @Test
    fun `nothing jumps - a thing a hair further on stands a hair further on`() {
        val count = 100
        for (p in positions) {
            var d = -8f
            var last = pose(d, p, count)
            while (d < 19f) {
                d += 0.01f
                val now = pose(d, p, count)
                // A sleeve falling across the whole of a nearly empty crate goes four millimetres in
                // a hundredth of a record; a seam that did not meet would be a pitch or more.
                assertTrue("z jumped at d=$d, position $p", abs(now.z - last.z) < STEP)
                assertTrue("lean jumped at d=$d, position $p", abs(now.leanDeg - last.leanDeg) < 1.0f)
                last = now
            }
            // The two seams themselves — still standing to falling, falling to landed — meet exactly.
            for (seam in listOf(0f, -1f)) {
                val before = pose(seam + 1e-4f, p, count)
                val after = pose(seam - 1e-4f, p, count)
                assertTrue("the seam at $seam does not meet at position $p", abs(before.z - after.z) < 0.0001f)
                assertTrue(abs(before.leanDeg - after.leanDeg) < 0.01f)
            }
        }
        // And as the hand moves, each thing moves with it and not in steps.
        for (item in listOf(0, 3, 40, 41, 98, 99)) {
            var p = 0f
            var last = pose(item - p, p, count)
            while (p < 99f) {
                p += 0.01f
                val now = pose(item - p, p, count)
                assertTrue("item $item jumped at position $p", abs(now.z - last.z) < STEP && abs(now.leanDeg - last.leanDeg) < 1.0f)
                last = now
            }
        }
    }

    @Test
    fun `nothing goes through the wood, and both piles rest on it`() {
        val wall = CrateLayout.WALL
        val top = CrateLayout.WALL_TOP
        for (count in listOf(1, 2, 5, 30, 100)) {
            for (p in positions.filter { it <= count - 1 }) {
                for (i in 0 until count) {
                    val at = pose(i - p, p, count)
                    if (!at.drawn) continue
                    // Its foot is on the floor of the crate, and where it passes the top of the wood it is inside it.
                    val atTop = at.z + top * tan(Math.toRadians(at.leanDeg.toDouble())).toFloat()
                    assertTrue("foot at ${at.z} (item $i, position $p, of $count)", at.z in -wall..wall)
                    assertTrue("through the wood at $atTop (item $i, position $p, of $count)", atTop >= -wall - 1e-4f && atTop <= wall + 1e-4f)
                }
            }
        }
        // The last record of a crate leans on its back; the first thing flipped, on its front.
        assertEquals(CrateLayout.REST_BACK, pose(0f, 29f, 30).z, 1e-6f)
        assertEquals(CrateLayout.REST_BACK, CrateLayout.readZ(29f, 30), 1e-6f)
        assertEquals(CrateLayout.REST_FRONT, pose(-1f, 1f, 30).z, 1e-6f)
        // In the middle of a long crate there is a gap to read a cover across: wider than a hand's width of nothing.
        val gap = pose(-1f, 50f, 100).z - pose(0f, 50f, 100).z
        assertTrue("a gap of ${gap * 1000}mm", gap > 0.08f)
    }

    @Test
    fun `no sleeve is ever in another - neither at its foot nor at its top`() {
        val h = VinylDimens.SLEEVE
        for (count in listOf(2, 9, 40, 100)) {
            var p = 0f
            while (p <= count - 1) {
                var before: CrateLayout.Pose? = null
                for (i in 0 until count) {
                    val at = pose(i - p, p, count)
                    if (!at.drawn) { before = null; continue }
                    val prev = before
                    if (prev != null) {
                        // The one nearer you is nearer you all the way up, by more than a sleeve is thick.
                        val foot = prev.z - at.z
                        val head = (prev.z + h * sin(Math.toRadians(prev.leanDeg.toDouble())).toFloat()) -
                            (at.z + h * sin(Math.toRadians(at.leanDeg.toDouble())).toFloat())
                        val square = cos(Math.toRadians(maxOf(abs(prev.leanDeg), abs(at.leanDeg)).toDouble())).toFloat()
                        assertTrue("feet ${foot * 1000}mm apart at item $i, position $p of $count", foot * square > VinylDimens.SLEEVE_T)
                        assertTrue("tops ${head * 1000}mm apart at item $i, position $p of $count", head * square > VinylDimens.SLEEVE_T)
                    }
                    before = at
                }
                p += 0.07f
            }
        }
    }

    @Test
    fun `the pool is a ring - each thing one sleeve, one sleeve changing for each record passed, and never one in sight`() {
        val count = 200
        val pool = CrateLayout.POOL
        var p = 20f
        var was = IntArray(pool) { CrateLayout.itemFor(it, p, count) }
        while (p < 60f) {
            p += 0.05f
            val now = IntArray(pool) { CrateLayout.itemFor(it, p, count) }
            // No two sleeves are the same thing, and every thing that is drawn has a sleeve.
            assertEquals(pool, now.filter { it >= 0 }.distinct().size)
            for (i in 0 until count) {
                if (pose(i - p, p, count).drawn) assertTrue("item $i has no sleeve at $p", now.contains(i))
            }
            val changed = (0 until pool).filter { was[it] != now[it] }
            assertTrue("${changed.size} sleeves changed at once at $p", changed.size <= 1)
            for (slot in changed) {
                assertFalse("the sleeve that changed was in sight", pose(now[slot] - p, p, count).drawn)
                assertFalse(pose(was[slot] - p, p, count).drawn)
            }
            was = now
        }
        // A thing is always the same sleeve, wherever the hand is.
        assertEquals(CrateLayout.itemFor(77 % pool, 75f, count), CrateLayout.itemFor(77 % pool, 80.9f, count))
        // Off either end of the crate a sleeve is nothing.
        assertTrue((0 until pool).count { CrateLayout.itemFor(it, 0f, 3) >= 0 } == 3)
    }

    // ---- A hand going through it -----------------------------------------------------------------------------

    private fun settle(m: CrateMotion, onStep: () -> Unit = {}): Int {
        var steps = 0
        while (!m.settled && steps < 2400) {
            m.step(1f / 120f)
            onStep()
            steps++
        }
        return steps
    }

    @Test
    fun `let go, it comes to rest on a record - never between two, never on a shelf's card, never outside`() {
        val items = Crate.items(library(1, 1, 1, 6, 2))
        val stops = Crate.stops(items)
        for (from in listOf(1, 3, 7, 11)) {
            for (dragged in listOf(-3.4f, -0.6f, -0.2f, 0f, 0.3f, 0.5f, 1.5f, 2.6f, 9f)) {
                for (speed in listOf(-30f, -4f, -0.5f, 0f, 0.5f, 4f, 30f)) {
                    val m = motion(items, from)
                    m.grab()
                    m.drag(dragged)
                    m.release(speed)
                    val took = settle(m)
                    assertTrue("did not settle from $from, $dragged, $speed", m.settled)
                    assertTrue("took ${took / 120f}s", took < 240)
                    assertEquals(m.position, m.nearest.toFloat(), 0f)
                    assertTrue("rested on a card at ${m.nearest} (from $from, $dragged, $speed)", stops[m.nearest])
                }
            }
        }
    }

    @Test
    fun `it arrives and does not come back - and never further than the wood allows`() {
        val items = Crate.items(library(40))
        for (speed in listOf(-60f, -9f, -2f, 2f, 9f, 60f)) {
            val m = motion(items, 20)
            m.grab()
            m.drag(if (speed > 0) 0.3f else -0.3f)
            m.release(speed)
            val going = if (m.target >= m.position) 1f else -1f
            var last = m.position
            settle(m) {
                assertTrue("turned back at ${m.position} on the way to ${m.target}", (m.position - last) * going >= -1e-4f)
                assertTrue("went past ${m.target}", (m.target - m.position) * going >= -1e-3f)
                last = m.position
            }
            assertTrue("a flick at $speed ran ${abs(m.position - 20f)} records", abs(m.position - 20f) <= CrateMotion.FURTHEST_RUN + 1f)
        }
        // Flicked hard at the back of the crate, where there is nowhere to run on to: it stops
        // on the last thing in it, and not past it and back.
        val end = motion(items, items.lastIndex - 1)
        end.grab()
        end.drag(0.6f)
        end.release(60f)
        var furthest = end.position
        settle(end) { furthest = maxOf(furthest, end.position) }
        assertEquals(items.lastIndex.toFloat(), end.position, 0f)
        assertTrue("ran ${furthest - items.lastIndex} of a record past the back", furthest <= items.lastIndex + 1e-3f)
        // Dragged past either end it gives a third of a record and no more, and goes back when let go.
        val m = motion(items, 1)
        m.grab()
        m.drag(-50f)
        assertTrue(m.position > 1f - CrateMotion.MOST_GIVE - 1e-4f && m.position < 1f)
        m.release(-20f)
        settle(m)
        assertEquals(1f, m.position, 0f)
        m.grab()
        m.drag(500f)
        assertTrue(m.position < items.lastIndex + CrateMotion.MOST_GIVE + 1e-4f)
        m.release(20f)
        settle(m)
        assertEquals(items.lastIndex.toFloat(), m.position, 0f)
    }

    @Test
    fun `a flick always goes somewhere, and a change of mind goes back`() {
        val items = Crate.items(library(10))
        // A short quick flick: the finger barely moved — by its speed alone it would have come
        // to rest less than half a record on, and gone back — and it is still the next record.
        val flick = motion(items, 4)
        flick.grab()
        flick.drag(0.05f)
        flick.release(1.6f)
        settle(flick)
        assertEquals(5f, flick.position, 0f)
        val back = motion(items, 4)
        back.grab()
        back.drag(-0.05f)
        back.release(-1.6f)
        settle(back)
        assertEquals(3f, back.position, 0f)
        // Dragged a little and let go still: it was not a flick, and it goes back where it was.
        val mind = motion(items, 4)
        mind.grab()
        mind.drag(0.3f)
        mind.release(0.2f)
        settle(mind)
        assertEquals(4f, mind.position, 0f)
        // Over a shelf's card, a flick lands on the record beyond it and not on the card.
        val shelves = Crate.items(library(2, 2))   // [0] 1 2 [3] 4 5 +
        val over = motion(shelves, 2)
        over.grab()
        over.drag(0.1f)
        over.release(2.0f)
        settle(over)
        assertEquals(4f, over.position, 0f)
    }

    @Test
    fun `told to go somewhere it goes there, and a nudge is one record`() {
        val items = Crate.items(library(3, 3))   // [0] 1 2 3 [4] 5 6 7 +(8)
        val m = motion(items)
        assertEquals(1f, m.position, 0f)
        m.goTo(4)                                 // a card: the record after it
        settle(m)
        assertEquals(5f, m.position, 0f)
        m.nudge(-1)                               // back over the card to the record before it
        settle(m)
        assertEquals(3f, m.position, 0f)
        m.nudge(1); m.nudge(1)
        settle(m)
        assertEquals(6f, m.position, 0f)
        m.goTo(400)
        settle(m)
        assertEquals(8f, m.position, 0f)
        m.nudge(1)                                // at the back of the crate there is no further
        settle(m)
        assertEquals(8f, m.position, 0f)
        // A crate that changes under the hand keeps the hand inside it.
        m.reset(Crate.stops(Crate.items(library(1))), 8)
        assertEquals(2f, m.position, 0f)
        assertTrue(m.settled)
        // An empty crate has no hand in it, and nothing throws.
        val none = CrateMotion()
        none.grab(); none.drag(1f); none.release(3f); none.goTo(2); none.nudge(1); none.step(0.016f)
        assertEquals(0, none.nearest)
    }
}
