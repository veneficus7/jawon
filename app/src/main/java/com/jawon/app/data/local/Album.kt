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
    val lastPlayedAt: Long? = null,

    /**
     * Which music server this record came from, or null for one that lives on this phone.
     *
     * **Null is the whole library as it stood before streaming existed**, which is why it is
     * the default rather than a sentinel like `"local"`: every row written by the importer
     * keeps meaning exactly what it meant, and every query that does not care about the
     * distinction carries on not caring.
     *
     * A string rather than a boolean because there will be more than one server one day, and a
     * boolean that has to become an id later is a second migration nobody needed.
     */
    val sourceId: String? = null,

    /**
     * This album's id **on that server**, which is not the same thing as [id].
     *
     * [id] is Jawon's own — a UUID minted here, and the key everything else in the database
     * hangs off, including the play history. Keeping them separate is what lets a re-sync
     * recognise an album it has already seen and *update* it, rather than inserting a second
     * copy and splitting its listening record in half. It is also what makes the server
     * replaceable: move to a different one and the wear on the case survives.
     */
    val remoteId: String? = null
)
