package com.example.optimalx.data.model

import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey
import com.example.optimalx.data.sync.SyncGlobalIds

/**
 * Snapshot of a content resource at a meaningful moment (build, accepted Eidos proposal,
 * manual edit, or restore). Used by the DIFF_REVIEW pipeline to support
 * restore-to-here and to anchor unified-diff patches.
 *
 * Polymorphic over [sourceType] (currently `workshop_file`; `note` reserved for a
 * later phase) — no DB foreign key to file_references / notes because the source
 * shape may evolve; cleanup runs through `CheckpointRepository` in code.
 */
@Entity(
    tableName = "content_checkpoints",
    indices = [
        Index(value = ["sourceType", "sourceId", "sequence"]),
        Index(value = ["sourceType", "sourceId", "createdAt"]),
        Index(value = ["globalId"], unique = true),
    ],
)
data class ContentCheckpoint(
    @PrimaryKey(autoGenerate = true)
    val id: Long = 0,
    /** `workshop_file` | `note` (note path lands in a later phase). */
    val sourceType: String,
    /** `fileReferenceId` for workshop files; `subfolderId` for notes. */
    val sourceId: Long,
    /** Monotonic sequence per (sourceType, sourceId). 0 = baseline. */
    val sequence: Int,
    /** Full content body at this checkpoint. */
    val contentBlob: String,
    /** SHA-256 hex of [contentBlob] for quick equality checks. */
    val contentHash: String,
    /** `user` | `eidos` | `system` | `import`. */
    val author: String,
    /** Optional human-friendly label ("Design build", "Restored to seq 3", …). */
    val label: String? = null,
    /** Conversation that produced this checkpoint, if any. */
    val conversationId: Long? = null,
    val createdAt: Long = System.currentTimeMillis(),
    val globalId: String = SyncGlobalIds.newGlobalId(),
    val originDeviceId: String? = null,
)
