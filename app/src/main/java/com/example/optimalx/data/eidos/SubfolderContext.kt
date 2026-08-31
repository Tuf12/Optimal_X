package com.example.optimalx.data.eidos

import com.example.optimalx.data.db.AppDatabase
import com.example.optimalx.data.model.Subfolder

/**
 * Volatile subfolder context for [com.example.optimalx.data.eidos.prompt.EidosPromptComposer].
 */
object SubfolderContext {

    suspend fun buildVolatileContext(
        database: AppDatabase,
        subfolderId: Long,
    ): String {
        val subfolder = database.subfolderDao().getById(subfolderId)
            ?: return "Subfolder context unavailable."
        return buildVolatileContext(database, subfolder)
    }

    suspend fun buildVolatileContext(
        database: AppDatabase,
        subfolder: Subfolder,
    ): String {
        val note = database.noteDao().getBySubfolderOnce(subfolder.id)
        val body = note?.content?.let { NotePromptContext.normalizeBody(it) }.orEmpty()
        val noteContext = NotePromptContext.formatForPrompt(
            note = note,
            subfolderId = subfolder.id,
            bodyMarkdown = body,
            aiLocked = note?.aiLocked == true,
        )
        val memoryNudge = NotePromptContext.folderMemoryNudge(note, body.length)

        return buildString {
            appendLine("Current subfolder:")
            appendLine("Name: ${subfolder.name}")
            appendLine("subfolderId: ${subfolder.id}")
            appendLine(noteContext)
            memoryNudge?.let {
                appendLine()
                appendLine(it)
            }
            appendLine("Attachments: use list_folder_contents / read_file — not listed inline.")
        }.trim()
    }
}
