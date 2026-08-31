package com.example.optimalx.data.model

import androidx.room.Entity
import androidx.room.ForeignKey
import androidx.room.Index
import androidx.room.PrimaryKey
import com.example.optimalx.data.sync.SyncGlobalIds

@Entity(
    tableName = "subfolders",
    foreignKeys = [
        ForeignKey(
            entity = ParentFolder::class,
            parentColumns = ["id"],
            childColumns = ["parentFolderId"],
            onDelete = ForeignKey.CASCADE,
        )
    ],
    indices = [
        Index("parentFolderId"),
        Index(value = ["globalId"], unique = true),
    ],
)
data class Subfolder(
    @PrimaryKey(autoGenerate = true)
    val id: Long = 0,
    val parentFolderId: Long,
    val name: String,
    val createdAt: Long = System.currentTimeMillis(),
    val updatedAt: Long = System.currentTimeMillis(),
    val sortOrder: Int = 0,
    val deletedAt: Long? = null,
    val isSystemSubfolder: Boolean = false,
    /** User-generated workshop project summary for Eidos (stable until regenerated). */
    val projectSummary: String? = null,
    val projectSummaryUpdatedAt: Long? = null,
    /** `mobile` | `desktop` — meaningful for Panel Workshop projects. */
    val targetPlatform: String = "mobile",
    val globalId: String = SyncGlobalIds.newGlobalId(),
    val originDeviceId: String? = null,
)
