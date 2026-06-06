package com.example.optimalx.data.model

import androidx.room.Entity
import androidx.room.ForeignKey
import androidx.room.Index
import androidx.room.PrimaryKey

/**
 * A single proposed change inside a [PendingChangeSet] — one file or one note.
 * Carries the full proposed body so Accept is a single write and the diff is
 * computed once at proposal time.
 */
@Entity(
    tableName = "pending_change_items",
    foreignKeys = [
        ForeignKey(
            entity = PendingChangeSet::class,
            parentColumns = ["id"],
            childColumns = ["changeSetId"],
            onDelete = ForeignKey.CASCADE,
        ),
    ],
    indices = [
        Index(value = ["changeSetId"]),
        Index(value = ["sourceType", "sourceId"]),
        Index(value = ["status"]),
    ],
)
data class PendingChangeItem(
    @PrimaryKey(autoGenerate = true)
    val id: Long = 0,
    val changeSetId: Long,
    /** `workshop_file` | `note` (note path deferred). */
    val sourceType: String,
    /** `fileReferenceId` for workshop files; `subfolderId` for notes. */
    val sourceId: Long,
    /**
     * Checkpoint id representing the working-copy state at proposal time. Used by the
     * concurrency guard on Accept to detect "working copy changed since proposal".
     * Null when no prior checkpoint exists (e.g. new file proposal — diff vs empty).
     */
    val baseCheckpointId: Long? = null,
    /** Full proposed body — written to disk verbatim on Accept. */
    val proposedContent: String,
    /** SHA-256 hex of [proposedContent] for quick equality checks. */
    val proposedHash: String,
    /** Precomputed unified diff (baseline → proposed) for the review UI. */
    val unifiedDiff: String,
    /** `pending` | `accepted` | `rejected`. */
    val status: String,
    /** Display name for the review list (file name; null for notes). */
    val fileName: String? = null,
    /**
     * Marks newly-created files so the review UI shows them as "added" instead of "modified".
     * For `workshop_create_file` proposals this is true.
     */
    val isNewFile: Boolean = false,
    val createdAt: Long = System.currentTimeMillis(),
    val updatedAt: Long = System.currentTimeMillis(),
)
