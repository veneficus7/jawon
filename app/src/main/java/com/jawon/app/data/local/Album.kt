package com.jawon.app.data.local

import androidx.room.Entity
import androidx.room.PrimaryKey

@Entity
data class Album(
    @PrimaryKey val id: String,
    val shelfId: String,
    val title: String,
    val artist: String,
    val year: Int?,
    val caseCoverUri: String?,
    val discFaceUri: String?,
    val spineHue: Float,
    val slotIndex: Int,
    val playCount: Int,
    val addedAt: Long,

    /**
     * Where this record was up to when you last closed it — queue index and offset into that
     * track.
     *
     * A CD player that forgets its place is a CD player nobody trusts, and this room is
     * pretending to be a physical object: take a record off, put it back on, and it should be
     * where you left it. Stored on the album rather than in a preference so it survives the
     * process, and so every album remembers its own place rather than there being one global
     * "resume point" that the last-played album overwrites.
     */
    val lastTrackIndex: Int = 0,
    val lastPositionMs: Long = 0L,

    /** Null until the album has actually been played once. Drives "recently played". */
    val lastPlayedAt: Long? = null
)
