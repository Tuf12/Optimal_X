package com.example.optimalx.data.model

object ConversationScopes {
    const val GENERAL = "general"
    const val PARENT = "parent"
    const val SUBFOLDER = "subfolder"
    const val QUICK_NOTES_ROOT = "quick_notes_root"
    const val QUICK_NOTES_DAY = "quick_notes_day"
    const val WEB_EDITOR = "web_editor"
    const val WEB_WIDGET = "web_widget"
    const val PANEL_WORKSHOP = "panel_workshop"
    const val PANEL_GALLERY = "panel_gallery"
    const val PANEL_RUNNER = "panel_runner"
    const val DUMP_EDIT = "dump_edit"

    val MAIN_CHAT_SCOPE_TYPES = listOf(
        GENERAL,
        PARENT,
        SUBFOLDER,
        QUICK_NOTES_ROOT,
        QUICK_NOTES_DAY,
        PANEL_WORKSHOP,
    )

    fun isWebScope(scopeType: String): Boolean =
        scopeType == WEB_EDITOR || scopeType == WEB_WIDGET
}
