package com.example.optimalx.data.model

import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey

/**
 * One review session — usually one Eidos turn that touched one or more workshop files.
 * Items belong to a set so "Accept all" / "Reject all" can apply atomically.
 *
 * In v1 only workshop scopes exist (`scopeType = workshop_project`); `note` is reserved
 * for a later phase.
 */
@Entity(
    tableName = "pending_change_sets",
    indices = [
        Index(value = ["scopeType", "scopeId"]),
        Index(value = ["status"]),
        Index(value = ["conversationId"]),
    ],
)
data class PendingChangeSet(
    @PrimaryKey(autoGenerate = true)
    val id: Long = 0,
    /** `workshop_project` | `note` (note path deferred). */
    val scopeType: String,
    /** `subfolderId` for workshop_project; `subfolderId` for note. */
    val scopeId: Long,
    val conversationId: Long? = null,
    /** `open` | `accepted` | `rejected` | `partial`. */
    val status: String,
    val createdAt: Long = System.currentTimeMillis(),
    val updatedAt: Long = System.currentTimeMillis(),
)
