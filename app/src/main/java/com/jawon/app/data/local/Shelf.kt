package com.jawon.app.data.local

import androidx.room.Entity
import androidx.room.PrimaryKey

@Entity
data class Shelf(
    @PrimaryKey val id: String,
    val name: String,
    val order: Int,
    val accentHue: Float,
    val createdAt: Long
)
