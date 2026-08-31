package com.example.optimalx.data.eidos

import com.example.optimalx.data.dao.SubfolderDao
import com.example.optimalx.data.db.AppDatabase
import com.example.optimalx.data.model.Subfolder
import com.example.optimalx.data.eidos.model.EidosMessage
import com.example.optimalx.data.eidos.prompt.EidosEntrySurface
import com.example.optimalx.data.semantic.SemanticChunkBuilder
import com.example.optimalx.data.semantic.SemanticIndexer
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

sealed class ContentSummaryResult {
    data object Success : ContentSummaryResult()
    data class Failed(val message: String) : ContentSummaryResult()
}

/**
 * LLM-backed **workshop project** orientation summaries (`Subfolder.projectSummary`).
 *
 * Per-note folder memory uses [Note.summary] ([Memory] / [Content]) — see app/docs/systems/NOTE_SUMMARY.md.
 */
class ContentSummaryService(
    private val database: AppDatabase,
    private val eidosApiClient: EidosApiClient,
    private val semanticChunkBuilder: SemanticChunkBuilder,
    private val semanticIndexer: SemanticIndexer,
) {
    private val subfolderDao: SubfolderDao = database.subfolderDao()

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
            semanticChunkBuilder.indexNote(semanticIndexer, subfolderId)
            ContentSummaryResult.Success
        } catch (e: Exception) {
            ContentSummaryResult.Failed(e.message ?: "Summary generation failed.")
        }
    }

    private suspend fun summarizeSegment(
        cacheKey: String,
        userPayload: String,
    ): String {
        val response = eidosApiClient.send(
            userMessage = userPayload,
            currentSubfolderId = null,
            currentParentFolderId = null,
            currentScopeType = "content_summary",
            conversationHistory = emptyList<EidosMessage>(),
            toolDefinitions = emptyList(),
            entrySurface = EidosEntrySurface.INTERNAL,
            previousResponseId = null,
            promptCacheKey = cacheKey,
        )
        return response.summaryTextOrNull().orEmpty()
    }

    companion object {
        fun noteContentToPlain(htmlOrText: String): String {
            return htmlOrText
                .replace(Regex("<[^>]+>"), " ")
                .replace(Regex("&nbsp;|&#160;", RegexOption.IGNORE_CASE), " ")
                .replace(Regex("\\s+"), " ")
                .trim()
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
