package com.example.optimalx.data.eidos

import com.example.optimalx.data.db.AppDatabase
import com.example.optimalx.data.model.Subfolder
import com.example.optimalx.ui.folders.SortOrder

/**
 * Volatile parent-folder context for [com.example.optimalx.data.eidos.prompt.EidosPromptComposer].
 */
object ParentFolderContext {

    const val MAX_SUBFOLDER_CATALOG = 20

    fun formatActiveParentLine(parentName: String?, parentFolderId: Long): String {
        val name = parentName?.trim().orEmpty()
        return if (name.isNotEmpty()) {
            "Active parent folder: $name (parentFolderId=$parentFolderId)"
        } else {
            "Active parent folder: (parentFolderId=$parentFolderId)"
        }
    }

    suspend fun resolveActiveParentLine(
        database: AppDatabase,
        parentFolderId: Long,
    ): String {
        val parent = database.parentFolderDao().getById(parentFolderId)
        return formatActiveParentLine(parent?.name, parentFolderId)
    }

    suspend fun buildVolatileContext(
        database: AppDatabase,
        parentFolderId: Long,
        sortOrder: SortOrder = SortOrder.NAME_ASC,
    ): String {
        val parent = database.parentFolderDao().getById(parentFolderId)
            ?: return "Parent folder context unavailable."
        val subfolders = database.subfolderDao().getAllByParentOnce(parentFolderId)
            .filter { it.deletedAt == null }
            .let { sortOrder.sortSubfolders(it) }
        val total = subfolders.size
        val shown = subfolders.take(MAX_SUBFOLDER_CATALOG)
        return buildString {
            appendLine("Current parent folder:")
            appendLine("Name: ${parent.name}")
            appendLine("parentFolderId: ${parent.id}")
            appendLine()
            appendLine(
                formatSubfolderCatalog(
                    parentFolderId = parent.id,
                    parentName = parent.name,
                    total = total,
                    shown = shown,
                ),
            )
        }.trim()
    }

    internal fun formatSubfolderCatalog(
        parentFolderId: Long,
        parentName: String? = null,
        total: Int,
        shown: List<Subfolder>,
    ): String = buildString {
        if (total == 0) {
            append("Subfolders: (none yet)")
            return@buildString
        }
        val header = if (total <= MAX_SUBFOLDER_CATALOG) {
            "All subfolders in this parent ($total):"
        } else {
            "Subfolders shown (${shown.size} of $total total — same order as your folder list):"
        }
        appendLine(header)
        shown.forEach { subfolder ->
            appendLine("- ${subfolder.name} (subfolderId=${subfolder.id})")
        }
        if (total > MAX_SUBFOLDER_CATALOG) {
            appendLine()
            val parentLabel = parentName?.trim()?.takeIf { it.isNotEmpty() }?.let { "\"$it\"" }
                ?: "parentFolderId=$parentFolderId"
            append(
                "$total total — content in unlisted folders: search_semantic(scopeType=parent). " +
                    "For write/create or name→subfolderId on an unlisted folder: " +
                    "list_folder_contents(folderId=$parentFolderId, parent=$parentLabel) or search_folders(query=…).",
            )
        }
    }.trim()

    private fun SortOrder.sortSubfolders(subfolders: List<Subfolder>): List<Subfolder> = when (this) {
        SortOrder.NAME_ASC -> subfolders.sortedBy { it.name.lowercase() }
        SortOrder.NAME_DESC -> subfolders.sortedByDescending { it.name.lowercase() }
        SortOrder.CREATED_DESC -> subfolders.sortedByDescending { it.createdAt }
        SortOrder.UPDATED_DESC -> subfolders.sortedByDescending { it.updatedAt }
    }
}
