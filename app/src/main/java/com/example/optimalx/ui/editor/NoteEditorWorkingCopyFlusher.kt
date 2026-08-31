package com.example.optimalx.ui.editor

import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.withContext

/**
 * Non-cancellable flush of the editor buffer to the working copy (Room / preferences).
 * Used on lifecycle exit and back navigation — not while typing (no debounce).
 */
class NoteEditorWorkingCopyFlusher(
    private val onSaving: () -> Unit = {},
    private val onSaved: () -> Unit = {},
    private val onPersist: suspend (String) -> Unit,
) {
    suspend fun flush(content: String, shouldPersist: Boolean) {
        if (!shouldPersist) return
        withContext(NonCancellable) {
            onSaving()
            onPersist(content)
            onSaved()
        }
    }
}
