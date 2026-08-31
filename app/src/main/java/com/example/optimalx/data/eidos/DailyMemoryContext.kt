package com.example.optimalx.data.eidos

import com.example.optimalx.data.db.AppDatabase
import com.example.optimalx.data.db.SystemFolderNames
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter

/**
 * Today's Daily Memory note under Eidos Daily for prompt injection.
 * See app/docs/memory/MEMORY_SYSTEM.md.
 */
object DailyMemoryContext {

    const val MAX_CONTENT_CHARS = 6_000

    suspend fun loadTodaySnapshot(
        database: AppDatabase,
        timestamp: Long = System.currentTimeMillis(),
    ): DailyMemoryPromptSnapshot {
        val dateKey = dateKeyFromTimestamp(timestamp)
        val parent = database.parentFolderDao().getSystemFolderByName(SystemFolderNames.EIDOS_DAILY)
            ?: return DailyMemoryPromptSnapshot(dateKey = dateKey, content = "", aiBlinded = false)

        val subfolder = database.subfolderDao().getAllByParentOnce(parent.id)
            .firstOrNull { it.deletedAt == null && it.name == dateKey }
        val note = subfolder?.let { database.noteDao().getBySubfolderOnce(it.id) }

        return DailyMemoryPromptSnapshot(
            dateKey = dateKey,
            content = note?.content.orEmpty().trim(),
            aiBlinded = note?.aiBlind == true,
        )
    }

    internal fun formatPrefetchFallbackBlock(dateKey: String): String =
        "Daily Memory ($dateKey): entries exist for today — use write_daily_memory or search_semantic if needed."

    internal fun formatPromptBlock(
        dateKey: String,
        content: String,
        aiBlinded: Boolean,
    ): String = buildString {
        appendLine("Daily Memory ($dateKey):")
        appendLine(EidosDailyMemoryUsageHint.TEXT)
        when {
            aiBlinded -> appendLine("(AI access disabled for today's daily memory note.)")
            content.isBlank() -> appendLine("(empty)")
            else -> {
                val capped = content.take(MAX_CONTENT_CHARS)
                append(capped)
                if (content.length > capped.length) {
                    appendLine()
                    append("[... daily memory truncated at $MAX_CONTENT_CHARS characters ...]")
                }
            }
        }
    }.trim()

    private fun dateKeyFromTimestamp(timestamp: Long): String =
        DateTimeFormatter.ofPattern("yyyy-MM-dd")
            .withZone(ZoneId.systemDefault())
            .format(Instant.ofEpochMilli(timestamp))
}

private object EidosDailyMemoryUsageHint {
    const val TEXT: String =
        "Today's working context — tasks, decisions, and mood for this day only. " +
            "Durable personal facts belong in Long-Term Memory (write_long_term_memory). " +
            "Use write_daily_memory when the user shares something relevant to today."
}
