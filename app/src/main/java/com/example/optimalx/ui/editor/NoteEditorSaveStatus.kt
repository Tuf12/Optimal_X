package com.example.optimalx.ui.editor

enum class NoteEditorSaveStatus {
    Saved,
    Unsaved,
    Saving,
}

fun NoteEditorSaveStatus.toStatusLabel(isDirtyVsHead: Boolean = false): String? = when (this) {
    NoteEditorSaveStatus.Saved -> if (isDirtyVsHead) "Uncommitted" else "Saved"
    NoteEditorSaveStatus.Unsaved -> "Unsaved"
    NoteEditorSaveStatus.Saving -> "Saving…"
}
