package com.example.optimalx.ui.reasoning

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.example.optimalx.OptimalXApplication
import com.example.optimalx.data.model.Subfolder
import com.example.optimalx.data.repository.EditorRepository
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.mapLatest
import kotlinx.coroutines.flow.stateIn

class ReasoningInboxViewModel(
    app: Application,
    private val reasoningSubfolderId: Long,
) : AndroidViewModel(app) {
    private val json = Json { ignoreUnknownKeys = true }

    private val appRef = app as OptimalXApplication
    private val editorRepo = EditorRepository(
        db = appRef.database,
        semanticIndexer = appRef.semanticIndexer,
        appIndexSync = appRef.appIndexSyncService,
        semanticSync = appRef.semanticSyncService,
        semanticChunkBuilder = appRef.semanticChunkBuilder,
    )

    val reasoningSubfolder: StateFlow<Subfolder?> = editorRepo.getSubfolder(reasoningSubfolderId)
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), null)

    @OptIn(ExperimentalCoroutinesApi::class)
    val entries: StateFlow<List<ReasoningEntry>> = editorRepo.getNote(reasoningSubfolderId)
        .mapLatest { note ->
            val text = note?.content.orEmpty()
            val structured = parseStructuredRecords(text)
            if (structured.isNotEmpty()) return@mapLatest structured.newestFirst()

            val chunks = text.split(Regex("\\n\\s*\\n"))
                .map { it.trim() }
                .filter { it.isNotBlank() }

            val subfolderNameCache = linkedMapOf<Long, String>()
            chunks.map { chunk ->
                val parsed = parseChunk(chunk)
                val sourceLabel = parsed.sourceId?.let { sid ->
                    subfolderNameCache[sid] ?: run {
                        val resolved = appRef.database.subfolderDao().getById(sid)?.name ?: "Subfolder #$sid"
                        subfolderNameCache[sid] = resolved
                        resolved
                    }
                } ?: "Unknown source"

                ReasoningEntry(
                    sourceLabel = sourceLabel,
                    timestamp = parsed.timestamp,
                    piece = parsed.piece,
                    situation = parsed.situation,
                    step = parsed.step,
                    content = parsed.content,
                )
            }.newestFirst()
        }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())

    private fun parseStructuredRecords(text: String): List<ReasoningEntry> {
        val records = text.lineSequence()
            .map { it.trim() }
            .filter { it.startsWith("ABR1|") }
            .mapNotNull { line ->
                runCatching {
                    val obj = json.parseToJsonElement(line.removePrefix("ABR1|")).jsonObject
                    val type = obj["type"]?.jsonPrimitive?.content.orEmpty()
                    val timestamp = obj["timestamp"]?.jsonPrimitive?.content
                    val content = obj.toString()
                    when (type) {
                        "step" -> {
                            val piece = obj["selected_piece"]?.jsonPrimitive?.content
                            val situation = obj["board_signals"]?.jsonArray
                                ?.joinToString(",") { it.jsonPrimitive.content }
                            val stepName = obj["called_tool_name"]?.jsonPrimitive?.content
                            ReasoningEntry(
                                sourceLabel = "AgentByte",
                                timestamp = timestamp,
                                piece = piece,
                                situation = situation,
                                step = stepName,
                                content = content,
                            )
                        }
                        "run_start", "run_end" -> ReasoningEntry(
                            sourceLabel = "AgentByte",
                            timestamp = timestamp,
                            piece = null,
                            situation = type,
                            step = null,
                            content = content,
                        )
                        else -> null
                    }
                }.getOrNull()
            }
            .toList()
        return records
    }

    private fun parseChunk(raw: String): ParsedReasoningChunk {
        val lines = raw.lines().filter { it.isNotBlank() }
        if (lines.isEmpty()) return ParsedReasoningChunk(content = raw)

        val header = lines.first().trim()
        val body = lines.drop(1).joinToString("\n").trim().ifBlank { header }
        val headMatch = Regex("""^\[([^\]]+)]\s*(.*)$""").find(header)
        val timestamp = headMatch?.groupValues?.getOrNull(1)?.trim()?.ifBlank { null }
        val metaRaw = headMatch?.groupValues?.getOrNull(2)?.trim().orEmpty()

        var sourceId: Long? = null
        var piece: String? = null
        var situation: String? = null
        var step: String? = null

        metaRaw.split("|")
            .map { it.trim() }
            .filter { it.contains("=") }
            .forEach { token ->
                val idx = token.indexOf('=')
                val key = token.substring(0, idx).trim().lowercase()
                val value = token.substring(idx + 1).trim()
                when (key) {
                    "source" -> {
                        if (value.startsWith("subfolder:")) {
                            sourceId = value.substringAfter("subfolder:").toLongOrNull()
                        }
                    }
                    "piece" -> piece = value
                    "situation" -> situation = value
                    "step" -> step = value
                }
            }

        return ParsedReasoningChunk(
            sourceId = sourceId,
            timestamp = timestamp,
            piece = piece,
            situation = situation,
            step = step,
            content = body,
        )
    }
}
data class ReasoningEntry(
    val sourceLabel: String,
    val timestamp: String?,
    val piece: String?,
    val situation: String?,
    val step: String?,
    val content: String,
)

private fun List<ReasoningEntry>.newestFirst(): List<ReasoningEntry> = asReversed()

private data class ParsedReasoningChunk(
    val sourceId: Long? = null,
    val timestamp: String? = null,
    val piece: String? = null,
    val situation: String? = null,
    val step: String? = null,
    val content: String,
)

