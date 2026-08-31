package com.example.optimalx.data.eidos

import android.content.Context
import android.util.Log
import com.example.optimalx.data.db.AppDatabase
import com.example.optimalx.data.eidos.model.EidosMessage
import com.example.optimalx.data.eidos.prompt.EidosEntrySurface
import com.example.optimalx.data.eidos.prompt.RolloverPromptBlocks
import com.example.optimalx.data.eidos.prompt.RolloverPromptPhase
import com.example.optimalx.data.eidos.model.ToolExecutionResult
import com.example.optimalx.data.semantic.EmbeddingEngine
import com.example.optimalx.data.semantic.SemanticIndexer
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

/**
 * Phase-driven memory rollover loop (no AgentByte / chess-piece policy).
 */
class RolloverOrchestrator(
    private val context: Context,
    private val database: AppDatabase,
    private val eidosApiClient: EidosApiClient,
    private val embeddingEngine: EmbeddingEngine,
    private val semanticIndexer: SemanticIndexer,
) {
    private val decisionJson = Json { ignoreUnknownKeys = true }

    suspend fun run(
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
        var rolloverResponseText = ""
        val phaseState = RolloverPhaseState(
            dailyRead = preloadedDailyContent.isNotBlank(),
            dailyContent = preloadedDailyContent,
        )
        var iteration = 1
        val maxIterations = 10

        suspend fun finish(
            exitReason: String,
            result: String,
            notes: String = "",
        ): String {
            Log.i(LOG_TAG, "Rollover finished: exit=$exitReason iterations=$iteration notes=$notes")
            return result
        }

        while (iteration <= maxIterations) {
            val phase = phaseState.currentPhase()
            val phaseTools = phaseAllowedTools(phase)
            val decision = chooseToolCall(
                timestamp = timestamp,
                rolloverId = rolloverId,
                iteration = iteration,
                phase = phase,
                phaseAllowedTools = phaseTools,
                phaseState = phaseState,
            )

            when (decision.type) {
                RolloverDecisionType.EXPLICIT_COMPLETE -> {
                    if (phaseState.ltmPromotionComplete && phaseState.successGatesVerified) {
                        return finish("complete", rolloverResponseText)
                    }
                    Log.w(LOG_TAG, "Early completion rejected at iteration $iteration")
                    return finish(
                        exitReason = "early_complete_rejected",
                        result = "ROLLOVER_ERROR|loop_exit=early_complete_rejected",
                        notes = decision.notes,
                    )
                }
                RolloverDecisionType.NO_ACTION -> {
                    val exit = if (decision.forceUnrecoverable) {
                        "decision_parse_failed"
                    } else {
                        "no_action"
                    }
                    Log.w(LOG_TAG, "No actionable decision at iteration $iteration ($exit)")
                    return finish(
                        exitReason = exit,
                        result = "ROLLOVER_ERROR|loop_exit=$exit",
                        notes = decision.notes,
                    )
                }
                RolloverDecisionType.TOOL_CALL -> {
                    val toolCall = decision.toolCall
                    if (toolCall == null) {
                        return finish(
                            exitReason = "missing_tool_call",
                            result = "ROLLOVER_ERROR|loop_exit=missing_tool_call",
                        )
                    }
                    if (toolCall.toolName !in phaseTools) {
                        Log.w(LOG_TAG, "Disallowed tool ${toolCall.toolName} at iteration $iteration")
                        return finish(
                            exitReason = "disallowed_tool",
                            result = "ROLLOVER_ERROR|loop_exit=disallowed_tool",
                        )
                    }
                    val outcome = executeToolCall(
                        toolCall = toolCall,
                        timestamp = timestamp,
                        rolloverId = rolloverId,
                        toolExecutor = toolExecutor,
                        phaseState = phaseState,
                        setRolloverResponse = { rolloverResponseText = it },
                    )
                    if (outcome.unrecoverableError) {
                        return finish(
                            exitReason = "unrecoverable",
                            result = if (rolloverResponseText.isBlank()) {
                                "ROLLOVER_ERROR|loop_exit=unrecoverable"
                            } else {
                                rolloverResponseText
                            },
                            notes = outcome.notes,
                        )
                    }
                    if (outcome.isComplete) {
                        return finish("complete", rolloverResponseText, outcome.notes)
                    }
                }
            }
            iteration++
        }

        Log.w(LOG_TAG, "Max iterations ($maxIterations) reached")
        return finish(
            exitReason = "max_iterations",
            result = if (rolloverResponseText.isBlank()) {
                "ROLLOVER_ERROR|loop_exit=max_iterations"
            } else {
                rolloverResponseText
            },
            notes = "Stopped after $maxIterations iterations.",
        )
    }

    private suspend fun chooseToolCall(
        timestamp: Long,
        rolloverId: String,
        iteration: Int,
        phase: RolloverPhase,
        phaseAllowedTools: List<String>,
        phaseState: RolloverPhaseState,
    ): RolloverToolDecision {
        if (phaseAllowedTools.size == 1) {
            val selectedTool = phaseAllowedTools.first()
            return RolloverToolDecision(
                type = RolloverDecisionType.TOOL_CALL,
                toolCall = RolloverToolCall(selectedTool),
                notes = "Single allowlisted phase action '$selectedTool' selected directly.",
            )
        }
        if (phaseAllowedTools.isEmpty()) {
            return RolloverToolDecision(
                type = RolloverDecisionType.NO_ACTION,
                forceUnrecoverable = true,
                notes = "No tools allowed for phase ${phase.name}.",
            )
        }

        val decisionResponse = eidosApiClient.send(
            userMessage = buildRolloverDecisionPrompt(
                timestamp = timestamp,
                rolloverId = rolloverId,
                iteration = iteration,
                phase = phase,
                phaseAllowedTools = phaseAllowedTools,
                phaseState = phaseState,
            ),
            promptCacheKey = "optimalx-rollover-$rolloverId",
            currentSubfolderId = null,
            currentParentFolderId = null,
            currentScopeType = "rollover",
            conversationHistory = emptyList<EidosMessage>(),
            toolDefinitions = emptyList(),
            internalVolatilePrompt = RolloverPromptBlocks.buildRolloverSystemPrompt(
                phase = RolloverPromptPhase.DECISION,
                activePhase = phase,
                allowedTools = phaseAllowedTools,
            ),
            entrySurface = EidosEntrySurface.INTERNAL,
            previousResponseId = null,
        )
        val raw = decisionResponse.summaryTextOrNull().orEmpty()
        var parsed = parseRolloverDecision(raw, phaseAllowedTools)
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
            internalVolatilePrompt = RolloverPromptBlocks.buildRolloverSystemPrompt(
                phase = RolloverPromptPhase.DECISION_REPAIR,
                activePhase = phase,
                allowedTools = phaseAllowedTools,
            ),
            entrySurface = EidosEntrySurface.INTERNAL,
            previousResponseId = null,
            )
            parsed = parseRolloverDecision(repairResponse.summaryTextOrNull().orEmpty(), phaseAllowedTools)
            if (parsed == null) {
                return RolloverToolDecision(
                    type = RolloverDecisionType.NO_ACTION,
                    decisionText = raw,
                    forceUnrecoverable = true,
                    notes = "Failed to parse rollover decision JSON after one repair retry.",
                )
            }
        }
        if (parsed.type == RolloverDecisionType.EXPLICIT_COMPLETE &&
            !(phaseState.ltmPromotionComplete && phaseState.successGatesVerified)
        ) {
            return RolloverToolDecision(
                type = RolloverDecisionType.NO_ACTION,
                decisionText = parsed.decisionText,
                forceUnrecoverable = true,
                notes = "Early completion rejected: synthesis/success gates are not finished.",
            )
        }
        return parsed
    }

    private suspend fun executeToolCall(
        toolCall: RolloverToolCall,
        timestamp: Long,
        rolloverId: String,
        toolExecutor: RoomToolExecutor,
        phaseState: RolloverPhaseState,
        setRolloverResponse: (String) -> Unit,
    ): RolloverStepOutcome {
        return when (toolCall.toolName) {
            "read_daily_memory" -> readSupportTool(
                toolExecutor = toolExecutor,
                toolName = "read_daily_memory",
                argsJson = buildJsonObject { put("timestamp", JsonPrimitive(timestamp)) }.toString(),
                onSuccess = {
                    phaseState.dailyRead = true
                    phaseState.dailyContent = mergeDailyContentAfterRead(
                        existing = phaseState.dailyContent,
                        readResult = it,
                    )
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
            toolDefinitions = emptyList(),
            internalVolatilePrompt = RolloverPromptBlocks.buildRolloverSystemPrompt(
                phase = RolloverPromptPhase.LOGICAL_PASS,
                activePhase = RolloverPhase.ROOK_LOGICAL_PASS,
                allowedTools = emptyList(),
            ),
            entrySurface = EidosEntrySurface.INTERNAL,
            previousResponseId = null,
                )
                val text = response.summaryTextOrNull().orEmpty()
                phaseState.logicalPassComplete = true
                phaseState.logicalPassContext = text
                RolloverStepOutcome(notes = "Logical pass completed.")
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
            toolDefinitions = emptyList(),
            internalVolatilePrompt = RolloverPromptBlocks.buildRolloverSystemPrompt(
                phase = RolloverPromptPhase.REFLECTIVE_PASS,
                activePhase = RolloverPhase.BISHOP_REFLECTIVE_PASS,
                allowedTools = emptyList(),
            ),
            entrySurface = EidosEntrySurface.INTERNAL,
            previousResponseId = null,
                )
                val text = response.summaryTextOrNull().orEmpty()
                phaseState.reflectivePassComplete = true
                phaseState.reflectivePassContext = text
                RolloverStepOutcome(notes = "Reflective pass completed.")
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
                setRolloverResponse = setRolloverResponse,
            )
            else -> RolloverStepOutcome(
                unrecoverableError = true,
                notes = "Unsupported rollover tool: ${toolCall.toolName}",
            )
        }
    }

    private suspend fun readSupportTool(
        toolExecutor: RoomToolExecutor,
        toolName: String,
        argsJson: String,
        onSuccess: (String) -> Unit,
    ): RolloverStepOutcome {
        return when (val result = toolExecutor.execute(toolName, argsJson)) {
            is ToolExecutionResult.Success -> {
                onSuccess(result.content)
                RolloverStepOutcome(notes = "$toolName executed successfully.")
            }
            is ToolExecutionResult.Failure -> RolloverStepOutcome(
                unrecoverableError = true,
                notes = "$toolName failed: ${result.message}",
            )
        }
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
            4) Write one Eidos self-reflection journal entry (environment/process/discoveries/issues), and include "rollover_id=$rolloverId" in that journal content.
            5) Promote durable facts to Long-Term Memory using write_long_term_memory.
            6) Do NOT clear daily memory.
        """.trimIndent()
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
        iteration: Int,
        phase: RolloverPhase,
        phaseAllowedTools: List<String>,
        phaseState: RolloverPhaseState,
    ): String = buildString {
        append("Rollover decision step.\n")
        append("timestamp=$timestamp rollover_id=$rolloverId iteration=$iteration\n")
        append("active_phase=${phase.name}\n")
        append("Phase-allowed tools: ${phaseAllowedTools.joinToString(",").ifBlank { "(none)" }}\n")
        append("State flags: dailyRead=${phaseState.dailyRead}, journalRead=${phaseState.journalRead}, longTermRead=${phaseState.longTermRead}, logicalPassComplete=${phaseState.logicalPassComplete}, reflectivePassComplete=${phaseState.reflectivePassComplete}, journalWriteComplete=${phaseState.journalWriteComplete}, ltmPromotionComplete=${phaseState.ltmPromotionComplete}\n")
        append("Task: select exactly one next action.\n")
        append("Output must be strict JSON, no markdown:\n")
        append("""{"action":"tool|complete","tool":"<name-or-empty>","arguments":{},"reasoning":"<full reasoning monologue>"}""")
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
        append("""{"action":"tool|complete","tool":"<name-or-empty>","arguments":{},"reasoning":"<full reasoning monologue>"}""")
        append("\nOutput JSON only, no markdown and no extra text.\n")
        append("Previous invalid response:\n")
        append(rawDecisionText.ifBlank { "(empty)" })
    }

    private fun parseRolloverDecision(
        raw: String,
        phaseAllowedTools: List<String>,
    ): RolloverToolDecision? {
        val obj = runCatching { decisionJson.parseToJsonElement(raw).jsonObject }.getOrNull() ?: return null
        val action = obj["action"]?.jsonPrimitive?.content?.trim()?.lowercase().orEmpty()
        val reasoning = obj["reasoning"]?.jsonPrimitive?.content.orEmpty()
        if (action == "complete") {
            return RolloverToolDecision(
                type = RolloverDecisionType.EXPLICIT_COMPLETE,
                decisionText = reasoning,
                notes = "LLM marked rollover complete.",
            )
        }
        if (action != "tool") return null
        val toolName = obj["tool"]?.jsonPrimitive?.content?.trim().orEmpty()
        if (toolName.isBlank()) return null
        if (phaseAllowedTools.isNotEmpty() && toolName !in phaseAllowedTools) {
            return RolloverToolDecision(
                type = RolloverDecisionType.NO_ACTION,
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
        return RolloverToolDecision(
            type = RolloverDecisionType.TOOL_CALL,
            toolCall = RolloverToolCall(toolName, argsMap),
            decisionText = reasoning,
            notes = "LLM selected tool '$toolName'.",
        )
    }

    private suspend fun runJournalWrite(
        timestamp: Long,
        rolloverId: String,
        phaseState: RolloverPhaseState,
    ): RolloverStepOutcome {
        eidosApiClient.send(
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
            toolDefinitions = emptyList(),
            internalVolatilePrompt = RolloverPromptBlocks.buildRolloverSystemPrompt(
                phase = RolloverPromptPhase.SYNTHESIS_WRITE,
                activePhase = RolloverPhase.ROOK_PAWN_JOURNAL_WRITE,
                allowedTools = listOf("write_journal_entry"),
            ),
            entrySurface = EidosEntrySurface.INTERNAL,
            previousResponseId = null,
            toolDefinitionsOverride = EidosToolCatalog.toolsByNames("write_journal_entry"),
        )
        phaseState.journalWriteComplete = true
        return RolloverStepOutcome(
            notes = "Journal write phase completed.",
        )
    }

    private suspend fun runLtmPromotion(
        timestamp: Long,
        rolloverId: String,
        phaseState: RolloverPhaseState,
        setRolloverResponse: (String) -> Unit,
    ): RolloverStepOutcome {
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
            toolDefinitions = emptyList(),
            internalVolatilePrompt = RolloverPromptBlocks.buildRolloverSystemPrompt(
                phase = RolloverPromptPhase.SYNTHESIS_WRITE,
                activePhase = RolloverPhase.ROOK_PAWN_LTM_PROMOTION,
                allowedTools = listOf("write_long_term_memory"),
            ),
            entrySurface = EidosEntrySurface.INTERNAL,
            previousResponseId = null,
            toolDefinitionsOverride = EidosToolCatalog.toolsByNames("write_long_term_memory"),
        )
        val text = response.summaryTextOrNull().orEmpty()
        setRolloverResponse(text)
        phaseState.ltmPromotionComplete = true
        if (text.contains(ROLLOVER_EMPTY_MARKER)) {
            phaseState.successGatesVerified = true
            return RolloverStepOutcome(
                isComplete = true,
                notes = "Rollover returned ROLLOVER_EMPTY.",
            )
        }
        val report = parseRolloverReport(text)
        val okMarker = text.contains(ROLLOVER_OK_MARKER)
        val gateOk = okMarker && report != null &&
            report.journalWritten &&
            report.ltmPromotions >= 0
        if (!gateOk) {
            return RolloverStepOutcome(
                unrecoverableError = true,
                notes = "Success gates failed: missing/invalid rollover completion markers.",
            )
        }
        phaseState.successGatesVerified = true
        return RolloverStepOutcome(
            isComplete = true,
            notes = "Synthesis completed and success gates verified.",
        )
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

    companion object {
        private const val LOG_TAG = "OptimalX.Rollover"
        private const val ROLLOVER_OK_MARKER = "ROLLOVER_OK"
        private const val ROLLOVER_EMPTY_MARKER = "ROLLOVER_EMPTY"

        internal fun phaseAllowlistSnapshotForTests(): Map<String, List<String>> {
            return RolloverPhase.entries.associate { phase ->
                phase.name to phaseAllowedTools(phase)
            }
        }

        internal fun phaseAllowedTools(phase: RolloverPhase): List<String> {
            return when (phase) {
                RolloverPhase.KING_INIT -> listOf("read_daily_memory")
                RolloverPhase.ROOK_READ_DAILY -> listOf("read_journal")
                RolloverPhase.ROOK_KNIGHT_SEARCH -> listOf(
                    "read_long_term_memory",
                    "search_semantic",
                    "search_chat_history",
                )
                RolloverPhase.ROOK_LOGICAL_PASS -> listOf(
                    "ROOK_LOGICAL_PASS",
                    "read_journal",
                    "read_long_term_memory",
                    "search_semantic",
                    "search_chat_history",
                )
                RolloverPhase.BISHOP_REFLECTIVE_PASS -> listOf("BISHOP_REFLECTIVE_PASS")
                RolloverPhase.ROOK_PAWN_JOURNAL_WRITE -> listOf("ROOK_PAWN_JOURNAL_WRITE")
                RolloverPhase.ROOK_PAWN_LTM_PROMOTION -> listOf("ROOK_PAWN_LTM_PROMOTION")
                RolloverPhase.KING_VERIFY_CLOSE -> emptyList()
            }
        }
    }
}

private enum class RolloverDecisionType {
    TOOL_CALL,
    EXPLICIT_COMPLETE,
    NO_ACTION,
}

private data class RolloverToolCall(
    val toolName: String,
    val arguments: Map<String, Any?> = emptyMap(),
)

private data class RolloverToolDecision(
    val type: RolloverDecisionType,
    val toolCall: RolloverToolCall? = null,
    val decisionText: String? = null,
    val forceUnrecoverable: Boolean = false,
    val notes: String = "",
)

private data class RolloverStepOutcome(
    val isComplete: Boolean = false,
    val unrecoverableError: Boolean = false,
    val notes: String = "",
)

internal data class RolloverReport(
    val journalRead: Boolean,
    val journalWritten: Boolean,
    val ltmPromotions: Int,
)

internal data class RolloverPhaseState(
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

private val rolloverDailyReadJson = Json { ignoreUnknownKeys = true }

private fun mergeDailyContentAfterRead(existing: String, readResult: String): String {
    val parsedContent = runCatching {
        rolloverDailyReadJson.parseToJsonElement(readResult).jsonObject["content"]?.jsonPrimitive?.content.orEmpty().trim()
    }.getOrDefault("")
    return when {
        existing.isNotBlank() -> existing
        parsedContent.isNotBlank() -> readResult
        else -> readResult
    }
}

enum class RolloverPhase {
    KING_INIT,
    ROOK_READ_DAILY,
    ROOK_KNIGHT_SEARCH,
    ROOK_LOGICAL_PASS,
    BISHOP_REFLECTIVE_PASS,
    ROOK_PAWN_JOURNAL_WRITE,
    ROOK_PAWN_LTM_PROMOTION,
    KING_VERIFY_CLOSE,
}

private enum class RolloverSynthesisTarget {
    JOURNAL_WRITE,
    LTM_PROMOTION,
}
