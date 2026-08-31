package com.example.optimalx.data.revision

import com.example.optimalx.data.model.FileReference

// ── String constants used as DB tags ─────────────────────────────────────────

/** Workshop file with an existing `FileReference` row (sourceId = fileReferenceId). */
const val SOURCE_TYPE_WORKSHOP_FILE: String = "workshop_file"

/**
 * A workshop file that does not yet exist on disk. `sourceId` is the owning
 * subfolderId; the actual fileName lives on the `PendingChangeItem` row. On
 * accept the `DirectWriteApplier` creates the FileReference + disk file and
 * baselines the new file under [SOURCE_TYPE_WORKSHOP_FILE].
 */
const val SOURCE_TYPE_WORKSHOP_NEW_FILE: String = "workshop_new_file"

/**
 * Note body checkpoint (one note per subfolder, so `sourceId = subfolderId`).
 * Pending-review wired via [PendingChangeService.proposeNote] and [NoteWriteRouter].
 */
const val SOURCE_TYPE_NOTE: String = "note"

/** Scope of a pending change set targeting a subfolder note. */
const val SCOPE_SUBFOLDER: String = "subfolder"

/** Scope of a pending change set targeting a workshop project. */
const val SCOPE_WORKSHOP_PROJECT: String = "workshop_project"

const val CHECKPOINT_AUTHOR_USER: String = "user"
const val CHECKPOINT_AUTHOR_EIDOS: String = "eidos"
const val CHECKPOINT_AUTHOR_SYSTEM: String = "system"

const val CHECKPOINT_LABEL_COMMITTED_EDITS: String = "Committed edits"
const val CHECKPOINT_LABEL_BEFORE_EIDOS: String = "Before Eidos edit"

const val PENDING_SET_STATUS_OPEN: String = "open"
const val PENDING_SET_STATUS_ACCEPTED: String = "accepted"
const val PENDING_SET_STATUS_REJECTED: String = "rejected"
const val PENDING_SET_STATUS_PARTIAL: String = "partial"

const val PENDING_ITEM_STATUS_PENDING: String = "pending"
const val PENDING_ITEM_STATUS_ACCEPTED: String = "accepted"
const val PENDING_ITEM_STATUS_REJECTED: String = "rejected"

// ── Indexer hook ─────────────────────────────────────────────────────────────

/**
 * Reindexes a workshop file's body into the semantic store after a write.
 *
 * Production wiring composes `SemanticChunkBuilder.indexFile(SemanticIndexer, ...)`.
 * Tests can pass [NoOp] to avoid heavy embedding work.
 */
fun interface WorkshopFileIndexer {
    suspend fun reindex(ref: FileReference, content: String)

    companion object {
        val NoOp: WorkshopFileIndexer = WorkshopFileIndexer { _, _ -> }
    }
}

/**
 * Reindexes a note into the semantic store after a write.
 *
 * Production wiring composes `SemanticChunkBuilder.indexNote(SemanticIndexer, ...)`.
 */
fun interface NoteIndexer {
    suspend fun reindex(subfolderId: Long)

    companion object {
        val NoOp: NoteIndexer = NoteIndexer { }
    }
}

// ── Result sealed types ──────────────────────────────────────────────────────

sealed interface ProposeResult {
    data class Queued(
        val setId: Long,
        val itemId: Long,
        /** Pending items in the open set after this propose (one per file, not per tool call). */
        val pendingCountInSet: Int,
        /** True when an earlier pending proposal for the same file was replaced. */
        val supersededPriorItem: Boolean,
    ) : ProposeResult
    data class NoChange(val reason: String) : ProposeResult
    data class Failed(val message: String) : ProposeResult
}

sealed interface AcceptResult {
    /** [checkpointId] is the new checkpoint capturing the accepted state. */
    data class Applied(val itemId: Long, val checkpointId: Long, val fileReferenceId: Long) : AcceptResult

    /**
     * Working copy hash no longer matches the proposal baseline —
     * the user (or another process) modified the content since Eidos proposed.
     * Diff Review should offer Dismiss (clear queue, keep current) vs Leave it
     * (return to the review list; Reject may undo workshop write-through).
     */
    data class ConcurrentChange(
        val itemId: Long,
        val expectedHash: String,
        val actualHash: String,
    ) : AcceptResult

    data class Failed(val itemId: Long, val message: String) : AcceptResult
}

sealed interface RejectResult {
    data object Ok : RejectResult
    data class Failed(val message: String) : RejectResult
}

sealed interface WriteResult {
    data class Applied(val fileReferenceId: Long, val checkpointId: Long) : WriteResult
    data class Failed(val message: String) : WriteResult
}
