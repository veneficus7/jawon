package com.jawon.app.data.spotify

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/** Robolectric because this parses with `org.json`, which the bare JVM runtime stubs out. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class SpotifyClientTest {

    // ---- saved albums ----------------------------------------------------------------------

    @Test
    fun `saved albums are read out of their envelope`() {
        // /me/albums wraps each album in {added_at, album}, unlike every other album endpoint.
        val albums = SpotifyClient.parseSavedAlbums(
            """
            {"items":[
              {"added_at":"2024-01-01T00:00:00Z","album":{
                "id":"al1","uri":"spotify:album:al1","name":"Kid A",
                "artists":[{"name":"Radiohead"}],"release_date":"2000-10-02",
                "total_tracks":10,
                "images":[{"url":"https://i/large.jpg","width":640},
                          {"url":"https://i/small.jpg","width":64}]}}
            ]}
            """.trimIndent()
        )

        val album = albums.single()
        assertEquals("al1", album.id)
        assertEquals("Kid A", album.title)
        assertEquals("Radiohead", album.artist)
        assertEquals(2000, album.year)
        assertEquals(10, album.trackCount)
        // Widest first, and the big one is wanted: it is baked onto a case that fills a phone
        // screen, where an upscaled 64px thumbnail looks exactly as bad as it sounds.
        assertEquals("https://i/large.jpg", album.coverUrl)
    }

    @Test
    fun `release_date is read at whatever precision Spotify gives`() {
        // It is "1997", "1997-03" or "1997-03-24" depending on how much the label recorded.
        // Parsing the whole string as a number would throw on two of those three.
        fun yearOf(date: String) = SpotifyClient.parseSavedAlbums(
            """{"items":[{"album":{"id":"a","name":"n","artists":[],"release_date":"$date"}}]}"""
        ).single().year

        assertEquals(1997, yearOf("1997"))
        assertEquals(1997, yearOf("1997-03"))
        assertEquals(1997, yearOf("1997-03-24"))
        assertNull(yearOf(""))
    }

    @Test
    fun `several credited artists are joined rather than dropped`() {
        val album = SpotifyClient.parseSavedAlbums(
            """
            {"items":[{"album":{"id":"a","name":"Watch The Throne",
              "artists":[{"name":"JAY-Z"},{"name":"Kanye West"}],"release_date":"2011"}}]}
            """.trimIndent()
        ).single()

        assertEquals("JAY-Z, Kanye West", album.artist)
    }

    @Test
    fun `an album with no cover is null rather than blank`() {
        val album = SpotifyClient.parseSavedAlbums(
            """{"items":[{"album":{"id":"a","name":"n","artists":[],"images":[]}}]}"""
        ).single()
        assertNull(album.coverUrl)
    }

    @Test
    fun `an empty library parses to nothing rather than throwing`() {
        assertTrue(SpotifyClient.parseSavedAlbums("""{"items":[]}""").isEmpty())
        assertTrue(SpotifyClient.parseSavedAlbums("""{}""").isEmpty())
    }

    // ---- tracks ----------------------------------------------------------------------------

    @Test
    fun `tracks keep their numbering and duration`() {
        val tracks = SpotifyClient.parseTracks(
            """
            {"items":[
              {"id":"t1","uri":"spotify:track:t1","name":"Everything In Its Right Place",
               "track_number":1,"disc_number":1,"duration_ms":251000,
               "artists":[{"name":"Radiohead"}]}
            ]}
            """.trimIndent()
        )

        val track = tracks.single()
        assertEquals("t1", track.id)
        assertEquals("spotify:track:t1", track.uri)
        assertEquals(1, track.trackNumber)
        assertEquals(1, track.discNumber)
        // Spotify reports milliseconds already — unlike Subsonic, which reports seconds. Getting
        // these two the same way round is why both have a test saying so.
        assertEquals(251_000L, track.durationMs)
    }

    @Test
    fun `a track with no artists is null rather than an empty name`() {
        val track = SpotifyClient.parseTracks(
            """{"items":[{"id":"t","name":"x","artists":[],"duration_ms":1000}]}"""
        ).single()
        assertNull(track.artist)
    }

    // ---- playback state --------------------------------------------------------------------

    @Test
    fun `playback state is read`() {
        val playback = SpotifyClient.parsePlayback(
            """
            {"is_playing":true,"progress_ms":45000,
             "device":{"name":"Galaxy S25 Ultra","is_active":true},
             "item":{"id":"t1","uri":"spotify:track:t1","duration_ms":251000}}
            """.trimIndent()
        )!!

        assertTrue(playback.isPlaying)
        assertEquals(45_000L, playback.positionMs)
        assertEquals(251_000L, playback.durationMs)
        assertEquals("t1", playback.trackId)
        assertEquals("Galaxy S25 Ultra", playback.deviceName)
    }

    @Test
    fun `devices are read, and a restricted one is still reported`() {
        val devices = SpotifyClient.parseDevices(
            """
            {"devices":[
              {"id":"d1","name":"Phone","type":"Smartphone","is_active":true,"is_restricted":false},
              {"id":"d2","name":"TV","type":"TV","is_active":false,"is_restricted":true}
            ]}
            """.trimIndent()
        )

        assertEquals(2, devices.size)
        assertTrue(devices[0].isActive)
        // Reported here and filtered by the player: a restricted device accepts no commands, and
        // silently choosing one is the most confusing failure available.
        assertTrue(devices[1].isRestricted)
    }

    // ---- failures, which is where the user experience actually lives -----------------------

    @Test
    fun `a 401 means sign in again`() {
        assertEquals(
            SpotifyFailure.NeedsLogin,
            SpotifyClient.failureFor(401, """{"error":{"status":401,"message":"token expired"}}""")
        )
    }

    @Test
    fun `a free account is told it needs Premium, not that something broke`() {
        // Spotify signals this two ways depending on the endpoint, and both have to land on the
        // same message — this is a permanent state, and retrying will never fix it.
        assertEquals(
            SpotifyFailure.NeedsPremium,
            SpotifyClient.failureFor(403, """{"error":{"status":403,"reason":"PREMIUM_REQUIRED"}}""")
        )
        assertEquals(
            SpotifyFailure.NeedsPremium,
            SpotifyClient.failureFor(
                403,
                """{"error":{"status":403,"message":"Player command failed: Premium required"}}"""
            )
        )
    }

    @Test
    fun `no active device is its own case, because it is not really an error`() {
        // The Web API cannot create a device. This means "open Spotify somewhere", and saying
        // anything else sends the user looking for a fault that is not there.
        assertEquals(
            SpotifyFailure.NoDevice,
            SpotifyClient.failureFor(404, """{"error":{"status":404,"reason":"NO_ACTIVE_DEVICE"}}""")
        )
        assertEquals(SpotifyFailure.NoDevice, SpotifyClient.failureFor(404, ""))
    }

    @Test
    fun `rate limiting carries Spotify's own wait time`() {
        val failure = SpotifyClient.failureFor(429, "", retryAfterSeconds = 17)
        assertEquals(SpotifyFailure.RateLimited(17), failure)
    }

    @Test
    fun `an unknown failure still says whatever Spotify said`() {
        val failure = SpotifyClient.failureFor(500, """{"error":{"message":"Service unavailable"}}""")
        assertEquals(SpotifyFailure.Other("Service unavailable"), failure)
    }

    @Test
    fun `a failure with an unreadable body still produces something sayable`() {
        assertEquals(SpotifyFailure.Other("Spotify returned 502"), SpotifyClient.failureFor(502, "<html>"))
    }

    // ---- the profile, which decides whether any of it will work ----------------------------

    @Test
    fun `premium is detected, and anything else is not premium`() {
        assertTrue(SpotifyClient.parseProfile("""{"display_name":"Sam","product":"premium"}""").isPremium)
        assertTrue(!SpotifyClient.parseProfile("""{"display_name":"Sam","product":"free"}""").isPremium)
        assertTrue(!SpotifyClient.parseProfile("""{"display_name":"Sam"}""").isPremium)
    }
}
