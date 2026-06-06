package com.example.optimalx.data.model

import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey

@Entity(
    tableName = "panel_state",
    indices = [
        Index(value = ["workshopSubfolderId", "scopeKey"], unique = true),
    ],
)
data class PanelState(
    @PrimaryKey(autoGenerate = true)
    val id: Long = 0,
    val workshopSubfolderId: Long,
    /** `global` for gallery runner; `subfolder:{hostSubfolderId}` for editor custom tabs. */
    val scopeKey: String,
    val stateJson: String,
    val updatedAt: Long = System.currentTimeMillis(),
)
