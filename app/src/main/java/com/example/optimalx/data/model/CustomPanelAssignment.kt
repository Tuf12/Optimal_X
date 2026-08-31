package com.example.optimalx.data.model

import androidx.room.Entity
import androidx.room.ForeignKey
import androidx.room.Index
import androidx.room.PrimaryKey
import com.example.optimalx.data.sync.SyncGlobalIds

@Entity(
    tableName = "custom_panel_assignments",
    foreignKeys = [
        ForeignKey(
            entity = Subfolder::class,
            parentColumns = ["id"],
            childColumns = ["workshopSubfolderId"],
            onDelete = ForeignKey.CASCADE,
        ),
        ForeignKey(
            entity = Subfolder::class,
            parentColumns = ["id"],
            childColumns = ["targetSubfolderId"],
            onDelete = ForeignKey.CASCADE,
        ),
    ],
    indices = [
        Index("workshopSubfolderId"),
        Index("targetSubfolderId"),
        Index(value = ["globalId"], unique = true),
    ],
)
data class CustomPanelAssignment(
    @PrimaryKey(autoGenerate = true)
    val id: Long = 0,
    val workshopSubfolderId: Long,
    val targetSubfolderId: Long,
    val panelTitle: String,
    val createdAt: Long = System.currentTimeMillis(),
    val globalId: String = SyncGlobalIds.newGlobalId(),
    val originDeviceId: String? = null,
)
