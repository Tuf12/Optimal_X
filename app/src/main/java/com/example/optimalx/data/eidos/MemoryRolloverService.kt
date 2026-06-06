package com.example.optimalx.data.eidos

import android.content.Context
import android.util.Log
import com.example.optimalx.data.db.AppDatabase
import com.example.optimalx.data.db.SystemFolderNames
import com.example.optimalx.data.eidos.agentbyte.AgentByteLoop
import com.example.optimalx.data.eidos.agentbyte.AgentByteReasoningLogger
import com.example.optimalx.data.eidos.agentbyte.BoardState
import com.example.optimalx.data.eidos.agentbyte.ChessPiece
import com.example.optimalx.data.eidos.agentbyte.LoopStepResult
import com.example.optimalx.data.eidos.agentbyte.ModeBoardScanPlan
import com.example.optimalx.data.eidos.agentbyte.OperatingMode
import com.example.optimalx.data.eidos.agentbyte.PieceRole
import com.example.optimalx.data.eidos.agentbyte.PieceSelection
import com.example.optimalx.data.eidos.model.EidosMessage
import com.example.optimalx.data.eidos.model.ToolExecutionResult
import com.example.optimalx.data.preferences.ApiKeyNames
import com.example.optimalx.data.preferences.EncryptedSettingKeys
import com.example.optimalx.data.preferences.SettingsDefaults
import com.example.optimalx.data.preferences.SettingsKeys
import com.example.optimalx.data.preferences.getEncryptedPrefs
import com.example.optimalx.data.preferences.settingsDataStore
import com.example.optimalx.data.semantic.EmbeddingEngine
import com.example.optimalx.data.semantic.SemanticIndexer
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.doubleOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter

class MemoryRolloverService(
    private val context: Context,
    private val database: AppDatabase,
    private val eidosApiClient: EidosApiClient,
    private val embeddingEngine: EmbeddingEngine,
    private val semanticIndexer: SemanticIndexer,
    private val appIndexSync: AppIndexSyncService? = null,
) {
    private val decisionJson = Json { ignoreUnknownKeys = true }

    suspend fun runMemoryRollover(timestamp: Long = System.currentTimeMillis()): MemoryRolloverResult = withContext(Dispatchers.IO) {
        Log.i(
            MemoryRolloverScheduler.LOG_TAG,
            "runMemoryRollover invoked timestamp=$timestamp dateKey=${dateKeyFromTimestamp(timestamp)}",
        )
        val provider = resolveActiveProvider()
        if (!hasApiKeyForProvider(provider)) {
            return@withContext MemoryRolloverResult(
                status = MemoryRolloverStatus.FAILED,
                message = "No API key configured for ${providerDisplayName(provider)}. Add one in Settings before rollover.",
                dailyCleared = false,
            )
        }

        val dailySnapshot = readDailyMemorySnapshot(timestamp)
        if (dailySnapshot.content.isBlank()) {
            return@withContext MemoryRolloverResult(
                status = MemoryRolloverStatus.SKIPPED,
                message = "Daily Memory is empty. Nothing to roll over.",
                dailyCleared = false,
            )
        }

        val journalBefore = getSystemFolderMaxUpdatedAt(SystemFolderNames.EIDOS_JOURNAL)
        val ltmBefore = getSystemFolderMaxUpdatedAt(SystemFolderNames.EIDOS_MEMORY)

        val rolloverId = buildRolloverId(timestamp)
        val text = runRolloverAgentByteLoop(
            timestamp = timestamp,
            rolloverId = rolloverId,
            preloadedDailyContent = dailySnapshot.content,
        ).trim()
        if (text.contains(ROLLOVER_EMPTY_MARKER)) {
            return@withContext MemoryRolloverResult(
                status = MemoryRolloverStatus.SKIPPED,
                message = "Daily Memory was empty during rollover. Nothing was changed.",
                dailyCleared = false,
            )
        }
        val ok = text.contains(ROLLOVER_OK_MARKER)
        if (!ok) {
            return@withContext MemoryRolloverResult(
                status = MemoryRolloverStatus.FAILED,
                message = if (text.isBlank()) {
                    "Rollover did not complete and returned no status marker. Daily Memory was left untouched."
                } else {
                    "Rollover did not complete cleanly (${text.take(180)}). Daily Memory was left untouched."
                },
                dailyCleared = false,
            )
        }

        val report = parseRolloverReport(text)
            ?: return@withContext MemoryRolloverResult(
                status = MemoryRolloverStatus.FAILED,
                message = "Rollover reported success marker but missing structured completion report. Daily Memory was left untouched.",
                dailyCleared = false,
            )

        val journalAfter = getSystemFolderMaxUpdatedAt(SystemFolderNames.EIDOS_JOURNAL)
        val ltmAfter = getSystemFolderMaxUpdatedAt(SystemFolderNames.EIDOS_MEMORY)

        val journalAdvanced = journalAfter > journalBefore
        val journalContainsRolloverId = journalContainsRolloverId(rolloverId, timestamp)
        if (!report.journalWritten || !journalAdvanced || !journalContainsRolloverId) {
            return@withContext MemoryRolloverResult(
                status = MemoryRolloverStatus.FAILED,
                message = "Rollover did not verify Journal write for rollover_id=$rolloverId. Daily Memory was left untouched.",
                dailyCleared = false,
            )
        }

        if (report.ltmPromotions > 0 && ltmAfter <= ltmBefore) {
            return@withContext MemoryRolloverResult(
                status = MemoryRolloverStatus.FAILED,
                message = "Rollover reported LTM promotions but Long-Term Memory did not update. Daily Memory was left untouched.",
                dailyCleared = false,
            )
        }

        clearDailyMemory(dailySnapshot.subfolderIds)

        Log.i(MemoryRolloverScheduler.LOG_TAG, "runMemoryRollover SUCCESS daily cleared")
        MemoryRolloverResult(
            status = MemoryRolloverStatus.SUCCESS,
            message = "Memory rollover completed (journalRead=${report.journalRead}, journal=${report.journalWritten}, ltmPromotions=${report.ltmPromotions}) and Daily Memory was cleared.",
            dailyCleared = true,
        )
    }.also { result ->
        if (result.status != MemoryRolloverStatus.SUCCESS) {
            Log.i(MemoryRolloverScheduler.LOG_TAG, "runMemoryRollover ended: ${result.status} — ${result.message}")
        }
    }

    private suspend fun resolveActiveProvider(): String {
        val encPrefs = getEncryptedPrefs(context)
        return encPrefs.getString(EncryptedSettingKeys.ACTIVE_PROVIDER, null)
            ?: context.settingsDataStore.data.first()[SettingsKeys.ACTIVE_PROVIDER]
            ?: SettingsDefaults.ACTIVE_PROVIDER
    }

    private fun hasApiKeyForProvider(provider: String): Boolean {
        val encPrefs = getEncryptedPrefs(context)
        val keyName = when (provider) {
            "openai" -> ApiKeyNames.OPENAI
            "anthropic" -> ApiKeyNames.ANTHROPIC
            "kimi" -> ApiKeyNames.KIMI
            else -> ApiKeyNames.XAI
        }
        return !encPrefs.getString(keyName, null).isNullOrBlank()
    }

    private suspend fun readDailyMemorySnapshot(timestamp: Long): DailyMemorySnapshot {
        val parent = database.parentFolderDao().getSystemFolderByName(SystemFolderNames.EIDOS_DAILY)
            ?: return DailyMemorySnapshot()
        val day = dateKeyFromTimestamp(timestamp)
        val allSubfolders = database.subfolderDao().getAllByParentOnce(parent.id)
            .filter { it.deletedAt == null }
        val subfolder = allSubfolders
            .firstOrNull { it.deletedAt == null && it.name == day }
        val targetContent = subfolder?.let { database.noteDao().getBySubfolderOnce(it.id)?.content.orEmpty().trim() }.orEmpty()
        if (targetContent.isNotBlank() && subfolder != null) {
            return DailyMemorySnapshot(
                content = targetContent,
                subfolderIds = listOf(subfolder.id),
            )
        }

        val fallback = allSubfolders
            .mapNotNull { sf ->
                val content = database.noteDao().getBySubfolderOnce(sf.id)?.content.orEmpty().trim()
                if (content.isBlank()) null else sf to content
            }
            .sortedBy { it.first.name }
        if (fallback.isEmpty()) return DailyMemorySnapshot()

        val merged = fallback.joinToString(separator = "\n\n") { (sf, content) ->
            "[day=${sf.name}]\n$content"
        }
        return DailyMemorySnapshot(
            content = merged,
            subfolderIds = fallback.map { it.first.id },
        )
    }

    private suspend fun clearDailyMemory(subfolderIds: List<Long>) {
        if (subfolderIds.isEmpty()) return
        var changed = false
        subfolderIds.forEach { subfolderId ->
            val note = database.noteDao().getBySubfolderOnce(subfolderId) ?: return@forEach
            database.noteDao().update(note.copy(content = "", updatedAt = System.currentTimeMillis()))
            changed = true
        }
        if (changed) {
            appIndexSync?.requestSync("rollover_clear_daily_memory")
        }
    }

    private suspend fun getSystemFolderMaxUpdatedAt(systemFolderName: String): Long {
        val parent = database.parentFolderDao().getSystemFolderByName(systemFolderName) ?: return 0L
        return database.subfolderDao().getAllByParentOnce(parent.id)
            .filter { it.deletedAt == null }
            .maxOfOrNull { sf ->
                val noteUpdated = database.noteDao().getBySubfolderOnce(sf.id)?.updatedAt ?: 0L
                maxOf(sf.updatedAt, noteUpdated)
            } ?: 0L
    }

    private suspend fun journalContainsRolloverId(rolloverId: String, timestamp: Long): Boolean {
        val parent = database.parentFolderDao().getSystemFolderByName(SystemFolderNames.EIDOS_JOURNAL) ?: return false
        val day = dateKeyFromTimestamp(timestamp)
        val subfolder = database.subfolderDao().getAllByParentOnce(parent.id)
            .firstOrNull { it.deletedAt == null && it.name == day }
            ?: return false
        val note = database.noteDao().getBySubfolderOnce(subfolder.id) ?: return false
        return note.content.contains("rollover_id=$rolloverId")
    }

    private fun parseRolloverReport(text: String): RolloverReport? {
        val reportLine = text.lineSequence()
            .map { it.trim() }
            .firstOrNull { it.startsWith(ROLLOVER_OK_MARKER) }
            ?: return null

        val tokens = reportLine.split("|").map { it.trim() }.filter { it.isNotBlank() }
        val fields = tokens.drop(1)
            .mapNotNull { token ->
                val idx = token.indexOf('=')
                if (idx <= 0) return@mapNotNull null
                token.substring(0, idx).trim().lowercase() to token.substring(idx + 1).trim()
            }
            .toMap()

        val journalWritten = fields["journal_written"]?.equals("true", ignoreCase = true) ?: false
        val journalRead = fields["journal_read"]?.equals("true", ignoreCase = true) ?: false
        val ltmPromotions = fields["ltm_promotions"]?.toIntOrNull() ?: return null
        return RolloverReport(
            journalRead = journalRead,
            journalWritten = journalWritten,
            ltmPromotions = ltmPromotions.coerceAtLeast(0),
        )
    }

    private fun dateKeyFromTimestamp(timestamp: Long): String {
        return DateTimeFormatter.ofPattern("yyyy-MM-dd")
            .withZone(ZoneId.systemDefault())
            .format(Instant.ofEpochMilli(timestamp))
    }

    private fun providerDisplayName(provider: String): String = when (provider) {
        "openai" -> "OpenAI"
        "anthropic" -> "Anthropic"
        "kimi" -> "Kimi (Moonshot)"
        else -> "xAI"
    }

    private fun buildRolloverTaskPrompt(timestamp: Long, rolloverId: String): String {
        return """
            Execute nightly memory rollover for timestamp $timestamp.
            rollover_id=$rolloverId

            Required procedure:
            1) Read Daily Memory for this timestamp.
            2) If empty, respond exactly with ROLLOVER_EMPTY and stop.
            3) Read Journal for continuity context only if needed:
               - read_journal (optional)
               - search_chat_history and/or read_conversation only when context is missing
               - search_semantic for related context as needed
               - read_tag_hints for retrieval/routing context only (never mutate tag-hint rows in rollover)
            4) Write one Eidos self-reflection journal entry (environment/process/discoveries/issues), and include "rollover_id=$rolloverId" in that journal content.
            5) Promote durable facts to Long-Term Memory using write_long_term_memory.
            6) Do NOT clear daily memory.
        """.trimIndent()
    }

    private fun buildRolloverId(timestamp: Long): String {
        val datePart = DateTimeFormatter.ofPattern("yyyyMMdd_HHmmss")
            .withZone(ZoneId.systemDefault())
            .format(Instant.ofEpochMilli(timestamp))
        return "rr_$datePart"
    }

    companion object {
        private const val ROLLOVER_OK_MARKER = "ROLLOVER_OK"
        private const val ROLLOVER_EMPTY_MARKER = "ROLLOVER_EMPTY"
        private const val TOOL_READ_TAG_HINTS = "read_tag_hints"
        
        internal fun rolloverPhaseAllowlistSnapshotForTests(): Map<String, List<String>> {
            return RolloverPhase.values().associate { phase ->
                phase.name to phaseAllowedTools(phase)
            }
        }

        internal fun rolloverTagHintReadOnlyPolicyForTests(): Boolean {
            val allTools = rolloverPhaseAllowlistSnapshotForTests().values.flatten().toSet()
            return "upsert_tag_hint" !in allTools && "remove_tag_hint" !in allTools
        }


        private fun phaseAllowedTools(phase: RolloverPhase): List<String> {
            val tools = when (phase) {
            RolloverPhase.KING_INIT -> listOf("read_daily_memory")
            RolloverPhase.ROOK_READ_DAILY -> listOf("read_journal")
            RolloverPhase.ROOK_KNIGHT_SEARCH -> listOf(
                "read_long_term_memory",
                "read_tag_hints",
                "search_semantic",
                "search_chat_history",
            )
            RolloverPhase.ROOK_LOGICAL_PASS -> listOf(
                "ROOK_LOGICAL_PASS",
                "read_journal",
                "read_long_term_memory",
                TOOL_READ_TAG_HINTS,
                "search_semantic",
                "search_chat_history",
            )
            RolloverPhase.BISHOP_REFLECTIVE_PASS -> listOf(
                "BISHOP_REFLECTIVE_PASS",
            )
            RolloverPhase.ROOK_PAWN_JOURNAL_WRITE -> listOf("ROOK_PAWN_JOURNAL_WRITE")
            RolloverPhase.ROOK_PAWN_LTM_PROMOTION -> listOf("ROOK_PAWN_LTM_PROMOTION")
            RolloverPhase.KING_VERIFY_CLOSE -> emptyList()
            }
            return if (EidosIndexFeature.isActive) tools else tools.filter { it != TOOL_READ_TAG_HINTS }
        }
    }

    private suspend fun runRolloverAgentByteLoop(
        timestamp: Long,
        rolloverId: String,
        preloadedDailyContent: String,
    ): String {
        val toolExecutor = RoomToolExecutor(
            context = context,
            db = database,
            embeddingEngine = embeddingEngine,
            semanticIndexer = semanticIndexer,
        )
        val logger = AgentByteReasoningLogger(
            db = database,
            scopeType = "rollover",
            scopeId = null,
            promptLabel = "Nightly memory rollover orchestration",
            runId = rolloverId,
        )
        var rolloverResponseText = ""
        val phaseState = RolloverPhaseState(
            dailyRead = false,
            dailyContent = preloadedDailyContent,
        )
        var report: RolloverReport? = null

        val result = AgentByteLoop.run(
            engine = object : AgentByteLoop.LoopEngine {
                override suspend fun assembleBoardState(iteration: Int, previous: LoopStepResult?): BoardState {
                    val intent = when (phaseState.nextToolName()) {
                        "read_daily_memory", "read_long_term_memory", "ROOK_LOGICAL_PASS", "ROOK_PAWN_LTM_PROMOTION" -> setOf("logical_retrieval")
                        "read_journal", "BISHOP_REFLECTIVE_PASS", "ROOK_PAWN_JOURNAL_WRITE" -> setOf("emotional_personal")
                        else -> setOf("logical_retrieval")
                    }
                    return BoardState(
                        operatingMode = OperatingMode.GENERAL,
                        scopeType = "rollover",
                        scopeId = null,
                        userMessage = "Run memory rollover",
                        intentSignals = intent,
                    )
                }

                override suspend fun buildPrompt(snapshot: AgentByteLoop.IterationSnapshot): AgentByteLoop.PromptPacket {
                    val phaseAllowed = phaseAllowedTools(phaseState.currentPhase())
                    return AgentByteLoop.PromptPacket(
                        modePlan = ModeBoardScanPlan(
                            mode = OperatingMode.GENERAL,
                            description = "Rollover orchestration",
                            recommendedOpeningTools = listOf("read_daily_memory"),
                        ),
                        pieceSelection = PieceSelection(
                            primaryPiece = snapshot.pieceSelection.primaryPiece,
                            role = snapshot.pieceSelection.role,
                            supportingPieces = snapshot.pieceSelection.supportingPieces,
                        ),
                        // Expose only phase-scoped tools to the decision prompt/log contract.
                        allowedTools = phaseAllowed,
                    )
                }

                override suspend fun chooseToolCall(
                    prompt: AgentByteLoop.PromptPacket,
                    snapshot: AgentByteLoop.IterationSnapshot,
                ): AgentByteLoop.ToolDecision {
                    val phase = phaseState.currentPhase()
                    val phaseAllowedTools = phaseAllowedTools(phase)
                    if (phaseAllowedTools.size == 1) {
                        val selectedTool = phaseAllowedTools.first()
                        return AgentByteLoop.ToolDecision(
                            type = AgentByteLoop.DecisionType.TOOL_CALL,
                            toolCall = AgentByteLoop.ToolCall(selectedTool),
                            decisionRequired = false,
                            decisionSkippedSingleInternalHandle = true,
                            notes = "Single allowlisted phase action '$selectedTool' selected directly; decision JSON call skipped.",
                        )
                    }

                    val decisionResponse = eidosApiClient.send(
                        userMessage = buildRolloverDecisionPrompt(
                            timestamp = timestamp,
                            rolloverId = rolloverId,
                            phase = phase,
                            phaseAllowedTools = phaseAllowedTools,
                            prompt = prompt,
                            snapshot = snapshot,
                            phaseState = phaseState,
                        ),
                        promptCacheKey = "optimalx-rollover-$rolloverId",
                        currentSubfolderId = null,
                        currentParentFolderId = null,
                        currentScopeType = "rollover",
                        conversationHistory = emptyList<EidosMessage>(),
                        toolDefinitions = emptyList(),
                        baseSystemPrompt = buildRolloverSystemPrompt(
                            phase = RolloverPromptPhase.DECISION,
                            activePhase = phase,
                            activePiece = snapshot.pieceSelection.primaryPiece.name,
                            supportingPieces = snapshot.pieceSelection.supportingPieces.map { it.name },
                            allowedTools = phaseAllowedTools,
                        ),
                        previousResponseId = null,
                    )
                    val raw = decisionResponse.textResponse.trim()
                    var parsed = parseRolloverDecision(raw, snapshot.allowedTools, phaseAllowedTools)
                    if (parsed == null) {
                        val repairResponse = eidosApiClient.send(
                            userMessage = buildRolloverDecisionRepairPrompt(
                                timestamp = timestamp,
                                rolloverId = rolloverId,
                                phase = phase,
                                phaseAllowedTools = phaseAllowedTools,
                                rawDecisionText = raw,
                            ),
                            promptCacheKey = "optimalx-rollover-$rolloverId",
                            currentSubfolderId = null,
                            currentParentFolderId = null,
                            currentScopeType = "rollover",
                            conversationHistory = emptyList<EidosMessage>(),
                            toolDefinitions = emptyList(),
                            baseSystemPrompt = buildRolloverSystemPrompt(
                                phase = RolloverPromptPhase.DECISION_REPAIR,
                                activePhase = phase,
                                activePiece = snapshot.pieceSelection.primaryPiece.name,
                                supportingPieces = snapshot.pieceSelection.supportingPieces.map { it.name },
                                allowedTools = phaseAllowedTools,
                            ),
                            previousResponseId = null,
                        )
                        val repairRaw = repairResponse.textResponse.trim()
                        parsed = parseRolloverDecision(repairRaw, snapshot.allowedTools, phaseAllowedTools)
                        if (parsed == null) {
                            return AgentByteLoop.ToolDecision(
                                type = AgentByteLoop.DecisionType.NO_ACTION,
                                decisionText = raw,
                                forceUnrecoverableOnNoAction = true,
                                decisionRequired = true,
                                decisionSkippedSingleInternalHandle = false,
                                decisionParseFailed = true,
                                decisionRepairRetryAttempted = true,
                                decisionRepairRetrySucceeded = false,
                                notes = "Failed to parse rollover decision JSON after one repair retry.",
                            )
                        }
                        parsed = parsed.copy(
                            decisionRequired = true,
                            decisionSkippedSingleInternalHandle = false,
                            decisionParseFailed = true,
                            decisionRepairRetryAttempted = true,
                            decisionRepairRetrySucceeded = true,
                        )
                    } else {
                        parsed = parsed.copy(
                            decisionRequired = true,
                            decisionSkippedSingleInternalHandle = false,
                            decisionParseFailed = false,
                            decisionRepairRetryAttempted = false,
                            decisionRepairRetrySucceeded = false,
                        )
                    }
                    if (parsed.type == AgentByteLoop.DecisionType.EXPLICIT_COMPLETE &&
                        !(phaseState.ltmPromotionComplete && phaseState.successGatesVerified)
                    ) {
                        return AgentByteLoop.ToolDecision(
                            type = AgentByteLoop.DecisionType.NO_ACTION,
                            decisionText = parsed.decisionText,
                            decisionRequired = parsed.decisionRequired,
                            decisionSkippedSingleInternalHandle = parsed.decisionSkippedSingleInternalHandle,
                            decisionParseFailed = parsed.decisionParseFailed,
                            decisionRepairRetryAttempted = parsed.decisionRepairRetryAttempted,
                            decisionRepairRetrySucceeded = parsed.decisionRepairRetrySucceeded,
                            notes = "Early completion rejected: synthesis/success gates are not finished.",
                        )
                    }
                    return parsed
                }

                override suspend fun internalAllowedTools(snapshot: AgentByteLoop.IterationSnapshot): Set<String> {
                    return phaseAllowedTools(phaseState.currentPhase())
                        .filter { it == "ROOK_LOGICAL_PASS" || it == "BISHOP_REFLECTIVE_PASS" || it == "ROOK_PAWN_JOURNAL_WRITE" || it == "ROOK_PAWN_LTM_PROMOTION" }
                        .toSet()
                }

                override suspend fun overridePieceSelection(
                    snapshot: AgentByteLoop.IterationSnapshot,
                    current: PieceSelection,
                ): PieceSelection? {
                    return if (phaseState.currentPhase() == RolloverPhase.KING_INIT) {
                        PieceSelection(
                            primaryPiece = ChessPiece.KING,
                            role = PieceRole.SAFETY,
                        )
                    } else {
                        null
                    }
                }

                override suspend fun snapshotState(): String {
                    return buildJsonObject {
                        put("dailyRead", JsonPrimitive(phaseState.dailyRead))
                        put("journalRead", JsonPrimitive(phaseState.journalRead))
                        put("longTermRead", JsonPrimitive(phaseState.longTermRead))
                        put("logicalPassComplete", JsonPrimitive(phaseState.logicalPassComplete))
                        put("reflectivePassComplete", JsonPrimitive(phaseState.reflectivePassComplete))
                        put("journalWriteComplete", JsonPrimitive(phaseState.journalWriteComplete))
                        put("ltmPromotionComplete", JsonPrimitive(phaseState.ltmPromotionComplete))
                        put("successGatesVerified", JsonPrimitive(phaseState.successGatesVerified))
                        put("dailyContentLen", JsonPrimitive(phaseState.dailyContent.length))
                        put("journalContextLen", JsonPrimitive(phaseState.journalContext.length))
                        put("longTermContextLen", JsonPrimitive(phaseState.longTermContext.length))
                        put("searchContextLen", JsonPrimitive(phaseState.searchContext.length))
                        put("logicalPassContextLen", JsonPrimitive(phaseState.logicalPassContext.length))
                        put("reflectivePassContextLen", JsonPrimitive(phaseState.reflectivePassContext.length))
                    }.toString()
                }

                override suspend fun executeToolCall(
                    toolCall: AgentByteLoop.ToolCall,
                    snapshot: AgentByteLoop.IterationSnapshot,
                ): AgentByteLoop.StepOutcome {
                    return when (toolCall.toolName) {
                        "read_daily_memory" -> readSupportTool(
                            toolExecutor = toolExecutor,
                            toolName = "read_daily_memory",
                            argsJson = buildJsonObject { put("timestamp", JsonPrimitive(timestamp)) }.toString(),
                            onSuccess = {
                                phaseState.dailyRead = true
                                phaseState.dailyContent = it
                            },
                        )
                        "read_journal" -> readSupportTool(
                            toolExecutor = toolExecutor,
                            toolName = "read_journal",
                            argsJson = "{}",
                            onSuccess = {
                                phaseState.journalRead = true
                                phaseState.journalContext = it
                            },
                        )
                        "read_long_term_memory" -> readSupportTool(
                            toolExecutor = toolExecutor,
                            toolName = "read_long_term_memory",
                            argsJson = "{}",
                            onSuccess = {
                                phaseState.longTermRead = true
                                phaseState.longTermContext = it
                            },
                        )
                        "read_tag_hints" -> readSupportTool(
                            toolExecutor = toolExecutor,
                            toolName = "read_tag_hints",
                            argsJson = argsMapToJson(toolCall.arguments),
                            onSuccess = {
                                phaseState.searchContext = phaseState.searchContext.appendSupportResult("read_tag_hints", it)
                            },
                        )
                        "search_semantic" -> readSupportTool(
                            toolExecutor = toolExecutor,
                            toolName = "search_semantic",
                            argsJson = argsMapToJson(toolCall.arguments),
                            onSuccess = {
                                phaseState.searchContext = phaseState.searchContext.appendSupportResult("search_semantic", it)
                            },
                        )
                        "search_chat_history" -> readSupportTool(
                            toolExecutor = toolExecutor,
                            toolName = "search_chat_history",
                            argsJson = argsMapToJson(toolCall.arguments),
                            onSuccess = {
                                phaseState.searchContext = phaseState.searchContext.appendSupportResult("search_chat_history", it)
                            },
                        )
                        "ROOK_LOGICAL_PASS" -> {
                            val response = eidosApiClient.send(
                                userMessage = buildRolloverLogicalPassPrompt(
                                    timestamp = timestamp,
                                    rolloverId = rolloverId,
                                    phaseState = phaseState,
                                ),
                                promptCacheKey = "optimalx-rollover-$rolloverId",
                                currentSubfolderId = null,
                                currentParentFolderId = null,
                                currentScopeType = "rollover",
                                conversationHistory = emptyList<EidosMessage>(),
                                baseSystemPrompt = buildRolloverSystemPrompt(
                                    phase = RolloverPromptPhase.LOGICAL_PASS,
                                    activePhase = RolloverPhase.ROOK_LOGICAL_PASS,
                                    allowedTools = emptyList(),
                                ),
                                previousResponseId = null,
                            )
                            val text = response.textResponse.trim()
                            phaseState.logicalPassComplete = true
                            phaseState.logicalPassContext = text
                            AgentByteLoop.StepOutcome(
                                llmResponseText = text,
                                notes = "Logical pass completed.",
                            )
                        }
                        "BISHOP_REFLECTIVE_PASS" -> {
                            val response = eidosApiClient.send(
                                userMessage = buildRolloverReflectivePassPrompt(
                                    timestamp = timestamp,
                                    rolloverId = rolloverId,
                                    phaseState = phaseState,
                                ),
                                promptCacheKey = "optimalx-rollover-$rolloverId",
                                currentSubfolderId = null,
                                currentParentFolderId = null,
                                currentScopeType = "rollover",
                                conversationHistory = emptyList<EidosMessage>(),
                                baseSystemPrompt = buildRolloverSystemPrompt(
                                    phase = RolloverPromptPhase.REFLECTIVE_PASS,
                                    activePhase = RolloverPhase.BISHOP_REFLECTIVE_PASS,
                                    allowedTools = emptyList(),
                                ),
                                previousResponseId = null,
                            )
                            val text = response.textResponse.trim()
                            phaseState.reflectivePassComplete = true
                            phaseState.reflectivePassContext = text
                            AgentByteLoop.StepOutcome(
                                llmResponseText = text,
                                notes = "Reflective pass completed.",
                            )
                        }
                        "ROOK_PAWN_JOURNAL_WRITE" -> runJournalWrite(
                            timestamp = timestamp,
                            rolloverId = rolloverId,
                            phaseState = phaseState,
                        )
                        "ROOK_PAWN_LTM_PROMOTION" -> runLtmPromotion(
                            timestamp = timestamp,
                            rolloverId = rolloverId,
                            phaseState = phaseState,
                            setRolloverResponse = { rolloverResponseText = it },
                            setReport = { report = it },
                        )
                        else -> AgentByteLoop.StepOutcome(
                            unrecoverableError = true,
                            notes = "Unsupported rollover loop tool: ${toolCall.toolName}",
                        )
                    }
                }

                override suspend fun runKingClose(
                    snapshot: AgentByteLoop.IterationSnapshot,
                    steps: List<LoopStepResult>,
                ): AgentByteLoop.KingCloseOutcome {
                    return AgentByteLoop.KingCloseOutcome(
                        notes = "Rollover King Close complete (log/tag-hint/memory updates handled by tool-calling flow).",
                    )
                }

                override suspend fun onStepRecorded(
                    step: LoopStepResult,
                    snapshot: AgentByteLoop.IterationSnapshot,
                    prompt: AgentByteLoop.PromptPacket,
                ) {
                    logger.appendStep(snapshot, prompt, step)
                }

                override suspend fun onLoopFinished(result: AgentByteLoop.LoopResult) {
                    logger.appendResult(result)
                }
            },
            maxIterationsOverride = 10,
            maxIterationsByMode = mapOf(OperatingMode.GENERAL to 10),
        )

        if (result.exitReason == AgentByteLoop.ExitReason.UNRECOVERABLE_ERROR &&
            rolloverResponseText.isBlank()
        ) {
            return "ROLLOVER_ERROR|loop_exit=${result.exitReason}"
        }
        return rolloverResponseText
    }

    private suspend fun readSupportTool(
        toolExecutor: RoomToolExecutor,
        toolName: String,
        argsJson: String,
        onSuccess: (String) -> Unit,
    ): AgentByteLoop.StepOutcome {
        return when (val result = toolExecutor.execute(toolName, argsJson)) {
            is ToolExecutionResult.Success -> {
                onSuccess(result.content)
                AgentByteLoop.StepOutcome(notes = "$toolName executed successfully.")
            }
            is ToolExecutionResult.Failure -> AgentByteLoop.StepOutcome(
                unrecoverableError = true,
                notes = "$toolName failed: ${result.message}",
            )
        }
    }

    private fun buildRolloverSynthesisPrompt(
        timestamp: Long,
        rolloverId: String,
        phaseState: RolloverPhaseState,
        target: RolloverSynthesisTarget,
    ): String {
        return buildString {
            append(buildRolloverTaskPrompt(timestamp, rolloverId))
            append("\n\nCurrent synthesis target: ${target.name}\n")
            when (target) {
                RolloverSynthesisTarget.JOURNAL_WRITE -> {
                    append("This step is journal writing only.\n")
                    append("Required output:\n")
                    append("1) Write/update the journal entry via tools as needed.\n")
                    append("2) After write completes, return exactly: JOURNAL_WRITE_OK\n")
                    append("Do not output rollover completion markers in this step.\n")
                }
                RolloverSynthesisTarget.LTM_PROMOTION -> {
                    append("This step is Long-Term Memory promotion and final rollover status.\n")
                    append("On successful completion, respond with EXACT marker format:\n")
                    append("ROLLOVER_OK|journal_read=true|journal_written=true|ltm_promotions=<int>\n")
                    append("where ltm_promotions is the count of write_long_term_memory promotions performed.\n")
                    append("If daily content is empty, return ROLLOVER_EMPTY.\n")
                }
            }
            append("\n\nSupport Context (real-tool reads):\n")
            append("DailyMemoryRead:\n")
            append(phaseState.dailyContent.take(6000))
            append("\n\nJournalRead:\n")
            append(phaseState.journalContext.take(6000))
            append("\n\nLongTermMemoryRead:\n")
            append(phaseState.longTermContext.take(6000))
            append("\n\nSearchContext:\n")
            append(phaseState.searchContext.take(6000))
            append("\n\nLogicalPassContext:\n")
            append(phaseState.logicalPassContext.take(6000))
            append("\n\nReflectivePassContext:\n")
            append(phaseState.reflectivePassContext.take(6000))
            append("\n")
        }
    }

    private fun buildRolloverLogicalPassPrompt(
        timestamp: Long,
        rolloverId: String,
        phaseState: RolloverPhaseState,
    ): String = buildString {
        append("Rollover logical pass for timestamp=$timestamp rollover_id=$rolloverId.\n")
        append("Task: perform operational first-person analysis of what Eidos did and what should persist.\n")
        append("Tag & Hint policy: read_tag_hints is retrieval-only context in rollover; do not attempt tag-hint writes.\n")
        append("Output: logical pass only. No tool call text. No completion marker.\n\n")
        append("DailyMemoryRead:\n")
        append(phaseState.dailyContent.take(6000))
        append("\n\nJournalRead:\n")
        append(phaseState.journalContext.take(6000))
        append("\n\nLongTermMemoryRead:\n")
        append(phaseState.longTermContext.take(6000))
        append("\n\nSearchContext:\n")
        append(phaseState.searchContext.take(6000))
        append("\n")
    }

    private fun buildRolloverReflectivePassPrompt(
        timestamp: Long,
        rolloverId: String,
        phaseState: RolloverPhaseState,
    ): String = buildString {
        append("Rollover reflective pass for timestamp=$timestamp rollover_id=$rolloverId.\n")
        append("Task: first-person reflection on interaction quality/friction/signal.\n")
        append("Output: reflective pass only. No tool call text. No completion marker.\n\n")
        append("DailyMemoryRead:\n")
        append(phaseState.dailyContent.take(6000))
        append("\n\nJournalRead:\n")
        append(phaseState.journalContext.take(6000))
        append("\n\nLongTermMemoryRead:\n")
        append(phaseState.longTermContext.take(6000))
        append("\n\nSearchContext:\n")
        append(phaseState.searchContext.take(6000))
        append("\n")
    }

    private fun buildRolloverDecisionPrompt(
        timestamp: Long,
        rolloverId: String,
        phase: RolloverPhase,
        phaseAllowedTools: List<String>,
        prompt: AgentByteLoop.PromptPacket,
        snapshot: AgentByteLoop.IterationSnapshot,
        phaseState: RolloverPhaseState,
    ): String = buildString {
        append("Rollover decision step.\n")
        append("timestamp=$timestamp rollover_id=$rolloverId iteration=${snapshot.index}\n")
        append("active_phase=${phase.name}\n")
        append("Active piece: ${snapshot.pieceSelection.primaryPiece} (${snapshot.pieceSelection.role})\n")
        append("Supporting pieces: ${snapshot.pieceSelection.supportingPieces.joinToString(",").ifBlank { "(none)" }}\n")
        append("Phase-allowed tools: ${phaseAllowedTools.joinToString(",").ifBlank { "(none)" }}\n")
        append("Policy-allowed tools: ${snapshot.allowedTools.joinToString(",")}\n")
        append("Tag & Hint in rollover is read-only retrieval context via read_tag_hints. Do not attempt upsert/remove tag-hint writes.\n")
        append("State flags: dailyRead=${phaseState.dailyRead}, journalRead=${phaseState.journalRead}, longTermRead=${phaseState.longTermRead}, logicalPassComplete=${phaseState.logicalPassComplete}, reflectivePassComplete=${phaseState.reflectivePassComplete}, journalWriteComplete=${phaseState.journalWriteComplete}, ltmPromotionComplete=${phaseState.ltmPromotionComplete}\n")
        append("Task: select exactly one next action.\n")
        append("Output must be strict JSON, no markdown:\n")
        append("""{"action":"tool|complete","piece":"<piece-name>","tool":"<name-or-empty>","arguments":{},"reasoning":"<full reasoning monologue>"}""")
        append("\nDo not output prose or explanations outside JSON.")
    }

    private fun buildRolloverDecisionRepairPrompt(
        timestamp: Long,
        rolloverId: String,
        phase: RolloverPhase,
        phaseAllowedTools: List<String>,
        rawDecisionText: String,
    ): String = buildString {
        append("Rollover decision JSON repair.\n")
        append("timestamp=$timestamp rollover_id=$rolloverId phase=${phase.name}\n")
        append("Allowed tools: ${phaseAllowedTools.joinToString(",").ifBlank { "(none)" }}\n")
        append("The previous response was not valid JSON. Convert it into exactly one strict JSON object with this schema:\n")
        append("""{"action":"tool|complete","piece":"<piece-name>","tool":"<name-or-empty>","arguments":{},"reasoning":"<full reasoning monologue>"}""")
        append("\nOutput JSON only, no markdown and no extra text.\n")
        append("Previous invalid response:\n")
        append(rawDecisionText.ifBlank { "(empty)" })
    }

    private fun buildRolloverSystemPrompt(
        phase: RolloverPromptPhase,
        activePhase: RolloverPhase,
        activePiece: String = "",
        supportingPieces: List<String> = emptyList(),
        allowedTools: List<String>,
    ): String = buildString {
        append("You are Eidos executing memory rollover inside a Kotlin-guardrailed AgentByte loop: each iteration choose one next tool from the current phase allowlist, or respond complete only when prompts say it is permitted.\n")
        append("Current prompt phase: ${phase.name}. Current rollover phase: ${activePhase.name}.\n")
        when (phase) {
            RolloverPromptPhase.DECISION -> {
                append("Active piece for this iteration: ${activePiece.ifBlank { "UNKNOWN" }}.\n")
                append("Supporting pieces: ${supportingPieces.joinToString(",").ifBlank { "(none)" }}.\n")
                append("Decide exactly one next action for this iteration.\n")
                append("Return strict JSON only.\n")
                append("You may only pick tools from this allowlist: ${allowedTools.joinToString(",").ifBlank { "(none)" }}.\n")
                append("Tag & Hint in rollover is read-only: only `read_tag_hints` is permitted when present in allowlists.\n")
                append("You must include the selected piece in JSON field `piece`.\n")
                append("If rollover is complete, set action=complete.\n")
            }
            RolloverPromptPhase.DECISION_REPAIR -> {
                append("You are repairing a malformed rollover decision.\n")
                append("Output exactly one strict JSON decision object.\n")
                append("No prose, no markdown, no prefixes, no suffixes.\n")
                append("You may only select tools from this allowlist: ${allowedTools.joinToString(",").ifBlank { "(none)" }}.\n")
            }
            RolloverPromptPhase.LOGICAL_PASS -> {
                append("Produce only logical pass text from first-person Eidos perspective.\n")
                append("No tool calls. No JSON wrappers. No completion markers.\n")
            }
            RolloverPromptPhase.REFLECTIVE_PASS -> {
                append("Produce only reflective pass text from first-person Eidos perspective.\n")
                append("No tool calls. No JSON wrappers. No completion markers.\n")
            }
            RolloverPromptPhase.SYNTHESIS_WRITE -> {
                append("Execute synthesis output for rollover.\n")
                when (activePhase) {
                    RolloverPhase.ROOK_PAWN_JOURNAL_WRITE -> {
                        append("This is the journal-write synthesis step.\n")
                        append("Write journal entry content and include rollover_id when writing.\n")
                        append("Return exactly JOURNAL_WRITE_OK when done.\n")
                        append("Do not emit ROLLOVER_OK in this step.\n")
                    }
                    RolloverPhase.ROOK_PAWN_LTM_PROMOTION -> {
                        append("This is the Long-Term Memory promotion/finalization step.\n")
                        append("Required final output format:\n")
                        append("ROLLOVER_OK|journal_read=true|journal_written=true|ltm_promotions=<int>\n")
                        append("If no daily content, return ROLLOVER_EMPTY.\n")
                    }
                    else -> {
                        append("Use synthesis output appropriate for the active phase.\n")
                    }
                }
            }
        }
    }

    private fun parseRolloverDecision(
        raw: String,
        allowedTools: List<String>,
        phaseAllowedTools: List<String>,
    ): AgentByteLoop.ToolDecision? {
        val obj = runCatching { decisionJson.parseToJsonElement(raw).jsonObject }.getOrNull() ?: return null
        val action = obj["action"]?.jsonPrimitive?.content?.trim()?.lowercase().orEmpty()
        val piece = obj["piece"]?.jsonPrimitive?.content?.trim().orEmpty()
        val reasoning = obj["reasoning"]?.jsonPrimitive?.content.orEmpty()
        if (action == "complete") {
            return AgentByteLoop.ToolDecision(
                type = AgentByteLoop.DecisionType.EXPLICIT_COMPLETE,
                decisionText = reasoning,
                notes = "LLM marked rollover complete.",
            )
        }
        if (action != "tool") return null
        val toolName = obj["tool"]?.jsonPrimitive?.content?.trim().orEmpty()
        if (toolName.isBlank()) return null
        if (phaseAllowedTools.isNotEmpty() && toolName !in phaseAllowedTools) {
            return AgentByteLoop.ToolDecision(
                type = AgentByteLoop.DecisionType.NO_ACTION,
                decisionText = reasoning,
                notes = "Tool '$toolName' is not permitted for phase gating.",
            )
        }
        val argsObj = (obj["arguments"] as? JsonObject) ?: JsonObject(emptyMap())
        val argsMap = argsObj.mapValues { (_, v) ->
            if (v is JsonPrimitive) {
                v.booleanOrNull ?: v.intOrNull ?: v.doubleOrNull ?: v.content
            } else {
                v.toString()
            }
        }
        val notes = if (toolName in allowedTools) {
            "LLM selected tool '$toolName'."
        } else {
            "LLM selected tool '$toolName' (not in allowlist; loop will enforce policy)."
        }
        return AgentByteLoop.ToolDecision(
            type = AgentByteLoop.DecisionType.TOOL_CALL,
            toolCall = AgentByteLoop.ToolCall(toolName, argsMap),
            decisionText = reasoning,
            notes = notes,
        )
    }

    private suspend fun runJournalWrite(
        timestamp: Long,
        rolloverId: String,
        phaseState: RolloverPhaseState,
    ): AgentByteLoop.StepOutcome {
        val response = eidosApiClient.send(
            userMessage = buildRolloverSynthesisPrompt(
                timestamp = timestamp,
                rolloverId = rolloverId,
                phaseState = phaseState,
                target = RolloverSynthesisTarget.JOURNAL_WRITE,
            ),
            promptCacheKey = "optimalx-rollover-$rolloverId",
            currentSubfolderId = null,
            currentParentFolderId = null,
            currentScopeType = "rollover",
            conversationHistory = emptyList<EidosMessage>(),
            baseSystemPrompt = buildRolloverSystemPrompt(
                phase = RolloverPromptPhase.SYNTHESIS_WRITE,
                activePhase = RolloverPhase.ROOK_PAWN_JOURNAL_WRITE,
                allowedTools = listOf("write_journal_entry"),
            ),
            previousResponseId = null,
        )
        val text = response.textResponse.trim()
        phaseState.journalWriteComplete = true
        return AgentByteLoop.StepOutcome(
            llmResponseText = text,
            notes = "Journal write phase completed.",
        )
    }

    private suspend fun runLtmPromotion(
        timestamp: Long,
        rolloverId: String,
        phaseState: RolloverPhaseState,
        setRolloverResponse: (String) -> Unit,
        setReport: (RolloverReport?) -> Unit,
    ): AgentByteLoop.StepOutcome {
        val response = eidosApiClient.send(
            userMessage = buildRolloverSynthesisPrompt(
                timestamp = timestamp,
                rolloverId = rolloverId,
                phaseState = phaseState,
                target = RolloverSynthesisTarget.LTM_PROMOTION,
            ),
            promptCacheKey = "optimalx-rollover-$rolloverId",
            currentSubfolderId = null,
            currentParentFolderId = null,
            currentScopeType = "rollover",
            conversationHistory = emptyList<EidosMessage>(),
            baseSystemPrompt = buildRolloverSystemPrompt(
                phase = RolloverPromptPhase.SYNTHESIS_WRITE,
                activePhase = RolloverPhase.ROOK_PAWN_LTM_PROMOTION,
                allowedTools = listOf("write_long_term_memory"),
            ),
            previousResponseId = null,
        )
        val text = response.textResponse.trim()
        setRolloverResponse(text)
        phaseState.ltmPromotionComplete = true
        if (text.contains(ROLLOVER_EMPTY_MARKER)) {
            phaseState.successGatesVerified = true
            return AgentByteLoop.StepOutcome(
                isComplete = true,
                llmResponseText = text,
                notes = "Rollover returned ROLLOVER_EMPTY.",
            )
        }
        val report = parseRolloverReport(text)
        setReport(report)
        val okMarker = text.contains(ROLLOVER_OK_MARKER)
        val gateOk = okMarker && report != null &&
            report.journalWritten &&
            report.ltmPromotions >= 0
        if (!gateOk) {
            return AgentByteLoop.StepOutcome(
                unrecoverableError = true,
                llmResponseText = text,
                notes = "Success gates failed: missing/invalid rollover completion markers.",
            )
        }
        phaseState.successGatesVerified = true
        return AgentByteLoop.StepOutcome(
            isComplete = true,
            llmResponseText = text,
            notes = "Synthesis completed and success gates verified.",
        )
    }

    private fun argsMapToJson(args: Map<String, Any?>): String {
        return buildJsonObject {
            args.forEach { (k, v) ->
                when (v) {
                    null -> put(k, JsonPrimitive(""))
                    is Boolean -> put(k, JsonPrimitive(v))
                    is Int -> put(k, JsonPrimitive(v))
                    is Long -> put(k, JsonPrimitive(v))
                    is Double -> put(k, JsonPrimitive(v))
                    is Float -> put(k, JsonPrimitive(v.toDouble()))
                    else -> put(k, JsonPrimitive(v.toString()))
                }
            }
        }.toString()
    }
}

enum class MemoryRolloverStatus {
    SUCCESS,
    FAILED,
    SKIPPED,
}

data class MemoryRolloverResult(
    val status: MemoryRolloverStatus,
    val message: String,
    val dailyCleared: Boolean,
)
private data class RolloverReport(
    val journalRead: Boolean,
    val journalWritten: Boolean,
    val ltmPromotions: Int,
)

private data class RolloverPhaseState(
    var dailyRead: Boolean = false,
    var journalRead: Boolean = false,
    var longTermRead: Boolean = false,
    var logicalPassComplete: Boolean = false,
    var reflectivePassComplete: Boolean = false,
    var journalWriteComplete: Boolean = false,
    var ltmPromotionComplete: Boolean = false,
    var successGatesVerified: Boolean = false,
    var dailyContent: String = "",
    var journalContext: String = "",
    var longTermContext: String = "",
    var searchContext: String = "",
    var logicalPassContext: String = "",
    var reflectivePassContext: String = "",
) {
    fun nextToolName(): String? = when {
        !dailyRead -> "read_daily_memory"
        !logicalPassComplete -> "ROOK_LOGICAL_PASS"
        !reflectivePassComplete && !journalWriteComplete -> "BISHOP_REFLECTIVE_PASS"
        !journalWriteComplete -> "ROOK_PAWN_JOURNAL_WRITE"
        !ltmPromotionComplete -> "ROOK_PAWN_LTM_PROMOTION"
        else -> null
    }

    fun currentPhase(): RolloverPhase = when {
        !dailyRead -> RolloverPhase.KING_INIT
        !logicalPassComplete -> RolloverPhase.ROOK_LOGICAL_PASS
        !reflectivePassComplete && !journalWriteComplete -> RolloverPhase.BISHOP_REFLECTIVE_PASS
        !journalWriteComplete -> RolloverPhase.ROOK_PAWN_JOURNAL_WRITE
        !ltmPromotionComplete -> RolloverPhase.ROOK_PAWN_LTM_PROMOTION
        else -> RolloverPhase.KING_VERIFY_CLOSE
    }
}

private fun String.appendSupportResult(tool: String, content: String): String {
    val block = "[$tool]\n${content.trim()}"
    if (this.isBlank()) return block
    return "$this\n\n$block"
}

private val internalPhaseTools = setOf(
    "ROOK_LOGICAL_PASS",
    "BISHOP_REFLECTIVE_PASS",
    "ROOK_PAWN_JOURNAL_WRITE",
    "ROOK_PAWN_LTM_PROMOTION",
)

private enum class RolloverPromptPhase {
    DECISION,
    DECISION_REPAIR,
    LOGICAL_PASS,
    REFLECTIVE_PASS,
    SYNTHESIS_WRITE,
}

private enum class RolloverSynthesisTarget {
    JOURNAL_WRITE,
    LTM_PROMOTION,
}

private enum class RolloverPhase {
    KING_INIT,
    ROOK_READ_DAILY,
    ROOK_KNIGHT_SEARCH,
    ROOK_LOGICAL_PASS,
    BISHOP_REFLECTIVE_PASS,
    ROOK_PAWN_JOURNAL_WRITE,
    ROOK_PAWN_LTM_PROMOTION,
    KING_VERIFY_CLOSE,
}

private data class DailyMemorySnapshot(
    val content: String = "",
    val subfolderIds: List<Long> = emptyList(),
)
