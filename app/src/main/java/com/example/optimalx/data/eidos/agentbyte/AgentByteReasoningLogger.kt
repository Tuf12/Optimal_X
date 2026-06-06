package com.example.optimalx.data.eidos.agentbyte

import com.example.optimalx.data.db.AppDatabase
import com.example.optimalx.data.db.SystemFolderNames
import com.example.optimalx.data.model.Note
import com.example.optimalx.data.model.Subfolder
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter

class AgentByteReasoningLogger(
    private val db: AppDatabase,
    private val scopeType: String,
    private val scopeId: Long?,
    private val promptLabel: String,
    private val runId: String = "ab_${System.currentTimeMillis()}",
) {
    private var started = false
    private val recordPrefix = "ABR1|"
    private val json = Json { ignoreUnknownKeys = true }

    suspend fun appendStep(
        snapshot: AgentByteLoop.IterationSnapshot,
        prompt: AgentByteLoop.PromptPacket,
        step: LoopStepResult,
    ) {
        val note = ensureRoutingNote()
        if (!started) {
            started = true
            appendRecord(
                note = note,
                payload = buildJsonObject {
                    put("type", JsonPrimitive("run_start"))
                    put("run_id", JsonPrimitive(runId))
                    put("scope_type", JsonPrimitive(scopeType))
                    put("scope_id", JsonPrimitive(scopeId?.toString() ?: "none"))
                    put("prompt_label", JsonPrimitive(promptLabel))
                    put("timestamp", JsonPrimitive(tsLabel()))
                }.toString(),
            )
        }
        appendRecord(
            note = note,
            payload = buildJsonObject {
                put("type", JsonPrimitive("step"))
                put("run_id", JsonPrimitive(runId))
                put("timestamp", JsonPrimitive(System.currentTimeMillis() / 1000L))
                put("iteration", JsonPrimitive(snapshot.index))
                put("active_piece", JsonPrimitive(step.primaryPiece.name))
                put("king_path", JsonPrimitive(step.kingPath.orEmpty()))

                // Schema-aligned training blocks (app/docs/agentbyte/ROLLOVER_ENGINE.md)
                put("input", buildJsonObject {
                    put("phase", JsonPrimitive(inferPhase(step.calledToolName)))
                    put("user_message", JsonPrimitive(snapshot.board.userMessage))
                    put("mode", JsonPrimitive(prompt.modePlan.mode.name))
                    put("board_state", JsonPrimitive(snapshot.board.toString()))
                    put("available_content", parseObjectOrEmpty(step.stateBefore))
                })
                put("llm_decision", buildJsonObject {
                    put("situation", JsonPrimitive(step.situation.name))
                    put("decision_source", JsonPrimitive("llm"))
                    put("reasoning_text", JsonPrimitive(step.decisionText.orEmpty()))
                    put("selected_tool", JsonPrimitive(step.calledToolName.orEmpty()))
                    put("tool_arguments", parseObjectOrEmpty(step.calledToolArguments))
                    put("decision_required", JsonPrimitive(step.decisionRequired ?: true))
                    put("decision_skipped_single_internal_handle", JsonPrimitive(step.decisionSkippedSingleInternalHandle ?: false))
                    put("decision_parse_failed", JsonPrimitive(step.decisionParseFailed ?: false))
                    put("decision_repair_retry_attempted", JsonPrimitive(step.decisionRepairRetryAttempted ?: false))
                    put("decision_repair_retry_succeeded", JsonPrimitive(step.decisionRepairRetrySucceeded ?: false))
                })
                put("tool_execution", buildJsonObject {
                    put("tool_name", JsonPrimitive(step.calledToolName.orEmpty()))
                    put("arguments", parseObjectOrEmpty(step.calledToolArguments))
                    put("result_summary", JsonPrimitive(step.notes))
                    put(
                        "status",
                        JsonPrimitive(
                            when {
                                step.calledToolName == "NO_ACTION" -> "no_action"
                                step.calledToolName == "EXPLICIT_COMPLETE" -> "complete"
                                step.notes.contains("failed", ignoreCase = true) ||
                                    step.notes.contains("blocked", ignoreCase = true) -> "failure"
                                else -> "success"
                            },
                        ),
                    )
                })
                put("state_delta", diffState(step.stateBefore, step.stateAfter))
                put("assistant_response_full", JsonPrimitive(step.llmResponseText.orEmpty().ifBlank { step.decisionText.orEmpty() }))
                put("training_flags", buildJsonObject {
                    put("use_for_piece_training", JsonPrimitive(true))
                    put("use_for_tool_training", JsonPrimitive(true))
                    put("use_for_argument_training", JsonPrimitive(true))
                    put("use_for_response_training", JsonPrimitive(false))
                    put("use_for_journal_training", JsonPrimitive(false))
                })

                // Legacy/compat fields retained for existing parser/UI.
                put("king_scan", JsonPrimitive(kingLine(snapshot)))
                put("board_signals", buildJsonArray {
                    snapshot.board.intentSignals.forEach { add(JsonPrimitive(it)) }
                })
                put("selected_piece", JsonPrimitive(step.primaryPiece.name))
                put("selected_role", JsonPrimitive(step.primaryRole.name))
                put("supporting_pieces", buildJsonArray {
                    step.supportingPieces.forEach { add(JsonPrimitive(it.name)) }
                })
                put("selected_tools", buildJsonArray {
                    step.selectedTools.forEach { add(JsonPrimitive(it)) }
                })
                put("called_tool_name", JsonPrimitive(step.calledToolName.orEmpty()))
                put("called_tool_arguments", JsonPrimitive(step.calledToolArguments.orEmpty()))
                put("decision_text", JsonPrimitive(step.decisionText.orEmpty()))
                put("outcome", JsonPrimitive(step.notes))
                put("llm_response_text", JsonPrimitive(step.llmResponseText.orEmpty()))
                put("king_path", JsonPrimitive(step.kingPath.orEmpty()))
                put("decision_required", JsonPrimitive(step.decisionRequired ?: true))
                put("decision_skipped_single_internal_handle", JsonPrimitive(step.decisionSkippedSingleInternalHandle ?: false))
                put("decision_parse_failed", JsonPrimitive(step.decisionParseFailed ?: false))
                put("decision_repair_retry_attempted", JsonPrimitive(step.decisionRepairRetryAttempted ?: false))
                put("decision_repair_retry_succeeded", JsonPrimitive(step.decisionRepairRetrySucceeded ?: false))
                put("state_before", JsonPrimitive(step.stateBefore.orEmpty()))
                put("state_after", JsonPrimitive(step.stateAfter.orEmpty()))
                put("prompt_packet", buildJsonObject {
                    put("mode", JsonPrimitive(prompt.modePlan.mode.name))
                    put("description", JsonPrimitive(prompt.modePlan.description))
                    put("recommended_opening_tools", buildJsonArray {
                        prompt.modePlan.recommendedOpeningTools.forEach { add(JsonPrimitive(it)) }
                    })
                    put("piece_selection", buildJsonObject {
                        put("primary_piece", JsonPrimitive(prompt.pieceSelection.primaryPiece.name))
                        put("role", JsonPrimitive(prompt.pieceSelection.role.name))
                        put("supporting_pieces", buildJsonArray {
                            prompt.pieceSelection.supportingPieces.forEach { add(JsonPrimitive(it.name)) }
                        })
                    })
                    put("allowed_tools", buildJsonArray {
                        prompt.allowedTools.forEach { add(JsonPrimitive(it)) }
                    })
                })
            }.toString(),
        )
    }

    suspend fun appendResult(result: AgentByteLoop.LoopResult) {
        val note = ensureRoutingNote()
        appendRecord(
            note = note,
            payload = buildJsonObject {
                put("type", JsonPrimitive("run_end"))
                put("run_id", JsonPrimitive(runId))
                put("timestamp", JsonPrimitive(tsLabel()))
                put("exit_reason", JsonPrimitive(result.exitReason.name))
                put("iterations", JsonPrimitive(result.iterations))
                put("notes", JsonPrimitive(result.notes))
            }.toString(),
        )
    }

    suspend fun appendExternalEvent(
        toolName: String,
        notes: String,
        stateBefore: String = "",
        stateAfter: String = "",
    ) {
        val note = ensureRoutingNote()
        if (!started) {
            started = true
            appendRecord(
                note = note,
                payload = buildJsonObject {
                    put("type", JsonPrimitive("run_start"))
                    put("run_id", JsonPrimitive(runId))
                    put("scope_type", JsonPrimitive(scopeType))
                    put("scope_id", JsonPrimitive(scopeId?.toString() ?: "none"))
                    put("prompt_label", JsonPrimitive(promptLabel))
                    put("timestamp", JsonPrimitive(tsLabel()))
                }.toString(),
            )
        }
        appendRecord(
            note = note,
            payload = buildJsonObject {
                put("type", JsonPrimitive("step"))
                put("run_id", JsonPrimitive(runId))
                put("timestamp", JsonPrimitive(System.currentTimeMillis() / 1000L))
                put("iteration", JsonPrimitive(1))
                put("active_piece", JsonPrimitive("KING"))
                put("king_path", JsonPrimitive(scopeType))
                put("input", buildJsonObject {
                    put("phase", JsonPrimitive(inferPhase(toolName)))
                    put("user_message", JsonPrimitive(""))
                    put("mode", JsonPrimitive("GENERAL"))
                    put("board_state", JsonPrimitive("external_indexing"))
                    put("available_content", parseObjectOrEmpty(stateBefore))
                })
                put("llm_decision", buildJsonObject {
                    put("situation", JsonPrimitive("NORMAL"))
                    put("decision_source", JsonPrimitive("external"))
                    put("reasoning_text", JsonPrimitive(notes))
                    put("selected_tool", JsonPrimitive(toolName))
                    put("tool_arguments", JsonObject(emptyMap()))
                    put("decision_required", JsonPrimitive(false))
                    put("decision_skipped_single_internal_handle", JsonPrimitive(true))
                    put("decision_parse_failed", JsonPrimitive(false))
                    put("decision_repair_retry_attempted", JsonPrimitive(false))
                    put("decision_repair_retry_succeeded", JsonPrimitive(false))
                })
                put("tool_execution", buildJsonObject {
                    put("tool_name", JsonPrimitive(toolName))
                    put("arguments", JsonObject(emptyMap()))
                    put("result_summary", JsonPrimitive(notes))
                    put("status", JsonPrimitive("success"))
                })
                put("state_delta", diffState(stateBefore, stateAfter))
                put("assistant_response_full", JsonPrimitive(notes))
                put("training_flags", buildJsonObject {
                    put("use_for_piece_training", JsonPrimitive(true))
                    put("use_for_tool_training", JsonPrimitive(true))
                    put("use_for_argument_training", JsonPrimitive(true))
                    put("use_for_response_training", JsonPrimitive(false))
                    put("use_for_journal_training", JsonPrimitive(false))
                })
                put("called_tool_name", JsonPrimitive(toolName))
                put("called_tool_arguments", JsonPrimitive("{}"))
                put("decision_text", JsonPrimitive(notes))
                put("outcome", JsonPrimitive(notes))
                put("llm_response_text", JsonPrimitive(notes))
                put("decision_required", JsonPrimitive(false))
                put("decision_skipped_single_internal_handle", JsonPrimitive(true))
                put("decision_parse_failed", JsonPrimitive(false))
                put("decision_repair_retry_attempted", JsonPrimitive(false))
                put("decision_repair_retry_succeeded", JsonPrimitive(false))
                put("state_before", JsonPrimitive(stateBefore))
                put("state_after", JsonPrimitive(stateAfter))
            }.toString(),
        )
    }

    suspend fun appendExternalResult(
        exitReason: String,
        notes: String,
        iterations: Int = 1,
    ) {
        val note = ensureRoutingNote()
        appendRecord(
            note = note,
            payload = buildJsonObject {
                put("type", JsonPrimitive("run_end"))
                put("run_id", JsonPrimitive(runId))
                put("timestamp", JsonPrimitive(tsLabel()))
                put("exit_reason", JsonPrimitive(exitReason))
                put("iterations", JsonPrimitive(iterations))
                put("notes", JsonPrimitive(notes))
            }.toString(),
        )
    }

    private fun kingLine(snapshot: AgentByteLoop.IterationSnapshot): String {
        if (snapshot.kingWarnings.isEmpty()) return "King scans — clean"
        return "King scans — warnings: ${snapshot.kingWarnings.joinToString(",")}"
    }

    private suspend fun ensureRoutingNote(): Note {
        val now = System.currentTimeMillis()
        val resolvedParentId = resolveParentForScope()
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

    private suspend fun resolveParentForScope(): Long? {
        return when (scopeType.lowercase()) {
            "parent" -> scopeId
            "subfolder", "panel_workshop", "quick_notes_day" -> {
                val sid = scopeId ?: return null
                db.subfolderDao().getById(sid)?.parentFolderId
            }
            else -> null
        }
    }

    private suspend fun ensureParentReasoningSubfolder(parentId: Long, now: Long): Subfolder {
        val existing = db.subfolderDao().getAllByParentOnce(parentId)
            .firstOrNull { it.deletedAt == null && it.name == SystemFolderNames.PARENT_REASONING_SUBFOLDER }
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

    private suspend fun appendRecord(note: Note, payload: String) {
        val line = "$recordPrefix$payload"
        val merged = if (note.content.isBlank()) line else note.content + "\n" + line
        db.noteDao().update(note.copy(content = merged, updatedAt = System.currentTimeMillis()))
    }

    private fun parseObjectOrEmpty(raw: String?): JsonObject {
        if (raw.isNullOrBlank()) return JsonObject(emptyMap())
        return runCatching { json.parseToJsonElement(raw).jsonObject }.getOrElse { JsonObject(emptyMap()) }
    }

    private fun diffState(beforeRaw: String?, afterRaw: String?): JsonObject {
        val before = parseObjectOrEmpty(beforeRaw)
        val after = parseObjectOrEmpty(afterRaw)
        val changed = linkedMapOf<String, JsonPrimitive>()
        after.forEach { (k, v) ->
            if (before[k] != v) changed[k] = JsonPrimitive("updated")
        }
        return JsonObject(changed)
    }

    private fun inferPhase(toolName: String?): String = when (toolName) {
        "read_daily_memory" -> "Read Daily Memory"
        "read_journal" -> "Read Journal"
        "read_long_term_memory" -> "Read Long-Term Memory"
        "read_tag_hints" -> "Read Tag & Hint Index"
        "upsert_tag_hint" -> "Upsert Tag & Hint"
        "remove_tag_hint" -> "Remove Tag & Hint"
        "notify_user" -> "Notify User"
        "ROOK_LOGICAL_PASS" -> "ROOK_LOGICAL_PASS"
        "BISHOP_REFLECTIVE_PASS" -> "BISHOP_REFLECTIVE_PASS"
        "ROOK_PAWN_JOURNAL_WRITE" -> "ROOK_PAWN_JOURNAL_WRITE"
        "ROOK_PAWN_LTM_PROMOTION" -> "ROOK_PAWN_LTM_PROMOTION"
        "KING_CLOSE" -> "KING_VERIFY_CLOSE"
        else -> "Unknown"
    }

    private fun dayKey(): String = DateTimeFormatter.ofPattern("yyyy-MM-dd")
        .withZone(ZoneId.systemDefault())
        .format(Instant.now())

    private fun tsLabel(): String = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss")
        .withZone(ZoneId.systemDefault())
        .format(Instant.now())
}
