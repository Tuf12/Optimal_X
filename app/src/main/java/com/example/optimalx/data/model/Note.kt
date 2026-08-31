package com.example.optimalx.data.model

import androidx.room.Entity
import androidx.room.ForeignKey
import androidx.room.Index
import androidx.room.PrimaryKey
import com.example.optimalx.data.sync.SyncContentHash
import com.example.optimalx.data.sync.SyncGlobalIds

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
    indices = [
        Index("subfolderId"),
        Index(value = ["globalId"], unique = true),
    ],
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
    /**
     * Two-section Eidos context: [Memory] curated bullets + [Content] auto body digest.
     * Serialized by [com.example.optimalx.data.eidos.NoteSummaryCodec].
     */
    val summary: String? = null,
    /**
     * @deprecated Legacy chunked body summaries — migrated into [summary] [Content] on v24.
     * Column retained for rollback; do not write new values.
     */
    val summaryChunksJson: String? = null,
    val summaryUpdatedAt: Long? = null,
    /** Char offset in normalized note body through which [Content] digest has been folded. */
    val summaryContentWatermark: Int? = null,
    val globalId: String = SyncGlobalIds.newGlobalId(),
    val originDeviceId: String? = null,
    val contentHash: String = SyncContentHash.noteContentHash(content),
)
