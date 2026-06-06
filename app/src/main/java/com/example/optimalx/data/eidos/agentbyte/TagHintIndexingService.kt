package com.example.optimalx.data.eidos.agentbyte

// ON HOLD — background Tag & Hint LLM enrichment for Eidos Index. Inactive while EidosIndexFeature is off.

import com.example.optimalx.data.dao.TagHintLineDao
import com.example.optimalx.data.db.AppDatabase
import com.example.optimalx.data.eidos.EidosIndexFeature
import com.example.optimalx.data.eidos.AppIndexMaterializer
import com.example.optimalx.data.eidos.EidosApiClient
import com.example.optimalx.data.eidos.EidosToolCatalog
import com.example.optimalx.data.eidos.model.EidosToolDefinition
import com.example.optimalx.data.model.TagHintLine
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject

data class IndexingRequest(
    val ref: String,
    val eventType: String,
    val contentPreview: String,
)

fun interface TagHintIndexingRunner {
    suspend fun run(prompt: String, toolDefinitions: List<EidosToolDefinition>): String
}

class TagHintIndexingService(
    private val db: AppDatabase,
    private val tagHintLineDao: TagHintLineDao,
    private val runner: TagHintIndexingRunner,
    private val materializer: AppIndexMaterializer = AppIndexMaterializer(db, tagHintLineDao),
    private val scope: CoroutineScope = CoroutineScope(SupervisorJob() + Dispatchers.IO),
) {
    private val queue = Channel<String>(Channel.UNLIMITED)
    private val pending = linkedMapOf<String, IndexingRequest>()
    private val pendingMutex = Mutex()
    private val restrictedTools = EidosToolCatalog.all.filter {
        it.name in setOf(
            "read_tag_hints",
            "upsert_tag_hint",
            "remove_tag_hint",
            "notify_user",
        )
    }

    init {
        scope.launch {
            while (true) {
                val key = queue.receive()
                val req = pendingMutex.withLock { pending.remove(key) } ?: continue
                process(req)
            }
        }
    }

    fun enqueue(request: IndexingRequest) {
        if (!EidosIndexFeature.isActive) return
        scope.launch {
            val key = request.ref.trim() + "|" + request.eventType.trim()
            val shouldSignal = pendingMutex.withLock {
                val first = pending[key] == null
                pending[key] = request
                first
            }
            if (shouldSignal) queue.send(key)
        }
    }

    suspend fun bootstrapFullIndex(): Int {
        if (!EidosIndexFeature.isActive) return 0
        val result = materializer.bootstrapFullIndex()
        return result.upsertedCount
    }

    private suspend fun process(request: IndexingRequest) {
        val logger = AgentByteReasoningLogger(
            db = db,
            scopeType = TAG_HINT_ENRICHMENT_SCOPE,
            scopeId = null,
            promptLabel = "${request.eventType}:${request.ref}",
        )
        val rowBefore = tagHintLineDao.getByRef(request.ref)
        val beforeSnapshot = rowToJson(rowBefore).toString()

        if (request.eventType == "permanent_delete" || request.eventType == "invalid_ref") {
            tagHintLineDao.deleteByRef(request.ref)
            logger.appendExternalEvent(
                toolName = "remove_tag_hint",
                notes = "operation=remove_short_circuit ref=${request.ref}",
                stateBefore = beforeSnapshot,
                stateAfter = rowToJson(null).toString(),
            )
            logger.appendExternalResult("success", "Tag hint removed by short-circuit")
            return
        }

        val prompt = buildPrompt(request)
        val llmText = runner.run(prompt, restrictedTools)
        if (llmText.contains("No API key is saved", ignoreCase = true)) {
            logger.appendExternalEvent(
                toolName = "notify_user",
                notes = "operation=skip_no_api_key ref=${request.ref}",
                stateBefore = beforeSnapshot,
                stateAfter = beforeSnapshot,
            )
            logger.appendExternalResult("success", "Skipped enrichment (no API key)")
            return
        }
        val rowAfter = tagHintLineDao.getByRef(request.ref)
        val operation = when {
            rowBefore == null && rowAfter != null -> "append"
            rowBefore != null && rowAfter == null -> "remove"
            rowBefore != null && rowAfter != null && rowBefore != rowAfter -> "replace"
            else -> "noop"
        }

        logger.appendExternalEvent(
            toolName = when (operation) {
                "remove" -> "remove_tag_hint"
                else -> "upsert_tag_hint"
            },
            notes = "operation=$operation ref=${request.ref} llm=${llmText.take(200)}",
            stateBefore = beforeSnapshot,
            stateAfter = rowToJson(rowAfter).toString(),
        )
        logger.appendExternalResult("success", "Indexing run complete")
    }

    private fun rowToJson(row: TagHintLine?): JsonObject {
        if (row == null) return JsonObject(emptyMap())
        return buildJsonObject {
            put("ref", JsonPrimitive(row.ref))
            put("tag", JsonPrimitive(row.tag))
            put("hint", JsonPrimitive(row.hint))
            put("objectName", JsonPrimitive(row.objectName))
            put("date", JsonPrimitive(row.date))
        }
    }

    private fun buildPrompt(request: IndexingRequest): String {
        return """
You are enriching Tag & Hint semantics for the Eidos Index.
Do not create or modify structural metadata (refs, folder names, IDs).
Only use upsert_tag_hint/remove_tag_hint to mutate rows.

Reference:
${request.ref}

Event type:
${request.eventType}

Content preview:
${request.contentPreview}

Write a short tag (1–4 words, topic/category) and a brief hint (what the object is about).
Use read_tag_hints first if you need surrounding context.
Call upsert_tag_hint or remove_tag_hint and finish with notify_user.
        """.trimIndent()
    }

    companion object {
        fun fromApiClient(
            db: AppDatabase,
            apiClient: EidosApiClient,
            scope: CoroutineScope = CoroutineScope(SupervisorJob() + Dispatchers.IO),
        ): TagHintIndexingService {
            val runner = TagHintIndexingRunner { prompt, toolDefinitions ->
                val response = apiClient.send(
                    userMessage = prompt,
                    promptCacheKey = "optimalx-tag-hint",
                    currentSubfolderId = null,
                    currentParentFolderId = null,
                    currentScopeType = TAG_HINT_ENRICHMENT_SCOPE,
                    conversationHistory = emptyList(),
                    toolDefinitions = toolDefinitions,
                    baseSystemPrompt = "You are running non-interrupting Tag & Hint enrichment only.",
                    previousResponseId = null,
                )
                response.textResponse
            }
            return TagHintIndexingService(
                db = db,
                tagHintLineDao = db.tagHintLineDao(),
                runner = runner,
                scope = scope,
            )
        }

        private const val TAG_HINT_ENRICHMENT_SCOPE = "tag_hint_enrichment"
    }
}
