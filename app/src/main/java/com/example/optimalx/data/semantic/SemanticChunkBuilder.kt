package com.example.optimalx.data.semantic

import com.example.optimalx.data.dao.ChatMessageDao
import com.example.optimalx.data.dao.ConversationDao
import com.example.optimalx.data.dao.NoteDao
import com.example.optimalx.data.dao.ParentFolderDao
import com.example.optimalx.data.dao.SubfolderDao
import com.example.optimalx.data.eidos.EidosRetrievalGuard
import com.example.optimalx.data.eidos.NoteSummaryCodec
import com.example.optimalx.data.model.ChatMessage
import com.example.optimalx.data.model.Conversation
import com.example.optimalx.data.model.FileReference
import com.example.optimalx.data.model.Note
import com.example.optimalx.data.model.Subfolder
import com.example.optimalx.ui.components.NoteContentCodec

class SemanticChunkBuilder(
    private val noteDao: NoteDao,
    private val subfolderDao: SubfolderDao,
    private val parentFolderDao: ParentFolderDao,
    private val conversationDao: ConversationDao,
    private val chatMessageDao: ChatMessageDao,
) {

    suspend fun buildNoteChunks(subfolderId: Long): List<SemanticChunkDraft> {
        val subfolder = subfolderDao.getById(subfolderId) ?: return emptyList()
        if (subfolder.deletedAt != null) return emptyList()
        val parent = parentFolderDao.getById(subfolder.parentFolderId)
        if (!EidosRetrievalGuard.shouldIndexNoteForEidos(subfolder, parent)) return emptyList()
        val location = locationLabel(parent?.name, subfolder.name)
        val drafts = mutableListOf<SemanticChunkDraft>()
        drafts += buildWorkshopProjectSummaryDrafts(
            subfolder = subfolder,
            parentFolderId = subfolder.parentFolderId,
            location = location,
        )

        val note = noteDao.getBySubfolderOnce(subfolderId) ?: return drafts
        if (note.deletedAt != null || note.aiBlind) return drafts
        val stored = NoteContentCodec.normalizeLegacyToMarkdown(note.content)

        val summarySections = NoteSummaryCodec.parse(note.summary)
        if (summarySections.memoryBullets.isNotEmpty()) {
            drafts += draft(
                objectType = SemanticObjectType.NOTE,
                objectId = subfolderId,
                parentFolderId = subfolder.parentFolderId,
                subfolderId = subfolderId,
                location = location,
                text = "Folder memory:\n" + summarySections.memoryBullets.joinToString("\n") { "- $it" },
                chunkType = "summary_memory",
                startLine = null,
                endLine = null,
            )
        }
        if (summarySections.contentDigest.isNotBlank()) {
            drafts += draft(
                objectType = SemanticObjectType.NOTE,
                objectId = subfolderId,
                parentFolderId = subfolder.parentFolderId,
                subfolderId = subfolderId,
                location = location,
                text = "Content digest: ${summarySections.contentDigest.trim()}",
                chunkType = "summary_content",
                startLine = null,
                endLine = null,
            )
        }
        // Legacy plain summary not yet migrated to two-section format
        if (summarySections.isEmpty && !note.summary.isNullOrBlank()) {
            drafts += draft(
                objectType = SemanticObjectType.NOTE,
                objectId = subfolderId,
                parentFolderId = subfolder.parentFolderId,
                subfolderId = subfolderId,
                location = location,
                text = "Summary: ${note.summary!!.trim()}",
                chunkType = "summary",
                startLine = null,
                endLine = null,
            )
        }

        ContentSegmentation.splitContentSegments(stored).forEach { segment ->
            drafts += draft(
                objectType = SemanticObjectType.NOTE,
                objectId = subfolderId,
                parentFolderId = subfolder.parentFolderId,
                subfolderId = subfolderId,
                location = location,
                text = segment.text,
                chunkType = segment.chunkType,
                startLine = segment.startLine,
                endLine = segment.endLine,
            )
        }
        return drafts
    }

    private fun buildWorkshopProjectSummaryDrafts(
        subfolder: Subfolder,
        parentFolderId: Long?,
        location: String,
    ): List<SemanticChunkDraft> {
        val summary = subfolder.projectSummary?.trim().orEmpty()
        if (summary.isEmpty()) return emptyList()
        return listOf(
            draft(
                objectType = SemanticObjectType.NOTE,
                objectId = subfolder.id,
                parentFolderId = parentFolderId,
                subfolderId = subfolder.id,
                location = "$location (project summary)",
                text = "Project summary (${subfolder.name}):\n$summary",
                chunkType = CHUNK_TYPE_PROJECT_SUMMARY,
                startLine = null,
                endLine = null,
            ),
        )
    }

    fun buildFileChunks(ref: FileReference, extractedText: String, parentFolderId: Long?, subfolderName: String): List<SemanticChunkDraft> {
        val location = locationLabel(
            parentFolderId?.let { null }, // resolved by caller if needed
            subfolderName,
        ).let { base ->
            if (base.isBlank()) ref.fileName else "$base / ${ref.fileName}"
        }
        val header = "File: ${ref.fileName}\n"
        return ContentSegmentation.splitFileLineWindows(extractedText).map { segment ->
            draft(
                objectType = SemanticObjectType.FILE,
                objectId = ref.id,
                parentFolderId = parentFolderId,
                subfolderId = ref.subfolderId,
                location = location,
                text = header + segment.text,
                chunkType = segment.chunkType,
                startLine = segment.startLine,
                endLine = segment.endLine,
            )
        }
    }

    suspend fun buildFileChunks(ref: FileReference, extractedText: String): List<SemanticChunkDraft> {
        val subfolder = subfolderDao.getById(ref.subfolderId)
        val parent = subfolder?.let { parentFolderDao.getById(it.parentFolderId) }
        val location = locationLabel(parent?.name, subfolder?.name ?: "Files")
        val header = "File: ${ref.fileName}\n"
        return ContentSegmentation.splitFileLineWindows(extractedText).map { segment ->
            draft(
                objectType = SemanticObjectType.FILE,
                objectId = ref.id,
                parentFolderId = subfolder?.parentFolderId,
                subfolderId = ref.subfolderId,
                location = "$location / ${ref.fileName}",
                text = header + segment.text,
                chunkType = segment.chunkType,
                startLine = segment.startLine,
                endLine = segment.endLine,
            )
        }
    }

    suspend fun buildConversationChunks(
        conversationId: Long,
        excludeLatestUserText: String? = null,
    ): List<SemanticChunkDraft> {
        val conversation = conversationDao.getById(conversationId) ?: return emptyList()
        val rawMessages = chatMessageDao.getAllByConversation(conversationId)
        val messages = excludeLatestMatchingUser(rawMessages, excludeLatestUserText)
        if (messages.isEmpty()) return emptyList()
        val threadText = buildConversationThreadString(conversation, messages)
        val (parentFolderId, subfolderId) = resolveConversationScope(conversation)
        val location = conversation.title.ifBlank { "Chat ${conversation.id}" }
        return ContentSegmentation.splitConversationBatches(threadText).map { segment ->
            draft(
                objectType = SemanticObjectType.CONVERSATION,
                objectId = conversationId,
                parentFolderId = parentFolderId,
                subfolderId = subfolderId,
                location = location,
                text = segment.text,
                chunkType = segment.chunkType,
                startLine = segment.startLine,
                endLine = segment.endLine,
            )
        }
    }

    suspend fun indexNote(indexer: SemanticIndexer, subfolderId: Long) {
        val chunks = buildNoteChunks(subfolderId)
        if (chunks.isEmpty()) {
            indexer.deleteObject(SemanticObjectType.NOTE, subfolderId)
        } else {
            indexer.replaceObjectChunks(SemanticObjectType.NOTE, subfolderId, chunks)
        }
    }

    suspend fun indexFile(indexer: SemanticIndexer, ref: FileReference, extractedText: String) {
        val chunks = buildFileChunks(ref, extractedText)
        if (chunks.isEmpty()) {
            indexer.deleteObject(SemanticObjectType.FILE, ref.id)
        } else {
            indexer.replaceObjectChunks(SemanticObjectType.FILE, ref.id, chunks)
        }
    }

    suspend fun indexConversation(indexer: SemanticIndexer, conversationId: Long) {
        val chunks = buildConversationChunks(conversationId)
        if (chunks.isEmpty()) {
            indexer.deleteObject(SemanticObjectType.CONVERSATION, conversationId)
        } else {
            indexer.replaceObjectChunks(SemanticObjectType.CONVERSATION, conversationId, chunks)
        }
    }

    suspend fun conversationPlainText(conversationId: Long): String? {
        val conversation = conversationDao.getById(conversationId) ?: return null
        val messages = chatMessageDao.getAllByConversation(conversationId)
        if (messages.isEmpty()) return null
        return buildConversationThreadString(conversation, messages)
    }

    private fun buildConversationThreadString(conversation: Conversation, messages: List<ChatMessage>): String {
        return buildString {
            appendLine("Title: ${conversation.title}")
            messages.forEach { msg ->
                appendLine("${msg.role}: ${msg.content.trim()}")
            }
        }.trim()
    }

    private suspend fun resolveConversationScope(conversation: Conversation): Pair<Long?, Long?> {
        return when (conversation.scopeType) {
            "parent" -> conversation.parentFolderId to null
            "subfolder", "panel_workshop", "quick_notes_day", "web_editor" -> {
                val sfId = conversation.subfolderId
                val parentId = sfId?.let { subfolderDao.getById(it)?.parentFolderId }
                parentId to sfId
            }
            else -> null to conversation.subfolderId
        }
    }

    private fun locationLabel(parentName: String?, subfolderName: String): String {
        return listOfNotNull(
            parentName?.trim()?.takeIf { it.isNotEmpty() },
            subfolderName.trim().takeIf { it.isNotEmpty() },
        ).joinToString(" / ")
    }

    private fun draft(
        objectType: String,
        objectId: Long,
        parentFolderId: Long?,
        subfolderId: Long?,
        location: String,
        text: String,
        chunkType: String,
        startLine: Int?,
        endLine: Int?,
    ): SemanticChunkDraft {
        return SemanticChunkDraft(
            objectType = objectType,
            objectId = objectId,
            parentFolderId = parentFolderId,
            subfolderId = subfolderId,
            location = location,
            chunkText = text.trim(),
            chunkType = chunkType,
            startLine = startLine,
            endLine = endLine,
        )
    }

    private fun excludeLatestMatchingUser(
        messages: List<ChatMessage>,
        userText: String?,
    ): List<ChatMessage> {
        if (userText.isNullOrBlank() || messages.isEmpty()) return messages
        val trimmed = userText.trim()
        val last = messages.last()
        return if (last.role == "user" && last.content.trim() == trimmed) {
            messages.dropLast(1)
        } else {
            messages
        }
    }

    companion object {
        const val CHUNK_TYPE_PROJECT_SUMMARY = "project_summary"
    }
}

object SemanticObjectType {
    const val NOTE = "note"
    const val FILE = "file"
    const val CONVERSATION = "conversation"
}

/** @deprecated Use [SemanticObjectType] — kept for migration from object-level vectors. */
object SemanticSourceType {
    const val NOTE = SemanticObjectType.NOTE
    const val FILE = SemanticObjectType.FILE
    const val CONVERSATION = SemanticObjectType.CONVERSATION
    const val PARENT_FOLDER = "parent_folder"
    const val SUBFOLDER = "subfolder"
}
