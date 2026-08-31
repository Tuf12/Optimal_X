package com.example.optimalx.data.semantic

/**
 * Maps coalesced sync reason strings (from saves, folder ops, chat) to incremental index work.
 * Full rebuild only on [SemanticSyncAction.FullBootstrap].
 */
sealed class SemanticSyncAction {
    data object FullBootstrap : SemanticSyncAction()
    data object NoOp : SemanticSyncAction()
    data class IndexNote(val subfolderId: Long) : SemanticSyncAction()
    data class IndexSubfolder(val subfolderId: Long) : SemanticSyncAction()
    data class IndexParentFolder(val parentFolderId: Long) : SemanticSyncAction()
    data class IndexConversation(val conversationId: Long) : SemanticSyncAction()
    data class DeleteFile(val fileId: Long) : SemanticSyncAction()
    data class DeleteSubfolder(val subfolderId: Long) : SemanticSyncAction()
    data class DeleteParentFolder(val parentFolderId: Long) : SemanticSyncAction()
    data class DeleteConversation(val conversationId: Long) : SemanticSyncAction()
}

object SemanticSyncReason {
    private val fullBootstrapPrefixes = setOf(
        "startup_seed",
        "manual_rebuild",
    )

    fun parse(reason: String): SemanticSyncAction {
        if (reason in fullBootstrapPrefixes) return SemanticSyncAction.FullBootstrap

        val colon = reason.indexOf(':')
        if (colon <= 0) return SemanticSyncAction.NoOp

        val prefix = reason.substring(0, colon)
        val id = reason.substring(colon + 1).toLongOrNull() ?: return SemanticSyncAction.NoOp

        return when (prefix) {
            "save_note_content",
            "toggle_ai_blind",
            "save_quick_note_full_content" -> SemanticSyncAction.IndexNote(id)

            "toggle_ai_lock" -> SemanticSyncAction.NoOp

            "insert_file_reference",
            "share_import_file",
            "create_workshop_project",
            "create_subfolder",
            "create_quick_notes_day_subfolder",
            "workshop_editor_open" -> SemanticSyncAction.IndexSubfolder(id)

            "delete_file_reference",
            "soft_delete_file_reference",
            "permanently_delete_file_reference" -> SemanticSyncAction.DeleteFile(id)

            "create_parent_folder",
            "rename_parent_folder",
            "restore_parent_folder" -> SemanticSyncAction.IndexParentFolder(id)

            "soft_delete_parent_folder",
            "delete_parent_folder" -> SemanticSyncAction.DeleteParentFolder(id)

            "rename_subfolder",
            "restore_subfolder",
            "move_subfolder" -> SemanticSyncAction.IndexSubfolder(id)

            "soft_delete_subfolder",
            "delete_subfolder" -> SemanticSyncAction.DeleteSubfolder(id)

            "conversation_reply_written",
            "conversation_stopped_reply",
            "conversation_auto_retitle",
            "conversation_created",
            "rename_conversation",
            "background_conversation_reply_written",
            "widget_general_reply",
            "widget_quick_note_reply",
            "widget_conversation_auto_retitle",
            "widget_general_conversation_created",
            "widget_quick_note_conversation_created",
            "move_conversation_viewed_directory",
            "move_active_conversation_current_scope" -> SemanticSyncAction.IndexConversation(id)

            "web_conversation_deleted" -> SemanticSyncAction.DeleteConversation(id)

            else -> SemanticSyncAction.NoOp
        }
    }
}
