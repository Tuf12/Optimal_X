package com.example.optimalx.data.eidos

import com.example.optimalx.data.db.AppDatabase
import com.example.optimalx.data.model.CustomPanelAssignment

/**
 * Host subfolder a workshop panel project serves ([CustomPanelAssignment]).
 * Used for workshop Chat cross-source retrieval (Phase 4.3).
 */
data class WorkshopHostLink(
    val targetSubfolderId: Long,
    val targetSubfolderName: String,
    val parentFolderId: Long,
    val parentFolderName: String?,
    val panelTitle: String,
)

object WorkshopHostLinkContext {

    suspend fun resolve(
        database: AppDatabase,
        workshopSubfolderId: Long,
    ): WorkshopHostLink? {
        val assignment = database.customPanelAssignmentDao()
            .getByWorkshopSubfolder(workshopSubfolderId)
            .firstOrNull()
            ?: return null
        return resolve(database, assignment)
    }

    suspend fun resolve(
        database: AppDatabase,
        assignment: CustomPanelAssignment,
    ): WorkshopHostLink? {
        val target = database.subfolderDao().getById(assignment.targetSubfolderId)
            ?: return null
        if (target.deletedAt != null) return null
        val parent = database.parentFolderDao().getById(target.parentFolderId)
        return WorkshopHostLink(
            targetSubfolderId = target.id,
            targetSubfolderName = target.name,
            parentFolderId = target.parentFolderId,
            parentFolderName = parent?.name,
            panelTitle = assignment.panelTitle.trim().ifEmpty { target.name },
        )
    }

    fun formatPromptBlock(link: WorkshopHostLink): String = buildString {
        appendLine("Host project link:")
        appendLine(
            "- Panel \"${link.panelTitle}\" serves subfolder \"${link.targetSubfolderName}\" " +
                "(targetSubfolderId=${link.targetSubfolderId}, " +
                "parent=${link.parentFolderName?.trim().orEmpty().ifEmpty { "parentFolderId=${link.parentFolderId}" }}, " +
                "parentFolderId=${link.parentFolderId}).",
        )
        appendLine(CROSS_SOURCE_RETRIEVAL_RULES)
    }.trim()

    internal val CROSS_SOURCE_RETRIEVAL_RULES: String = """
        Cross-source retrieval (Chat):
        - search_semantic defaults to this workshop project (scopeType=local_first, scopeId=workshop subfolderId).
        - To search the host project's notes and files, pass scopeType=subfolder or scopeType=parent — scopeId is auto-filled from the link above when omitted.
        - Use workshop_read_file for workshop file hits; use read_file for host-project file hits (not workshop_read_file).
        - Answer host-note questions from search_semantic chunk_text hits or read_note when the chat is note-scoped.
    """.trimIndent()
}
