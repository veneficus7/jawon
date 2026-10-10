package com.jawon.app.data.importer

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * "Open with Jawon": what each thing inside something handed over is, and where it may be put.
 *
 * An archive is someone else's idea of a folder. The rule these hold is that its paths only ever
 * *group* files into albums — they never decide where anything is written.
 */
class OpenedPlanTest {

    private fun plan(path: String) = OpenedPlan.plan(path, isDirectory = false)

    // ---- What is in an archive ---------------------------------------------------------------

    @Test
    fun `music is music, by its name, whatever the case`() {
        for (name in listOf("01 Only Shallow.flac", "track.MP3", "a.m4a", "b.ogg", "c.opus", "d.wav", "e.aac")) {
            assertEquals(name, OpenedPlan.Kind.AUDIO, plan("Loveless/$name")?.kind)
        }
    }

    @Test
    fun `a picture is the album's cover, and one called cover is the surest`() {
        val named = plan("Loveless/cover.jpg")!!
        val folder = plan("Loveless/Folder.PNG")!!
        val scan = plan("Loveless/booklet-03.jpg")!!
        assertEquals(OpenedPlan.Kind.COVER, named.kind)
        assertEquals(named.coverRank, folder.coverRank)
        assertTrue(named.coverRank > scan.coverRank)
    }

    @Test
    fun `everything else in the archive is left in it`() {
        assertNull(plan("Loveless/liner notes.pdf"))
        assertNull(plan("Loveless/album.cue"))
        assertNull(plan("readme.txt"))
        assertNull(OpenedPlan.plan("Loveless/", isDirectory = true))
    }

    @Test
    fun `what a Mac leaves in every archive it makes is not music`() {
        assertNull(plan("__MACOSX/Loveless/._01 Only Shallow.flac"))
        assertNull(plan("Loveless/.DS_Store"))
        assertNull(plan("Loveless/._01 Only Shallow.flac"))
    }

    @Test
    fun `an archive's folders are its albums`() {
        assertEquals("", plan("01 Only Shallow.flac")?.group)
        assertEquals("Loveless", plan("Loveless/01 Only Shallow.flac")?.group)
        assertEquals("MBV/Loveless", plan("MBV/Loveless/01 Only Shallow.flac")?.group)
        // Windows writes its paths the other way round.
        assertEquals("MBV/Loveless", plan("MBV\\Loveless\\01 Only Shallow.flac")?.group)
        assertEquals(plan("A/1.mp3")?.group, plan("A/2.mp3")?.group)
    }

    // ---- Where it may be put -----------------------------------------------------------------

    @Test
    fun `a path that climbs out of its folder is only ever a file's own name`() {
        val climbing = plan("../../../data/data/com.jawon.app/files/evil.mp3")!!
        assertEquals("evil.mp3", climbing.name)
        assertFalse(climbing.group.contains(".."))

        val absolute = plan("/sdcard/Android/evil.flac")!!
        assertEquals("evil.flac", absolute.name)

        for (path in listOf("..\\..\\evil.mp3", "a/../../b/evil.mp3", "./../evil.mp3")) {
            val planned = plan(path)!!
            assertFalse("$path kept a separator", planned.name.contains('/') || planned.name.contains('\\'))
            assertFalse("$path kept a climb", planned.group.split('/').contains(".."))
        }
    }

    @Test
    fun `an archive with an entry that climbs is known for what it is`() {
        assertTrue(OpenedPlan.climbs("../../../escaped.mp3"))
        assertTrue(OpenedPlan.climbs("Album/../../escaped.mp3"))
        assertTrue(OpenedPlan.climbs("..\\..\\escaped.mp3"))
        assertTrue(OpenedPlan.climbs("/sdcard/escaped.mp3"))
        // Dots in a name are not a climb.
        assertFalse(OpenedPlan.climbs("Album/01 ...And Justice for All.mp3"))
        assertFalse(OpenedPlan.climbs("R.E.M./Murmur/01 Radio Free Europe.mp3"))
        assertFalse(OpenedPlan.climbs("Album..Deluxe/01.mp3"))
    }

    @Test
    fun `a name is made safe to create - no separators, no control characters, nothing refused`() {
        assertEquals("AC_DC - Back in Black.mp3", OpenedPlan.safeName("AC:DC - Back in Black.mp3"))
        assertEquals("What_.flac", OpenedPlan.safeName("What?.flac"))
        assertEquals("tab_here.mp3", OpenedPlan.safeName("tab\there.mp3"))
        assertEquals("song.mp3", OpenedPlan.safeName("some/dir/song.mp3"))
        // A name that is nothing once cleaned is no name.
        assertNull(OpenedPlan.safeName(""))
        assertNull(OpenedPlan.safeName("..."))
        assertNull(OpenedPlan.safeName("   "))
        assertNull(OpenedPlan.safeName("dir/"))
    }

    @Test
    fun `a very long name is shortened and keeps what kind of file it is`() {
        val long = "A".repeat(400) + ".flac"
        val safe = OpenedPlan.safeName(long)!!
        assertTrue(safe.length <= 120)
        assertTrue(safe.endsWith(".flac"))
    }

    @Test
    fun `an album from an archive is kept under the archive's own name`() {
        assertEquals(
            "My Bloody Valentine - Loveless",
            OpenedPlan.folderFor("My Bloody Valentine - Loveless.zip", "", groups = 1)
        )
        // One folder inside, named for the album: not "Loveless/Loveless".
        assertEquals("Loveless", OpenedPlan.folderFor("Loveless.zip", "Loveless", groups = 1))
        // Several albums in one archive: each in its own.
        assertEquals("Discography/Loveless", OpenedPlan.folderFor("Discography.zip", "Loveless", groups = 2))
        assertEquals("Discography/Isn't Anything", OpenedPlan.folderFor("Discography.zip", "1988/Isn't Anything", groups = 2))
        // A folder inside named as the archive is: not repeated.
        assertEquals("Loveless", OpenedPlan.folderFor("Loveless.zip", "loveless", groups = 2))
    }

    @Test
    fun `an archive with no usable name still has somewhere to go, and it is never outside`() {
        assertEquals("Opened", OpenedPlan.folderFor(null, "", groups = 1))
        assertEquals("Opened", OpenedPlan.folderFor("...zip", "", groups = 1))
        val hostile = OpenedPlan.folderFor("../../etc.zip", "../../up", groups = 2)
        assertFalse(hostile.split('/').contains(".."))
        assertFalse(hostile.startsWith("/"))
    }

    // ---- What was handed over ----------------------------------------------------------------

    @Test
    fun `a ZIP is known by its name or by what it says it is`() {
        assertTrue(OpenedPlan.isZip("Loveless.zip", null))
        assertTrue(OpenedPlan.isZip("Loveless.ZIP", "application/octet-stream"))
        assertTrue(OpenedPlan.isZip("download", "application/zip"))
        assertTrue(OpenedPlan.isZip(null, "application/x-zip-compressed"))
        assertFalse(OpenedPlan.isZip("Loveless.flac", "audio/flac"))
    }

    @Test
    fun `a song is known by its name or by what it says it is`() {
        assertTrue(OpenedPlan.isAudio("song.mp3", null))
        assertTrue(OpenedPlan.isAudio("12345", "audio/mpeg"))
        assertFalse(OpenedPlan.isAudio("photo.jpg", "image/jpeg"))
        assertFalse(OpenedPlan.isAudio(null, null))
    }

    @Test
    fun `every kind of music has something to tell the phone's index it is`() {
        for (ext in OpenedPlan.AUDIO_EXTENSIONS) {
            val mime = OpenedPlan.mimeFor("song.$ext")
            assertTrue("$ext -> $mime", mime.startsWith("audio/") && mime != "audio/*")
        }
        assertNotNull(OpenedPlan.mimeFor("song"))
    }
}
