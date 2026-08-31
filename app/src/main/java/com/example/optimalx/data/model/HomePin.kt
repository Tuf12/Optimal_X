package com.example.optimalx.data.model

import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey
import com.example.optimalx.data.sync.SyncGlobalIds

@Entity(
    tableName = "home_pins",
    indices = [
        Index(value = ["pinType", "targetId"], unique = true),
        Index(value = ["globalId"], unique = true),
    ],
)
data class HomePin(
    @PrimaryKey(autoGenerate = true)
    val id: Long = 0,
    /** `parent`, `subfolder`, or `panel` — see [com.example.optimalx.ui.folders.HomePinType]. */
    val pinType: String,
    val targetId: Long,
    val displayName: String,
    val sortOrder: Int,
    val createdAt: Long = System.currentTimeMillis(),
    val globalId: String = SyncGlobalIds.newGlobalId(),
    val originDeviceId: String? = null,
)
