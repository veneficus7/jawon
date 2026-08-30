package com.jawon.app.data.local

import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey

/**
 * One stretch of listening: a track, when it started, and how much of it was actually heard.
 *
 * **This table exists before anything reads it, on purpose.** Listening history is the one
 * kind of data in this app that cannot be reconstructed after the fact — a cover can be
 * re-fetched and a title retyped, but a play that was never recorded is gone. `Album.playCount`
 * was a dead column written once as zero, so until now the app has been discarding this
 * silently. The stats screens are not built yet; the rows they will need have to start
 * accumulating from the first build real users run, not from the build that finally shows
 * them.
 *
 * Rows are written by [com.jawon.app.data.playback.PlaybackRepository] rather than by a
 * ViewModel, because playback outlives the UI: the service keeps going with the app closed,
 * and history recorded from a screen would quietly miss exactly the listening people do most.
 *
 * `msPlayed` is time-in-track, not wall time — pausing does not inflate it, and neither does
 * scrubbing back over a section already heard. It is deliberately not "did they finish it":
 * what counts as a play is a question for the stats screens, and answering it here would bake
 * one definition into the data and throw the rest away.
 */
@Entity(indices = [Index("albumId"), Index("startedAt")])
data class PlayEvent(
    @PrimaryKey(autoGenerate = true) val id: Long = 0L,
    val albumId: String,
    val trackId: String,
    /** Epoch millis when this stretch of listening began. */
    val startedAt: Long,
    val msPlayed: Long
)
