package com.example.optimalx.data.model

import androidx.room.Entity
import androidx.room.ForeignKey
import androidx.room.Index
import androidx.room.PrimaryKey

@Entity(
    tableName = "notes",
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
data class Note(
    @PrimaryKey(autoGenerate = true)
    val id: Long = 0,
    val subfolderId: Long,
    val content: String = "",
    val createdAt: Long = System.currentTimeMillis(),
    val updatedAt: Long = System.currentTimeMillis(),
    val deletedAt: Long? = null,
    val aiLocked: Boolean = false,
    val aiBlind: Boolean = false,
    /** User-generated Eidos context summary (stable until regenerated). */
    val summary: String? = null,
    /** JSON array of [ContentSummaryChunk] for very long notes. */
    val summaryChunksJson: String? = null,
    val summaryUpdatedAt: Long? = null,
)
