package com.jawon.app.ui.pocketscene

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The device's menus. The rules here are the classic's, and each one is a way the device
 * could feel subtly wrong: a list that wraps, a Back that forgets your place, an album
 * filed under T.
 */
class PocketMenuTest {

    private val library = PocketLibrary(
        shelves = listOf(
            PocketLibrary.Shelf("s2", "Late", listOf("a3", "a1")),
            PocketLibrary.Shelf("s1", "Early", listOf("a2"))
        ),
        albums = listOf(
            PocketLibrary.Album("a1", "The Wall", "Pink Floyd", null),
            PocketLibrary.Album("a2", "Abbey Road", "The Beatles", null),
            PocketLibrary.Album("a3", "Kind of Blue", "Miles Davis", null),
            PocketLibrary.Album("a4", "Animals", "pink floyd", null)
        )
    )

    private fun labels(key: MenuKey, tracks: Map<String, List<String>> = emptyMap(), playing: Boolean = false) =
        PocketMenus.rows(key, library, tracks, playing).map { it.label }

    // ---- what each list holds ---------------------------------------------------------------

    @Test
    fun `albums are filed the classic's way, ignoring a leading The`() {
        assertEquals(listOf("Abbey Road", "Animals", "Kind of Blue", "The Wall"), labels(MenuKey.Albums))
    }

    @Test
    fun `shelves keep the order they were arranged in, and so do their albums`() {
        assertEquals(listOf("Late", "Early"), labels(MenuKey.Shelves))
        assertEquals(listOf("Kind of Blue", "The Wall"), labels(MenuKey.Shelf("s2")))
    }

    @Test
    fun `an artist appears once however their name was typed`() {
        assertEquals(listOf("The Beatles", "Miles Davis", "Pink Floyd"), labels(MenuKey.Artists))
        assertEquals(listOf("Animals", "The Wall"), labels(MenuKey.Artist("Pink Floyd")))
    }

    @Test
    fun `an album lists its tracks once they have been read, and each one plays`() {
        assertTrue(labels(MenuKey.Album("a1")).isEmpty())
        val rows = PocketMenus.rows(MenuKey.Album("a1"), library, mapOf("a1" to listOf("In the Flesh?", "Hey You")), false)
        assertEquals(listOf("In the Flesh?", "Hey You"), rows.map { it.label })
        assertEquals(RowAction.PlayTrack("a1", 1), rows[1].action)
        assertFalse(rows[1].leadsOn)
    }

    @Test
    fun `the main menu offers Now Playing only when there is something to go back to`() {
        assertFalse("Now Playing" in labels(MenuKey.Main, playing = false))
        assertTrue("Now Playing" in labels(MenuKey.Main, playing = true))
    }

    @Test
    fun `Cover Flow holds the albums in the Albums list's order, and turning one over lists its tracks`() {
        assertEquals(labels(MenuKey.Albums), labels(MenuKey.CoverFlow))
        val flow = PocketMenus.rows(MenuKey.CoverFlow, library, emptyMap(), false)
        assertEquals(RowAction.Open(MenuKey.Flipped("a2")), flow.first().action)
        val back = PocketMenus.rows(MenuKey.Flipped("a1"), library, mapOf("a1" to listOf("In the Flesh?")), false)
        assertEquals(RowAction.PlayTrack("a1", 0), back.single().action)
        assertEquals("The Wall", PocketMenus.title(MenuKey.Flipped("a1"), library))
    }

    @Test
    fun `the main menu's Settings is the device's own, and each setting shows its value`() {
        val main = PocketMenus.rows(MenuKey.Main, library, emptyMap(), false)
        assertEquals(RowAction.Open(MenuKey.Settings), main.last().action)
        val settings = PocketSettings(
            eqOptions = listOf("Off", "Rock"), eqChosen = 1, soundCheck = 1,
            backlightMs = 0L, clicker = false
        )
        val rows = PocketMenus.rows(MenuKey.Settings, library, emptyMap(), false, settings)
            .associate { it.label to it.value }
        assertEquals("Rock", rows["EQ"])
        assertEquals("Album", rows["Sound Check"])
        assertEquals("Always On", rows["Backlight"])
        assertEquals("Off", rows["Clicker"])
    }

    @Test
    fun `bands set by hand in the rack read as Custom rather than as a preset they are not`() {
        val rows = PocketMenus.rows(
            MenuKey.Settings, library, emptyMap(), false,
            PocketSettings(eqOptions = listOf("Off", "Rock"), eqChosen = -1)
        )
        assertEquals("Custom", rows.first { it.label == "EQ" }.value)
    }

    @Test
    fun `a setting's choices tick the current one, and choosing leads nowhere further`() {
        val rows = PocketMenus.rows(
            MenuKey.Choice(PocketSetting.BACKLIGHT), library, emptyMap(), false,
            PocketSettings(backlightMs = 15_000L)
        )
        assertEquals(listOf("15 Seconds"), rows.filter { it.checked }.map { it.label })
        assertEquals(RowAction.Choose(PocketSetting.BACKLIGHT, 3), rows.first { it.checked }.action)
        assertFalse(rows.any { it.leadsOn })
    }

    @Test
    fun `About counts the library and goes nowhere`() {
        val rows = PocketMenus.rows(
            MenuKey.About, library, emptyMap(), false,
            PocketSettings(albums = 4, songs = 37, shelves = 2, version = "0.6.2")
        )
        assertEquals(listOf("4", "37", "2", "0.6.2"), rows.map { it.value })
        assertTrue(rows.all { it.action == RowAction.Nothing && !it.leadsOn })
    }

    @Test
    fun `sort keys set aside an article but not a whole name`() {
        assertEquals("beatles", PocketMenus.sortKey("The Beatles"))
        assertEquals("the", PocketMenus.sortKey("The"))
        assertEquals("tribe called quest", PocketMenus.sortKey("A Tribe Called Quest"))
        assertEquals("theatre", PocketMenus.sortKey("Theatre"))
    }

    // ---- moving the highlight ---------------------------------------------------------------

    @Test
    fun `the highlight stops at either end rather than wrapping`() {
        val nav = PocketNav()
        assertSame(nav, nav.move(-1, 5))
        val bottom = nav.move(99, 5)
        assertEquals(4, bottom.current.selected)
        assertSame(bottom, bottom.move(1, 5))
    }

    @Test
    fun `the list scrolls only as far as keeps the highlight on screen`() {
        val visible = PocketMenus.VISIBLE_ROWS
        var nav = PocketNav().open(MenuKey.Albums)
        repeat(visible - 1) { nav = nav.move(1, 30) }
        assertEquals(0, nav.current.top)
        nav = nav.move(1, 30)
        assertEquals(1, nav.current.top)
        // Back up by less than a screen: the window stays put.
        nav = nav.move(-3, 30)
        assertEquals(1, nav.current.top)
        nav = nav.move(-5, 30)
        assertEquals(0, nav.current.top)
    }

    @Test
    fun `going back returns to the row you left`() {
        val nav = PocketNav().move(2, 6).open(MenuKey.Artists).move(1, 3)
        val back = nav.back()
        assertEquals(MenuKey.Main, back.current.key)
        assertEquals(2, back.current.selected)
    }

    @Test
    fun `back from the main menu goes nowhere, and main skips every level`() {
        val nav = PocketNav()
        assertSame(nav, nav.back())
        val deep = nav.move(1, 6).open(MenuKey.Shelves).open(MenuKey.Shelf("s1")).open(MenuKey.Album("a2"))
        val main = deep.main()
        assertEquals(1, main.stack.size)
        assertEquals(1, main.current.selected)
    }

    @Test
    fun `where you were survives being saved, including a name with a bar in it`() {
        val nav = PocketNav().move(2, 6).open(MenuKey.Artists).move(4, 9)
            .open(MenuKey.Artist("Sly | The Family Stone")).open(MenuKey.Album("a-1")).move(1, 5)
            .open(MenuKey.CoverFlow).move(3, 9).open(MenuKey.Flipped("a-2"))
            .open(MenuKey.Settings).open(MenuKey.About).open(MenuKey.Choice(PocketSetting.CLICKER))
        val restored = nav.stack.map { PocketNav.encode(it) }.map { PocketNav.decode(it) }
        assertEquals(nav.stack, restored)
    }

    @Test
    fun `something unreadable restores to the main menu rather than a broken one`() {
        assertEquals(null, PocketNav.decode("garbage"))
        assertEquals(null, PocketNav.decode("1|0|nonsense|"))
    }

    @Test
    fun `a list that shrinks under the highlight keeps it on a real row`() {
        val nav = PocketNav().open(MenuKey.Albums).move(20, 30)
        val clamped = nav.clamp(4)
        assertEquals(3, clamped.current.selected)
        assertEquals(0, clamped.current.top)
    }
}
