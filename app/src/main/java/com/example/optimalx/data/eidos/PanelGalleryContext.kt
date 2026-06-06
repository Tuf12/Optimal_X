package com.example.optimalx.data.eidos

import android.content.Context
import com.example.optimalx.OptimalXApplication
import com.example.optimalx.data.db.AppDatabase
import com.example.optimalx.data.panel.PanelReleaseStore

/**
 * System prompt block for [ConversationScopes.PANEL_GALLERY] — panel list / launch surface.
 */
object PanelGalleryContext {

    suspend fun buildPromptBlock(context: Context, database: AppDatabase): String {
        val app = context.applicationContext as? OptimalXApplication
        val parentId = app?.folderRepository?.getWorkshopParentId()
        val projects = if (parentId == null) {
            emptyList()
        } else {
            database.subfolderDao().getAllByParentOnce(parentId)
                .filter { it.deletedAt == null && !it.isSystemSubfolder }
                .sortedBy { it.name.lowercase() }
        }
        val complete = projects.count { PanelReleaseStore.hasRelease(context, it.id) }
        val draft = projects.size - complete
        val lines = projects.map { subfolder ->
            PanelProjectKnowledge.build(
                context = context,
                database = database,
                subfolderId = subfolder.id,
                mode = PanelProjectKnowledge.Mode.GALLERY_LIST_ITEM,
            )
        }
        val listBlock = if (lines.isEmpty()) {
            "(no panel projects yet — user can open Panel Workshop to create one)"
        } else {
            lines.joinToString("\n")
        }
        return buildString {
            appendLine("Panel Gallery — browse and launch custom panels")
            appendLine("Counts: $complete launchable (COMPLETE), $draft draft/in-progress")
            appendLine()
            appendLine("Projects:")
            appendLine(listBlock)
            appendLine()
            appendLine(PanelPlatformSpec.eidosPanelGalleryRules())
        }.trim()
    }
}
