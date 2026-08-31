package com.example.optimalx.data.eidos

import com.example.optimalx.data.db.AppDatabase
import com.example.optimalx.data.quicknotes.QuickNotesFormat

/**
 * Volatile Quick Notes context for [com.example.optimalx.data.eidos.prompt.EidosPromptComposer].
 */
object QuickNotesContext {

    suspend fun buildDayVolatileContext(database: AppDatabase, subfolderId: Long): String {
        val subfolder = database.subfolderDao().getById(subfolderId)
            ?: return "Quick Notes day context unavailable."
        val note = database.noteDao().getBySubfolderOnce(subfolderId)
        val entryCount = note?.content?.let { QuickNotesFormat.parseEntries(it).size } ?: 0
        return buildString {
            appendLine("Quick Notes day inbox:")
            appendLine("Date folder: ${subfolder.name}")
            appendLine("subfolderId: $subfolderId")
            appendLine("Entries on this day: $entryCount")
            append("Use write_quick_note to append new captures; timestamps are added automatically.")
        }.trim()
    }

    suspend fun buildRootVolatileContext(database: AppDatabase, parentFolderId: Long): String {
        val parent = database.parentFolderDao().getById(parentFolderId)
            ?: return "Quick Notes root context unavailable."
        return """
            Quick Notes directory:
            Name: ${parent.name}
            parentFolderId: ${parent.id}
            Day folders: use list_folder_contents(folderId=${parent.id}, parent="${parent.name}") — not listed inline.
        """.trimIndent()
    }
}
