package com.example.optimalx.ui.navigation

/**
 * Lets the visible editor surface block chat navigation when there are unsaved edits.
 * Only one screen should register at a time (the current route).
 */
object NavigationLeaveGuard {
    private var confirmHandler: (suspend () -> Boolean)? = null

    fun register(confirmLeave: suspend () -> Boolean) {
        confirmHandler = confirmLeave
    }

    fun unregister(confirmLeave: suspend () -> Boolean) {
        if (confirmHandler === confirmLeave) {
            confirmHandler = null
        }
    }

    /** Drop any stale handler (e.g. when opening Eidos chat after an editor dispose race). */
    fun clear() {
        confirmHandler = null
    }

    suspend fun confirmLeave(): Boolean = confirmHandler?.invoke() ?: true

    suspend fun confirmLeaveWithDialog(
        hasUnsavedChanges: Boolean,
        requestConfirm: suspend (title: String, message: String) -> Boolean,
    ): Boolean {
        if (!hasUnsavedChanges) return true
        return requestConfirm(
            "Discard unsaved changes?",
            "You have unsaved edits. Leave without saving?",
        )
    }
}

object WorkshopNavigationState {
    var pendingSelectPath: String? = null
}
