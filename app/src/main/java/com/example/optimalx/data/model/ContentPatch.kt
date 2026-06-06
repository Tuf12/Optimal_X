package com.example.optimalx.data.model

import androidx.room.Entity
import androidx.room.ForeignKey
import androidx.room.Index
import androidx.room.PrimaryKey

/**
 * Forward unified-diff between two adjacent checkpoints of the same source. Lets the
 * timeline render compactly without storing every full snapshot.
 *
 * Patches are advisory — restore loads the target checkpoint's `contentBlob`
 * directly (O(1)). Patches drive the history UI delta display.
 */
@Entity(
    tableName = "content_patches",
    foreignKeys = [
        ForeignKey(
            entity = ContentCheckpoint::class,
            parentColumns = ["id"],
            childColumns = ["fromCheckpointId"],
            onDelete = ForeignKey.CASCADE,
        ),
        ForeignKey(
            entity = ContentCheckpoint::class,
            parentColumns = ["id"],
            childColumns = ["toCheckpointId"],
            onDelete = ForeignKey.CASCADE,
        ),
    ],
    indices = [
        Index(value = ["sourceType", "sourceId"]),
        Index(value = ["fromCheckpointId"]),
        Index(value = ["toCheckpointId"]),
    ],
)
data class ContentPatch(
    @PrimaryKey(autoGenerate = true)
    val id: Long = 0,
    /** Mirrors the linked checkpoints' sourceType — denormalized for fast lookups. */
    val sourceType: String,
    /** Mirrors the linked checkpoints' sourceId. */
    val sourceId: Long,
    val fromCheckpointId: Long,
    val toCheckpointId: Long,
    /** Unified diff text from fromCheckpoint → toCheckpoint. */
    val unifiedDiff: String,
    val createdAt: Long = System.currentTimeMillis(),
)
