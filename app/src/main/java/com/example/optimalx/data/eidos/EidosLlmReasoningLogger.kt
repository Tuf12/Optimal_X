package com.example.optimalx.data.eidos

import com.example.optimalx.data.db.AppDatabase
import com.example.optimalx.data.db.SystemFolderNames
import com.example.optimalx.data.model.ConversationScopes
import com.example.optimalx.data.model.Note
import com.example.optimalx.data.model.Subfolder
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Locale

/**
 * Persists provider thinking/reasoning traces into the **Reasoning** system subfolder
 * under the scoped parent folder (or Eidos Reasoning for general scope).
 *
 * Format matches [RoomToolExecutor.appendReasoningTrace] so [ReasoningInboxScreen] can parse entries.
 */
class EidosLlmReasoningLogger(
    private val db: AppDatabase,
) {
    suspend fun appendProviderThinking(
        provider: String,
        scopeType: String?,
        subfolderId: Long?,
        parentFolderId: Long?,
        turnId: String? = null,
        round: Int,
        phaseLabel: String,
        reasoningContent: String,
        toolNames: List<String> = emptyList(),
        chatMessageId: Long? = null,
    ) {
        val trimmed = reasoningContent.trim()
        if (trimmed.isBlank()) return

        val now = System.currentTimeMillis()
        val ts = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss")
            .withZone(ZoneId.systemDefault())
            .format(Instant.ofEpochMilli(now))

        val sourceMarker = buildSourceMarker(scopeType, subfolderId, parentFolderId)
        val header = buildList {
            add(sourceMarker)
            add("provider=$provider")
            if (!turnId.isNullOrBlank()) add("turn=$turnId")
            chatMessageId?.let { add("chatMessageId=$it") }
            add("phase=$phaseLabel")
            add("round=$round")
            if (toolNames.isNotEmpty()) {
                add("tools=${toolNames.joinToString(",")}")
            }
        }.joinToString(" | ")

        val entry = "[$ts] $header\n$trimmed"
        val note = resolveReasoningNote(scopeType, subfolderId, parentFolderId, now)
        val merged = if (note.content.isBlank()) entry else "${note.content.trimEnd()}\n\n$entry"
        db.noteDao().update(note.copy(content = merged, updatedAt = now))
        db.subfolderDao().getById(note.subfolderId)?.let { sf ->
            db.subfolderDao().update(sf.copy(updatedAt = now))
        }
    }

    private fun buildSourceMarker(
        scopeType: String?,
        subfolderId: Long?,
        parentFolderId: Long?,
    ): String {
        return when (scopeType?.lowercase(Locale.US)) {
            ConversationScopes.PARENT -> parentFolderId?.let { "source=parent:$it" }
            ConversationScopes.SUBFOLDER,
            ConversationScopes.PANEL_WORKSHOP,
            ConversationScopes.QUICK_NOTES_DAY,
            ConversationScopes.WEB_EDITOR,
            -> subfolderId?.let { "source=subfolder:$it" }
            ConversationScopes.QUICK_NOTES_ROOT -> parentFolderId?.let { "source=parent:$it" }
            else -> null
        } ?: "source=general"
    }

    private suspend fun resolveReasoningNote(
        scopeType: String?,
        subfolderId: Long?,
        parentFolderId: Long?,
        now: Long,
    ): Note {
        val resolvedParentId = resolveParentFolderId(scopeType, subfolderId, parentFolderId)
        if (resolvedParentId != null) {
            val reasoningSubfolder = ensureParentReasoningSubfolder(resolvedParentId, now)
            return db.noteDao().getBySubfolderOnce(reasoningSubfolder.id)
                ?: db.noteDao().insert(Note(subfolderId = reasoningSubfolder.id, updatedAt = now)).let {
                    db.noteDao().getBySubfolderOnce(reasoningSubfolder.id)
                } ?: error("Failed to resolve parent reasoning note")
        }

        val parent = db.parentFolderDao().getSystemFolderByName(SystemFolderNames.EIDOS_REASONING)
            ?: error("Eidos Reasoning system folder not found")
        val day = dayKey()
        val subfolder = db.subfolderDao().getAllByParentOnce(parent.id)
            .firstOrNull { it.deletedAt == null && it.name == day }
            ?: run {
                val sid = db.subfolderDao().insert(
                    Subfolder(
                        parentFolderId = parent.id,
                        name = day,
                        updatedAt = now,
                    ),
                )
                db.noteDao().insert(Note(subfolderId = sid, updatedAt = now))
                db.subfolderDao().getById(sid) ?: error("Failed creating daily reasoning subfolder")
            }

        return db.noteDao().getBySubfolderOnce(subfolder.id)
            ?: db.noteDao().insert(Note(subfolderId = subfolder.id, updatedAt = now)).let {
                db.noteDao().getBySubfolderOnce(subfolder.id)
            } ?: error("Failed to resolve system reasoning note")
    }

    private suspend fun resolveParentFolderId(
        scopeType: String?,
        subfolderId: Long?,
        parentFolderId: Long?,
    ): Long? {
        return when (scopeType?.lowercase(Locale.US)) {
            ConversationScopes.PARENT,
            ConversationScopes.QUICK_NOTES_ROOT,
            -> parentFolderId
            ConversationScopes.SUBFOLDER,
            ConversationScopes.PANEL_WORKSHOP,
            ConversationScopes.QUICK_NOTES_DAY,
            ConversationScopes.WEB_EDITOR,
            -> subfolderId?.let { db.subfolderDao().getById(it)?.parentFolderId }
            else -> null
        }
    }

    private suspend fun ensureParentReasoningSubfolder(parentId: Long, now: Long): Subfolder {
        val existing = db.subfolderDao().getAllByParentOnce(parentId)
            .firstOrNull {
                it.deletedAt == null &&
                    it.isSystemSubfolder &&
                    it.name == SystemFolderNames.PARENT_REASONING_SUBFOLDER
            }
        if (existing != null) return existing
        val id = db.subfolderDao().insert(
            Subfolder(
                parentFolderId = parentId,
                name = SystemFolderNames.PARENT_REASONING_SUBFOLDER,
                isSystemSubfolder = true,
                sortOrder = 9997,
                updatedAt = now,
            ),
        )
        db.noteDao().insert(Note(subfolderId = id, updatedAt = now))
        return db.subfolderDao().getById(id) ?: error("Failed to create parent reasoning subfolder")
    }

    private fun dayKey(): String = DateTimeFormatter.ofPattern("yyyy-MM-dd")
        .withZone(ZoneId.systemDefault())
        .format(Instant.now())
}
