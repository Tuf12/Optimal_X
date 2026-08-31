package com.example.optimalx.ui.editor

/**
 * Pure decisions for note editor ↔ Room sync during Eidos tool writes.
 * Keeps reload/save guards testable without a full [EditorViewModel].
 */
object NoteEditorSyncPolicy {

    /** Reload from DB after an external writer when the user has not started an edit session. */
    fun shouldReloadExternalWrite(
        userHasEdited: Boolean,
        dbUpdatedAt: Long,
        lastLoadedUpdatedAt: Long,
        dbContent: String,
        lastPersistedContent: String?,
    ): Boolean {
        if (userHasEdited) return false
        if (dbUpdatedAt <= lastLoadedUpdatedAt) return false
        if (dbContent == lastPersistedContent) return false
        return true
    }

    /**
     * Persist the working copy when markdown differs from the last Room row.
     * [userHasEdited] is tracked separately to block external DB reload during an edit session.
     */
    fun shouldPersistWorkingCopy(content: String, lastPersistedContent: String?): Boolean =
        content != lastPersistedContent

    /**
     * Persist on lifecycle when content differs from the last persisted row.
     */
    fun shouldPersistOnLifecycle(userHasEdited: Boolean, hasPendingChanges: Boolean): Boolean =
        hasPendingChanges

    fun shouldTrackEditorSnapshots(isEditMode: Boolean): Boolean = isEditMode

    /**
     * Skip a lifecycle/dispose save when Room was updated externally (Diff Review accept,
     * Eidos auto-apply) after this editor buffer was last synced.
     */
    fun shouldSkipPersistClobberingExternalWrite(
        dbUpdatedAt: Long,
        lastLoadedUpdatedAt: Long,
        persistContent: String,
        dbContent: String,
    ): Boolean =
        dbUpdatedAt > lastLoadedUpdatedAt && persistContent != dbContent
}
