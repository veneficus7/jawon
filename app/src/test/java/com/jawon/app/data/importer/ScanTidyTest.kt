package com.jawon.app.data.importer

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ScanTidyTest {

    @Test
    fun `the phone's own recordings and voice notes are never offered`() {
        assertTrue(ScanTidy.isNeverMusic("Recordings/Voice Recorder/", isRecording = false))
        assertTrue(ScanTidy.isNeverMusic("Music/Recordings/", isRecording = false))
        assertTrue(ScanTidy.isNeverMusic("Android/media/com.whatsapp/WhatsApp/Media/WhatsApp Voice Notes/202641/", false))
        assertTrue(ScanTidy.isNeverMusic("Call/", false))
        assertTrue(ScanTidy.isNeverMusic("Notifications/", false))
        // Android's own flag counts wherever the file is.
        assertTrue(ScanTidy.isNeverMusic("Music/", isRecording = true))
    }

    @Test
    fun `music folders, including ones that mention a word in passing, are offered`() {
        assertFalse(ScanTidy.isNeverMusic("Music/Radiohead - In Rainbows/", false))
        assertFalse(ScanTidy.isNeverMusic("Download/", false))
        // "Sounds of Silence" is not the "Sounds" folder: whole segments only.
        assertFalse(ScanTidy.isNeverMusic("Music/Sounds of Silence/", false))
        assertFalse(ScanTidy.isNeverMusic(null, false))
    }

    @Test
    fun `an untagged file in Download is not an album called Download`() {
        assertTrue(ScanTidy.isFolderAlbum("Download", "Download/"))
        assertTrue(ScanTidy.isFolderAlbum("Music", "Music/"))
        assertTrue(ScanTidy.isFolderAlbum("WhatsApp Audio", "Android/media/com.whatsapp/WhatsApp/Media/WhatsApp Audio/"))
        assertTrue(ScanTidy.isFolderAlbum(null, "Music/"))
        assertTrue(ScanTidy.isFolderAlbum("<unknown>", "Music/Somewhere/"))
    }

    @Test
    fun `a record's own folder, or a real album tag, is an album`() {
        // Untagged rips in a folder named for the record still come in together.
        assertFalse(ScanTidy.isFolderAlbum("Radiohead - In Rainbows", "Music/Radiohead - In Rainbows/"))
        // A tagged album sitting loose in Download is still that album.
        assertFalse(ScanTidy.isFolderAlbum("In Rainbows", "Download/"))
    }

    @Test
    fun `albums come first by title, then the singles by title`() {
        data class R(val title: String, val tracks: Int)
        val found = listOf(R("Zebra", 1), R("blue", 10), R("Apple", 2), R("Abbey Road", 17), R("Mono", 3))
        val (ordered, singlesStart) = ScanTidy.albumsThenSingles(found, { it.tracks }, { it.title })
        assertEquals(listOf("Abbey Road", "blue", "Mono", "Apple", "Zebra"), ordered.map { it.title })
        assertEquals(3, singlesStart)
    }

    @Test
    fun `a three-track EP is an album, two tracks a single`() {
        assertTrue(ScanTidy.isSingle(1))
        assertTrue(ScanTidy.isSingle(2))
        assertFalse(ScanTidy.isSingle(3))
    }
}
