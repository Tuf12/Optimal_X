package com.example.optimalx.data.model

import androidx.room.Entity
import androidx.room.ForeignKey
import androidx.room.Index
import androidx.room.PrimaryKey
import com.example.optimalx.data.sync.SyncGlobalIds

@Entity(
    tableName = "file_references",
    foreignKeys = [
        ForeignKey(
            entity = Subfolder::class,
            parentColumns = ["id"],
            childColumns = ["subfolderId"],
            onDelete = ForeignKey.CASCADE,
        )
    ],
    indices = [
        Index("subfolderId"),
        Index(value = ["globalId"], unique = true),
    ],
)
data class FileReference(
    @PrimaryKey(autoGenerate = true)
    val id: Long = 0,
    val subfolderId: Long,
    val fileName: String,
    val fileType: String,
    val filePath: String,
    val createdAt: Long = System.currentTimeMillis(),
    val globalId: String = SyncGlobalIds.newGlobalId(),
    val originDeviceId: String? = null,
    val metadataJson: String? = null,
    val deletedAt: Long? = null,
)
