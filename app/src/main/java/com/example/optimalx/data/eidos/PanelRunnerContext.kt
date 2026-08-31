package com.example.optimalx.data.eidos

import android.content.Context
import com.example.optimalx.data.db.AppDatabase
import com.example.optimalx.data.model.Subfolder
import com.example.optimalx.data.preferences.WorkshopProjectPreferences

/**
 * System prompt block for [ConversationScopes.PANEL_RUNNER] — runtime panel use (not Workshop build).
 */
object PanelRunnerContext {

    suspend fun buildPromptBlock(
        context: Context,
        database: AppDatabase,
        subfolder: Subfolder,
    ): String {
        val phase = WorkshopProjectPreferences.getProjectPhase(context, subfolder.id)
        val knowledge = PanelProjectKnowledge.build(
            context = context,
            database = database,
            subfolderId = subfolder.id,
            mode = PanelProjectKnowledge.Mode.RUNNER,
            panelStateScopeKey = PanelStateScope.GLOBAL,
        )
        return buildString {
            appendLine("Panel Runner — user is using panel \"${subfolder.name}\" full screen (not building).")
            appendLine("workshopSubfolderId=${subfolder.id}, phase=${phase.displayName}.")
            appendLine("Chat scope: panel_runner (separate from panel_workshop build threads).")
            appendLine()
            append(knowledge)
            appendLine()
            appendLine()
            append(PanelPlatformSpec.eidosRunnerInstructions(subfolder.id))
        }.trim()
    }
}
