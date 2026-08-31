package com.example.optimalx.data.eidos

import com.example.optimalx.data.db.AppDatabase
import com.example.optimalx.data.model.ConversationScopes

data class EidosApiTraceDirectory(
    val key: String,
    val label: String,
)

/**
 * Maps chat scope + folder ids to a stable directory bucket for the API trace inbox.
 */
object EidosApiTraceDirectoryResolver {

    suspend fun resolve(
        database: AppDatabase,
        scopeType: String?,
        subfolderId: Long?,
        parentFolderId: Long?,
    ): EidosApiTraceDirectory {
        val scope = scopeType?.trim().orEmpty().ifBlank { inferScope(subfolderId, parentFolderId) }
        return when (scope) {
            ConversationScopes.GENERAL -> EidosApiTraceDirectory("general", "General")
            ConversationScopes.PARENT -> {
                val id = parentFolderId ?: 0L
                val name = database.parentFolderDao().getById(id)?.name ?: "Parent $id"
                EidosApiTraceDirectory("parent:$id", "Parent · $name")
            }
            ConversationScopes.SUBFOLDER, ConversationScopes.QUICK_NOTES_DAY -> {
                val id = subfolderId ?: 0L
                val name = database.subfolderDao().getById(id)?.name ?: "Subfolder $id"
                EidosApiTraceDirectory("subfolder:$id", "Subfolder · $name")
            }
            ConversationScopes.WEB_EDITOR -> {
                val id = subfolderId ?: 0L
                val name = database.subfolderDao().getById(id)?.name ?: "Subfolder $id"
                EidosApiTraceDirectory("web_editor:$id", "Web · $name")
            }
            ConversationScopes.WEB_WIDGET ->
                EidosApiTraceDirectory("web_widget", "Widget Web")
            ConversationScopes.PANEL_WORKSHOP -> {
                val id = subfolderId ?: 0L
                val name = database.subfolderDao().getById(id)?.name ?: "Workshop $id"
                EidosApiTraceDirectory("panel_workshop:$id", "Workshop · $name")
            }
            ConversationScopes.PANEL_RUNNER -> {
                val id = subfolderId ?: 0L
                val name = database.subfolderDao().getById(id)?.name ?: "Panel $id"
                EidosApiTraceDirectory("panel_runner:$id", "Panel Runner · $name")
            }
            ConversationScopes.PANEL_GALLERY ->
                EidosApiTraceDirectory("panel_gallery", "Panel Gallery")
            ConversationScopes.DUMP_EDIT ->
                EidosApiTraceDirectory("dump_edit", "DumpEdit")
            ConversationScopes.IMAGE_STUDIO -> {
                val id = subfolderId ?: 0L
                val name = database.subfolderDao().getById(id)?.name ?: "Subfolder $id"
                EidosApiTraceDirectory("image_studio:$id", "Image Studio · $name")
            }
            ConversationScopes.QUICK_NOTES_ROOT -> {
                val id = parentFolderId ?: 0L
                EidosApiTraceDirectory("quick_notes_root:$id", "Quick Notes Root")
            }
            else -> {
                val id = subfolderId ?: parentFolderId
                if (id != null) {
                    EidosApiTraceDirectory("scope:$scope:$id", scope.replace('_', ' '))
                } else {
                    EidosApiTraceDirectory("scope:$scope", scope.replace('_', ' '))
                }
            }
        }
    }

    private fun inferScope(subfolderId: Long?, parentFolderId: Long?): String = when {
        subfolderId != null -> ConversationScopes.SUBFOLDER
        parentFolderId != null -> ConversationScopes.PARENT
        else -> ConversationScopes.GENERAL
    }
}
