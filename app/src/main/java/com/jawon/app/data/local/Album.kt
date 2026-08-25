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
    val addedAt: Long
)
