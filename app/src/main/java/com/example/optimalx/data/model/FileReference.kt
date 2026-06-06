package com.example.optimalx.data.model

import androidx.room.Entity
import androidx.room.ForeignKey
import androidx.room.Index
import androidx.room.PrimaryKey

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
    indices = [Index("subfolderId")],
)
data class FileReference(
    @PrimaryKey(autoGenerate = true)
    val id: Long = 0,
    val subfolderId: Long,
    val fileName: String,
    val fileType: String,
    val filePath: String,
    val createdAt: Long = System.currentTimeMillis(),
)
