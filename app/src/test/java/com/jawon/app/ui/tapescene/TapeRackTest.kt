package com.jawon.app.ui.tapescene

import com.jawon.app.ui.wallscene.CaseLayout
import com.jawon.app.ui.wallscene.RowLayout
import com.jawon.app.ui.wallscene.WallLayout
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.abs

/** The racks: which tape is in which slot, where a finger finds it, and how the box is carried to the deck. */
class TapeRackTest {

    private fun case(id: String, row: Int, col: Int) = CaseLayout(
        albumId = id, title = "Title $id", artist = "Artist", storedHue = 0f, fallbackHue = 0.3f,
        coverPath = null, discFacePath = null, row = row, col = col, x = 0f, y = 0f
    )

    private fun layout(perShelf: List<Int>): WallLayout {
        val rows = perShelf.indices.map { RowLayout("s$it", "Shelf $it", it, 0f, 0f, 0f) }
        val cases = perShelf.flatMapIndexed { row, n -> (0 until n).map { case("a$row-$it", row, it) } }
        return WallLayout(cases = cases, rows = rows, loaded = true)
    }

    @Test
    fun aTowerPerShelfInOrderAndALongShelfGoesOnInASecond() {
        val towers = TapeRack.towers(layout(listOf(3, 0, TapeDimens.RACK_SLOTS + 5)))
        assertEquals("an empty shelf has no rack", 3, towers.size)
        assertEquals("Shelf 0", towers[0].name)
        assertEquals(listOf(0, 1, 2), towers[0].tapes.map { it.slot })
        assertEquals(TapeDimens.RACK_SLOTS, towers[1].tapes.size)
        assertEquals(5, towers[2].tapes.size)
        assertEquals("1 OF 2", towers[1].partLabel)
        assertEquals("2 OF 2", towers[2].partLabel)
        assertEquals("", towers[0].partLabel)
        assertEquals(2 to 4, TapeRack.find(towers, "a2-28"))
    }

    @Test
    fun notBeforeTheLibraryHasBeenRead() {
        assertTrue(TapeRack.towers(WallLayout(loaded = false)).isEmpty())
    }

    @Test
    fun aFingerOnASpineFindsThatTape() {
        val towers = TapeRack.towers(layout(listOf(10, 10)))
        for (tower in 0..1) for (slot in listOf(0, 4, 9)) {
            val y = TapeDimens.slotY(slot) + TapeDimens.BOX_T / 2
            assertEquals(tower to slot, TapeRack.at(towers, TapeDimens.rackX(tower) + 0.02f, y))
        }
        // An empty slot, between racks, under them: nothing.
        assertNull(TapeRack.at(towers, 0f, TapeDimens.slotY(15) + 0.008f))
        assertNull(TapeRack.at(towers, TapeDimens.rackX(0) + 0.083f, TapeDimens.slotY(0) + 0.008f))
        assertNull(TapeRack.at(towers, 0f, TapeDimens.RACKS_BOTTOM - 0.01f))
    }

    @Test
    fun theSlotsTickInTheOrderTheyAreRead() {
        assertEquals(0, TapeRack.slotAtHeight(TapeDimens.slotY(0) + 0.008f))
        assertEquals(TapeDimens.RACK_SLOTS - 1, TapeRack.slotAtHeight(TapeDimens.slotY(TapeDimens.RACK_SLOTS - 1) + 0.008f))
    }

    @Test
    fun theCameraLooksOnlyAlongTheRack() {
        val range = TapeRack.lookRange(TapeCamera.rackTall(3120f / 1440f))
        assertTrue(range.start >= TapeDimens.RACKS_BOTTOM)
        assertTrue(range.endInclusive <= TapeDimens.RACKS_BOTTOM + TapeDimens.RACK_H)
        val motion = RackMotion()
        motion.setRange(range)
        motion.place(0, range.start)
        motion.grab()
        motion.drag(-1f)
        motion.fling(-2f)
        repeat(300) { motion.step(1f / 60f) }
        assertEquals("drawn back to its end", range.start, motion.y, 0.001f)
        assertTrue(motion.settled)
    }

    @Test
    fun aSwipeSettlesSquareOnTheNextRackAndNotPastTheLast() {
        val motion = RackMotion()
        motion.place(0, 0.5f)
        motion.swipe(forward = true, count = 2)
        repeat(120) { motion.step(1f / 60f) }
        assertEquals(1f, motion.tower, 0.001f)
        motion.swipe(forward = true, count = 2)
        repeat(120) { motion.step(1f / 60f) }
        assertEquals(1f, motion.tower, 0.001f)
    }

    @Test
    fun theBoxIsCarriedDownToTheDeckAndOpensAndTheTapeEndsInTheDoor() {
        val state = LoadTape.State()
        val x = TapeDimens.rackX(2)
        val y = TapeDimens.slotY(5)
        LoadTape.at(0f, x, y, 0f, state)
        assertEquals("from its slot", TapeDimens.SLOT_CASE_Z, state.case.z, 1e-4f)
        assertEquals(x, state.case.x, 1e-5f)
        LoadTape.at(LoadTape.TOTAL_S, x, y, TapeDimens.DOOR_OPEN_DEG, state)
        assertEquals("on the deck", 0f, state.case.x, 1e-5f)
        assertEquals(TapeDimens.CASE_Y, state.case.y, 1e-5f)
        assertEquals(TapeDimens.CASE_Z, state.case.z, 1e-5f)
        assertEquals(TapeDimens.LID_OPEN_DEG, state.lidDeg, 1e-3f)
        val seat = Pose()
        LoadTape.seat(TapeDimens.DOOR_OPEN_DEG, seat)
        assertEquals(seat.x, state.cassette.x, 1e-4f)
        assertEquals(seat.y, state.cassette.y, 1e-4f)
        assertEquals(seat.z, state.cassette.z, 1e-4f)
        assertEquals("turned with the door", 1f, abs(dot(seat.q, state.cassette.q)), 1e-4f)
    }

    @Test
    fun nothingJumpsOnTheWay() {
        val state = LoadTape.State()
        val x = TapeDimens.rackX(1)
        val y = TapeDimens.slotY(3)
        var px = Float.NaN
        var py = 0f
        var pz = 0f
        var cx = 0f
        var cy = 0f
        var cz = 0f
        var t = 0f
        while (t <= LoadTape.TOTAL_S) {
            LoadTape.at(t, x, y, TapeDimens.DOOR_OPEN_DEG * LoadTape.ease((t - LoadTape.DOOR_AT) / 0.2f), state)
            if (!px.isNaN()) {
                val box = maxOf(abs(state.case.x - px), abs(state.case.y - py), abs(state.case.z - pz))
                val tape = maxOf(abs(state.cassette.x - cx), abs(state.cassette.y - cy), abs(state.cassette.z - cz))
                // Sixty frames a second: the box crosses a metre and a half in under a second, so a few centimetres a frame is moving, more is a jump.
                assertTrue("box jumped $box at $t", box < 0.05f)
                assertTrue("tape jumped $tape at $t", tape < 0.05f)
            }
            px = state.case.x; py = state.case.y; pz = state.case.z
            cx = state.cassette.x; cy = state.cassette.y; cz = state.cassette.z
            t += 1f / 60f
        }
    }

    @Test
    fun theCoverFacesYouWhenItIsShown() {
        // Held up to be looked at, the box's top (its cover, +y) looks at the camera (+z) and its
        // spine (−z) is on the left (−x), as a book's is.
        val out = FloatArray(3)
        LoadTape.SHOWN.rotate(0f, 1f, 0f, out)
        assertEquals(1f, out[2], 1e-5f)
        LoadTape.SHOWN.rotate(0f, 0f, -1f, out)
        assertEquals(-1f, out[0], 1e-5f)
        LoadTape.SHOWN.rotate(1f, 0f, 0f, out)
        assertEquals("the cover's up is up", 1f, out[1], 1e-5f)
    }

    @Test
    fun aSongOnTheJCardIsFoundWhereItIsPrinted() {
        val set = TapeSet.of(List(9) { 240_000L })
        val layout = JCardLayout.of(set.sides, 0)
        for (row in layout.rows) {
            val u = (row.left + row.right) / 2
            val v = (row.top + row.bottom) / 2
            assertEquals(row.trackIndex, layout.trackAt(u, v))
        }
        assertEquals(-1, layout.trackAt(0.5f, 0.05f))
        assertEquals("every track is on the card", 9, layout.rows.size)
    }

    private fun dot(a: Quat, b: Quat): Float = a.w * b.w + a.x * b.x + a.y * b.y + a.z * b.z
}
