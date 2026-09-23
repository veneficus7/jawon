package com.jawon.app.data.local

import androidx.room.Entity
import androidx.room.PrimaryKey

@Entity
data class Shelf(
    @PrimaryKey val id: String,
    val name: String,
    val order: Int,
    /**
     * **Read by nothing since 2026-09-21.** It coloured the shelf's underlight, and then only the
     * label plate once the underlights went (2026-09-13); the owner had the picker removed as a
     * choice with no visible purpose. Kept rather than dropped because dropping a column costs a
     * migration, and every backup written so far carries it.
     */
    val accentHue: Float,
    val createdAt: Long
)
