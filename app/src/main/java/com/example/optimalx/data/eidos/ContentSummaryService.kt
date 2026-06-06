package com.example.optimalx.data.eidos

import android.content.Context
import com.example.optimalx.data.dao.NoteDao
import com.example.optimalx.data.dao.SubfolderDao
import com.example.optimalx.data.db.AppDatabase
import com.example.optimalx.data.eidos.model.EidosMessage
import com.example.optimalx.data.model.FileReference
import com.example.optimalx.data.model.Note
import com.example.optimalx.data.model.Subfolder
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import com.example.optimalx.data.semantic.ContentSegmentation

sealed class ContentSummaryResult {
    data object Success : ContentSummaryResult()
    data class Failed(val message: String) : ContentSummaryResult()
}

class ContentSummaryService(
    private val context: Context,
    private val database: AppDatabase,
    private val eidosApiClient: EidosApiClient,
) {
    private val noteDao: NoteDao = database.noteDao()
    private val subfolderDao: SubfolderDao = database.subfolderDao()

    suspend fun generateNoteSummary(noteId: Long): ContentSummaryResult = withContext(Dispatchers.IO) {
        val note = noteDao.getById(noteId) ?: return@withContext ContentSummaryResult.Failed("Note not found.")
        if (note.aiBlind) {
            return@withContext ContentSummaryResult.Failed("Note is blind from Eidos. Reveal it first.")
        }
        val plain = noteContentToPlain(note.content)
        if (plain.isBlank()) {
            return@withContext ContentSummaryResult.Failed("Note is empty.")
        }

        val input = plain.take(MAX_NOTE_INPUT_CHARS)
        val now = System.currentTimeMillis()

        return@withContext try {
            if (input.length >= NOTE_CHUNK_THRESHOLD_CHARS) {
                val segments = ContentSegmentation.splitContentSegments(input).map { it.text }
                val chunks = mutableListOf<ContentSummaryChunk>()
                segments.forEachIndexed { index, segment ->
                    val anchor = "Part ${index + 1} of ${segments.size}"
                    val summary = summarizeSegment(
                        cacheKey = "optimalx-summary-note-$noteId-chunk-$index",
                        systemPrompt = NOTE_CHUNK_SYSTEM,
                        userPayload = "Section: $anchor\n\n---\n\n$segment",
                    )
                    if (summary.isBlank()) {
                        return@withContext ContentSummaryResult.Failed("Summary generation returned empty text.")
                    }
                    chunks.add(ContentSummaryChunk(anchor = anchor, text = summary.trim()))
                }
                val overview = summarizeSegment(
                    cacheKey = "optimalx-summary-note-$noteId-overview",
                    systemPrompt = NOTE_OVERVIEW_SYSTEM,
                    userPayload = chunks.joinToString("\n\n") { "[${it.anchor}]\n${it.text}" },
                )
                noteDao.update(
                    note.copy(
                        summary = overview.trim().ifBlank { chunks.firstOrNull()?.text },
                        summaryChunksJson = ContentSummaryChunksCodec.encode(chunks),
                        summaryUpdatedAt = now,
                    ),
                )
            } else {
                val summary = summarizeSegment(
                    cacheKey = "optimalx-summary-note-$noteId",
                    systemPrompt = NOTE_SINGLE_SYSTEM,
                    userPayload = input,
                )
                if (summary.isBlank()) {
                    return@withContext ContentSummaryResult.Failed("Summary generation returned empty text.")
                }
                noteDao.update(
                    note.copy(
                        summary = summary.trim(),
                        summaryChunksJson = null,
                        summaryUpdatedAt = now,
                    ),
                )
            }
            ContentSummaryResult.Success
        } catch (e: Exception) {
            ContentSummaryResult.Failed(e.message ?: "Summary generation failed.")
        }
    }

    suspend fun generateWorkshopProjectSummary(
        subfolderId: Long,
        inMemoryByFileId: Map<Long, String> = emptyMap(),
    ): ContentSummaryResult = withContext(Dispatchers.IO) {
        val subfolder = subfolderDao.getById(subfolderId)
            ?: return@withContext ContentSummaryResult.Failed("Workshop project not found.")
        val files = database.fileReferenceDao().getBySubfolderOnce(subfolderId)
        val specBody = WorkshopSpecMarkdown.loadBounded(files, inMemoryByFileId)
        if (specBody.isBlank()) {
            return@withContext ContentSummaryResult.Failed(
                "No spec markdown found. Add README.md or other spec .md files first.",
            )
        }

        return@withContext try {
            val summary = summarizeSegment(
                cacheKey = "optimalx-summary-workshop-$subfolderId",
                systemPrompt = WORKSHOP_PROJECT_SYSTEM,
                userPayload = "Project: ${subfolder.name}\n\n$specBody",
            )
            if (summary.isBlank()) {
                return@withContext ContentSummaryResult.Failed("Summary generation returned empty text.")
            }
            subfolderDao.update(
                subfolder.copy(
                    projectSummary = summary.trim(),
                    projectSummaryUpdatedAt = System.currentTimeMillis(),
                ),
            )
            ContentSummaryResult.Success
        } catch (e: Exception) {
            ContentSummaryResult.Failed(e.message ?: "Summary generation failed.")
        }
    }

    private suspend fun summarizeSegment(
        cacheKey: String,
        systemPrompt: String,
        userPayload: String,
    ): String {
        val response = eidosApiClient.send(
            userMessage = userPayload,
            currentSubfolderId = null,
            currentParentFolderId = null,
            currentScopeType = "content_summary",
            conversationHistory = emptyList<EidosMessage>(),
            toolDefinitions = emptyList(),
            baseSystemPrompt = systemPrompt,
            previousResponseId = null,
            promptCacheKey = cacheKey,
        )
        return response.textResponse.trim()
    }

    companion object {
        const val NOTE_CHUNK_THRESHOLD_CHARS = 12_000
        const val CHUNK_SEGMENT_TARGET = ContentSegmentation.CHUNK_EMBED_TARGET_CHARS
        const val MAX_NOTE_INPUT_CHARS = 24_000

        private val NOTE_SINGLE_SYSTEM = """
            You compress note content for an AI assistant's stable context cache.
            Output only the summary — no title line, preamble, or markdown fences.
            Max ~800 words. Preserve topics, decisions, open questions, names, dates, and key facts.
            Do not invent content not present in the source.
        """.trimIndent()

        private val NOTE_CHUNK_SYSTEM = """
            You compress one section of a long note for an AI assistant.
            Output only that section's summary — no preamble or fences. Max ~400 words.
            Preserve facts from the section only; do not invent content.
        """.trimIndent()

        private val NOTE_OVERVIEW_SYSTEM = """
            You write a short overview of a long note from its section summaries.
            Output only the overview — no preamble or fences. Max ~300 words.
            Synthesize themes and structure; do not repeat every detail.
        """.trimIndent()

        private val WORKSHOP_PROJECT_SYSTEM = """
            You compress panel workshop spec markdown into a project orientation summary for an AI assistant.
            Output only the summary — no preamble or fences. Max ~600 words.
            Cover purpose, structure, features, user flows, and design constraints.
            Do not include HTML, CSS, or JavaScript. Do not invent features not in the specs.
        """.trimIndent()

        fun noteContentToPlain(htmlOrText: String): String {
            return htmlOrText
                .replace(Regex("<[^>]+>"), " ")
                .replace(Regex("&nbsp;|&#160;", RegexOption.IGNORE_CASE), " ")
                .replace(Regex("\\s+"), " ")
                .trim()
        }

        fun formatNoteSummaryForPrompt(note: Note): String? {
            if (note.aiBlind) return null
            val chunks = ContentSummaryChunksCodec.decode(note.summaryChunksJson)
            val summary = note.summary?.trim().orEmpty()
            if (summary.isEmpty() && chunks.isEmpty()) return null
            return buildString {
                if (summary.isNotEmpty()) {
                    appendLine("Note summary (user-generated; use read_note for full text):")
                    appendLine(summary)
                }
                if (chunks.isNotEmpty()) {
                    if (summary.isNotEmpty()) appendLine()
                    appendLine("Section summaries:")
                    chunks.forEach { chunk ->
                        appendLine("- ${chunk.anchor}: ${chunk.text}")
                    }
                }
            }.trim()
        }

        fun formatWorkshopSummaryForPrompt(subfolder: Subfolder): String? {
            val summary = subfolder.projectSummary?.trim().orEmpty()
            if (summary.isEmpty()) return null
            return """
                Project summary (user-generated; use workshop_read_file for full sources):
                $summary
            """.trimIndent()
        }
    }
}
