package com.example.optimalx.data.eidos.agentbyte

/**
 * Phase 3 loop runner scaffold.
 * This intentionally keeps execution generic so memory rollover and other
 * multi-step flows can plug into one deterministic loop shell.
 */
object AgentByteLoop {

    enum class ExitReason {
        COMPLETE,
        UNRECOVERABLE_ERROR,
        USER_CANCEL,
        TOKEN_MILESTONE_PAUSE,
        MAX_ITERATIONS_REACHED,
    }

    data class StepOutcome(
        val isComplete: Boolean = false,
        val userCancelled: Boolean = false,
        val unrecoverableError: Boolean = false,
        val pathBlocked: Boolean = false,
        val llmResponseText: String? = null,
        val notes: String = "",
    )

    data class ToolCall(
        val toolName: String,
        val arguments: Map<String, Any?> = emptyMap(),
    )

    enum class DecisionType {
        TOOL_CALL,
        EXPLICIT_COMPLETE,
        NO_ACTION,
    }

    data class ToolDecision(
        val type: DecisionType,
        val toolCall: ToolCall? = null,
        val decisionText: String? = null,
        val forceUnrecoverableOnNoAction: Boolean = false,
        val decisionRequired: Boolean? = null,
        val decisionSkippedSingleInternalHandle: Boolean? = null,
        val decisionParseFailed: Boolean? = null,
        val decisionRepairRetryAttempted: Boolean? = null,
        val decisionRepairRetrySucceeded: Boolean? = null,
        val notes: String = "",
    )

    data class PromptPacket(
        val modePlan: ModeBoardScanPlan,
        val pieceSelection: PieceSelection,
        val allowedTools: List<String>,
    )

    data class IterationSnapshot(
        val index: Int,
        val board: BoardState,
        val kingWarnings: Set<KingWarningReason>,
        val pieceSelection: PieceSelection,
        val allowedTools: List<String>,
    )

    data class LoopResult(
        val exitReason: ExitReason,
        val steps: List<LoopStepResult>,
        val iterations: Int,
        val lastBoard: BoardState?,
        val notes: String = "",
    )

    data class KingCloseOutcome(
        val notes: String = "",
        val unrecoverableError: Boolean = false,
    )

    interface LoopEngine {
        // Rebuild the board every iteration from live app state.
        // This is where operating mode, scope, token counters, and intent signals are sourced.
        suspend fun assembleBoardState(iteration: Int, previous: LoopStepResult?): BoardState

        // Build the model-facing packet (prompt + tool context) for this exact board snapshot.
        // The loop computes policy and allowlists first; engine uses them to shape prompt text.
        suspend fun buildPrompt(snapshot: IterationSnapshot): PromptPacket

        // Decide one next action (single-step tool call) for this iteration.
        // EXPLICIT_COMPLETE should only be returned when the model explicitly signals done.
        // NO_ACTION means no callable step was produced (ambiguous/non-terminal).
        suspend fun chooseToolCall(prompt: PromptPacket, snapshot: IterationSnapshot): ToolDecision

        // Execute the chosen tool call and return structured control signals
        // (complete, blocked, cancelled, unrecoverable, notes).
        suspend fun executeToolCall(toolCall: ToolCall, snapshot: IterationSnapshot): StepOutcome

        // Optional engine-owned state snapshot for training/audit logs.
        // Implementations can return compact JSON or plain text.
        suspend fun snapshotState(): String? = null

        // Runs deterministic post-success housekeeping (King Close phase).
        // Typical tools: write_log_entry, upsert_tag_hint, update_subfolder_memory_cache, voice_handoff.
        suspend fun runKingClose(snapshot: IterationSnapshot, steps: List<LoopStepResult>): KingCloseOutcome = KingCloseOutcome()

        // Internal engine-only actions that are not Room tools.
        // Used for phase markers/actions like rollover_* handlers.
        suspend fun internalAllowedTools(snapshot: IterationSnapshot): Set<String> = emptySet()
        suspend fun overridePieceSelection(
            snapshot: IterationSnapshot,
            current: PieceSelection,
        ): PieceSelection? = null

        suspend fun onStepRecorded(step: LoopStepResult, snapshot: IterationSnapshot, prompt: PromptPacket) {}

        suspend fun onLoopFinished(result: LoopResult) {}
    }

    // Default safety caps by operating mode.
    // These are intentionally higher for workshop-style work that can involve many file/tool hops.
    val defaultMaxIterationsByMode: Map<OperatingMode, Int> = mapOf(
        OperatingMode.GENERAL to 20,
        OperatingMode.PARENT_FOLDER to 24,
        OperatingMode.SUBFOLDER to 28,
        OperatingMode.PANEL_WORKSHOP to 48,
    )

    suspend fun run(
        engine: LoopEngine,
        // Explicit override for callers that need strict control.
        maxIterationsOverride: Int? = null,
        // Mode-aware defaults used when no explicit override is provided.
        maxIterationsByMode: Map<OperatingMode, Int> = defaultMaxIterationsByMode,
    ): LoopResult {
        val steps = mutableListOf<LoopStepResult>()
        var previousStep: LoopStepResult? = null
        var lastBoard: BoardState? = null
        var iteration = 1
        var effectiveMaxIterations = maxIterationsOverride
        var noActionRecoveryUsed = false

        while (true) {
            // 1) Always rebuild board state; loop is board-driven, not pre-scripted.
            val rawBoard = engine.assembleBoardState(iteration, previousStep)
            val board = rawBoard.copy(
                intentSignals = AgentByteIntentSignals.enrich(rawBoard, previousStep),
            )
            lastBoard = board
            if (effectiveMaxIterations == null) {
                effectiveMaxIterations = maxIterationsByMode[board.operatingMode] ?: 24
            }

            if (iteration > (effectiveMaxIterations ?: 24)) {
                break
            }

            // 2) Classify situation and select primary/supporting pieces.
            val situation = AgentByteClassifier.classifySituation(board)
            var selection = AgentByteClassifier.selectPiecesForSituation(situation)

            // 3) King safety scan runs every iteration.
            // Not all warnings should force King takeover:
            // - TOKEN_MILESTONE: pause boundary, not piece takeover
            // - ERROR_SIGNAL: prefer Knight reposition first
            // - Destructive/irreversible/system-folder risk: King safety ownership
            val warnings = AgentBytePolicies.kingWarnings(board)
            val requiresKingSafety =
                warnings.contains(KingWarningReason.DESTRUCTIVE_INTENT) ||
                    warnings.contains(KingWarningReason.IRREVERSIBLE_TOOL) ||
                    warnings.contains(KingWarningReason.SYSTEM_FOLDER_MODIFICATION)
            val requiresKnightReposition =
                warnings.contains(KingWarningReason.ERROR_SIGNAL) && !requiresKingSafety

            if (requiresKingSafety) {
                selection = PieceSelection(
                    primaryPiece = ChessPiece.KING,
                    role = PieceRole.SAFETY,
                )
            } else if (requiresKnightReposition) {
                selection = PieceSelection(
                    primaryPiece = ChessPiece.KNIGHT,
                    role = PieceRole.SETUP,
                    supportingPieces = listOf(ChessPiece.ROOK, ChessPiece.BISHOP, ChessPiece.PAWN),
                )
            }

            // 4) Derive exact tools allowed for this piece selection.
            // This is the hard policy boundary: model cannot step outside this list.
            val policyAllowedTools = AgentBytePolicies.toolsForSelection(selection)
            val internalAllowedTools = engine.internalAllowedTools(
                IterationSnapshot(
                    index = iteration,
                    board = board,
                    kingWarnings = warnings,
                    pieceSelection = selection,
                    allowedTools = policyAllowedTools.toList().sorted(),
                ),
            )
            val allowedTools = (policyAllowedTools + internalAllowedTools).toList().sorted()
            val snapshot = IterationSnapshot(
                index = iteration,
                board = board,
                kingWarnings = warnings,
                pieceSelection = selection,
                allowedTools = allowedTools,
            )
            selection = engine.overridePieceSelection(snapshot, selection) ?: selection
            val adjustedAllowedTools = AgentBytePolicies.toolsForSelection(selection)
            val adjustedInternalAllowedTools = engine.internalAllowedTools(
                snapshot.copy(
                    pieceSelection = selection,
                    allowedTools = adjustedAllowedTools.toList().sorted(),
                ),
            )
            val effectiveAllowedTools = (adjustedAllowedTools + adjustedInternalAllowedTools).toList().sorted()
            val effectiveSnapshot = snapshot.copy(
                pieceSelection = selection,
                allowedTools = effectiveAllowedTools,
            )
            val prompt = engine.buildPrompt(effectiveSnapshot)

            // 5) Ask engine for the next single action.
            val decision = engine.chooseToolCall(prompt, effectiveSnapshot)
            if (decision.type == DecisionType.EXPLICIT_COMPLETE) {
                val step = LoopStepResult(
                    kingScanTriggered = warnings.isNotEmpty(),
                    situation = situation,
                    primaryPiece = selection.primaryPiece,
                    primaryRole = selection.role,
                    supportingPieces = selection.supportingPieces,
                    selectedTools = effectiveAllowedTools,
                    calledToolName = "EXPLICIT_COMPLETE",
                    decisionText = decision.decisionText,
                    llmResponseText = decision.decisionText,
                    decisionRequired = decision.decisionRequired,
                    decisionSkippedSingleInternalHandle = decision.decisionSkippedSingleInternalHandle,
                    decisionParseFailed = decision.decisionParseFailed,
                    decisionRepairRetryAttempted = decision.decisionRepairRetryAttempted,
                    decisionRepairRetrySucceeded = decision.decisionRepairRetrySucceeded,
                    kingPath = kingPathForStep(selection.primaryPiece, "EXPLICIT_COMPLETE"),
                    notes = if (decision.notes.isBlank()) "Model explicitly signaled completion." else decision.notes,
                )
                steps += step
                engine.onStepRecorded(step, effectiveSnapshot, prompt)
                return completeWithKingClose(engine, effectiveSnapshot, prompt, steps, iteration, board)
            }
            if (decision.type == DecisionType.NO_ACTION || decision.toolCall == null) {
                if (decision.forceUnrecoverableOnNoAction) {
                    val step = LoopStepResult(
                        kingScanTriggered = warnings.isNotEmpty(),
                        situation = situation,
                        primaryPiece = selection.primaryPiece,
                        primaryRole = selection.role,
                        supportingPieces = selection.supportingPieces,
                        selectedTools = effectiveAllowedTools,
                        calledToolName = "NO_ACTION",
                        decisionText = decision.decisionText,
                        llmResponseText = decision.decisionText,
                        decisionRequired = decision.decisionRequired,
                        decisionSkippedSingleInternalHandle = decision.decisionSkippedSingleInternalHandle,
                        decisionParseFailed = decision.decisionParseFailed,
                        decisionRepairRetryAttempted = decision.decisionRepairRetryAttempted,
                        decisionRepairRetrySucceeded = decision.decisionRepairRetrySucceeded,
                        kingPath = kingPathForStep(selection.primaryPiece, "NO_ACTION"),
                        notes = if (decision.notes.isBlank()) "No action returned and marked unrecoverable." else decision.notes,
                    )
                    steps += step
                    engine.onStepRecorded(step, effectiveSnapshot, prompt)
                    val result = LoopResult(
                        exitReason = ExitReason.UNRECOVERABLE_ERROR,
                        steps = steps,
                        iterations = iteration,
                        lastBoard = board,
                        notes = "Model returned no actionable tool call and decision requested unrecoverable exit.",
                    )
                    engine.onLoopFinished(result)
                    return result
                }
                if (!noActionRecoveryUsed) {
                    noActionRecoveryUsed = true
                    val recoveryStep = LoopStepResult(
                        kingScanTriggered = warnings.isNotEmpty(),
                        situation = SituationType.TROUBLESHOOT,
                        primaryPiece = ChessPiece.KNIGHT,
                        primaryRole = PieceRole.SETUP,
                        supportingPieces = listOf(ChessPiece.ROOK, ChessPiece.BISHOP, ChessPiece.PAWN),
                        selectedTools = AgentBytePolicies.toolsForPiece(ChessPiece.KNIGHT).toList().sorted(),
                        calledToolName = "KNIGHT_RECOVERY",
                        decisionText = decision.decisionText,
                        decisionRequired = decision.decisionRequired,
                        decisionSkippedSingleInternalHandle = decision.decisionSkippedSingleInternalHandle,
                        decisionParseFailed = decision.decisionParseFailed,
                        decisionRepairRetryAttempted = decision.decisionRepairRetryAttempted,
                        decisionRepairRetrySucceeded = decision.decisionRepairRetrySucceeded,
                        kingPath = null,
                        notes = if (decision.notes.isBlank()) {
                            "No action returned; forcing one Knight reposition recovery iteration."
                        } else {
                            "No action returned (${decision.notes}); forcing one Knight reposition recovery iteration."
                        },
                    )
                    steps += recoveryStep
                    engine.onStepRecorded(recoveryStep, effectiveSnapshot, prompt)
                    previousStep = recoveryStep
                    iteration += 1
                    continue
                }
                val step = LoopStepResult(
                    kingScanTriggered = warnings.isNotEmpty(),
                    situation = situation,
                    primaryPiece = selection.primaryPiece,
                    primaryRole = selection.role,
                    supportingPieces = selection.supportingPieces,
                    selectedTools = effectiveAllowedTools,
                    calledToolName = "NO_ACTION",
                    decisionText = decision.decisionText,
                    llmResponseText = decision.decisionText,
                    decisionRequired = decision.decisionRequired,
                    decisionSkippedSingleInternalHandle = decision.decisionSkippedSingleInternalHandle,
                    decisionParseFailed = decision.decisionParseFailed,
                    decisionRepairRetryAttempted = decision.decisionRepairRetryAttempted,
                    decisionRepairRetrySucceeded = decision.decisionRepairRetrySucceeded,
                    kingPath = kingPathForStep(selection.primaryPiece, "NO_ACTION"),
                    notes = if (decision.notes.isBlank()) "No action returned; treating as non-terminal ambiguity." else decision.notes,
                )
                steps += step
                engine.onStepRecorded(step, effectiveSnapshot, prompt)
                val result = LoopResult(
                    exitReason = ExitReason.UNRECOVERABLE_ERROR,
                    steps = steps,
                    iterations = iteration,
                    lastBoard = board,
                    notes = "Model returned no actionable tool call and no explicit completion signal.",
                )
                engine.onLoopFinished(result)
                return result
            }
            val toolCall = decision.toolCall

            // 6) Enforce allowlist before execution.
            // If proposed tool is outside policy, stop immediately.
            if (toolCall.toolName !in effectiveAllowedTools) {
                val step = LoopStepResult(
                    kingScanTriggered = warnings.isNotEmpty(),
                    situation = situation,
                    primaryPiece = selection.primaryPiece,
                    primaryRole = selection.role,
                    supportingPieces = selection.supportingPieces,
                    selectedTools = effectiveAllowedTools,
                    calledToolName = toolCall.toolName,
                    calledToolArguments = toolCall.arguments.toString(),
                    decisionText = decision.decisionText,
                    llmResponseText = decision.decisionText,
                    kingPath = kingPathForStep(selection.primaryPiece, toolCall.toolName),
                    notes = "Blocked disallowed tool '${toolCall.toolName}'.",
                )
                steps += step
                engine.onStepRecorded(step, effectiveSnapshot, prompt)
                val result = LoopResult(
                    exitReason = ExitReason.UNRECOVERABLE_ERROR,
                    steps = steps,
                    iterations = iteration,
                    lastBoard = board,
                    notes = "Tool was not in allowlist for selected piece.",
                )
                engine.onLoopFinished(result)
                return result
            }

            // 7) Execute the approved tool call and persist iteration trace.
            val stateBefore = engine.snapshotState()
            val outcome = engine.executeToolCall(toolCall, effectiveSnapshot)
            val stateAfter = engine.snapshotState()
            val step = LoopStepResult(
                kingScanTriggered = warnings.isNotEmpty(),
                situation = situation,
                primaryPiece = selection.primaryPiece,
                primaryRole = selection.role,
                supportingPieces = selection.supportingPieces,
                selectedTools = effectiveAllowedTools,
                calledToolName = toolCall.toolName,
                calledToolArguments = if (toolCall.arguments.isEmpty()) null else toolCall.arguments.toString(),
                decisionText = decision.decisionText,
                llmResponseText = outcome.llmResponseText,
                stateBefore = stateBefore,
                stateAfter = stateAfter,
                decisionRequired = decision.decisionRequired,
                decisionSkippedSingleInternalHandle = decision.decisionSkippedSingleInternalHandle,
                decisionParseFailed = decision.decisionParseFailed,
                decisionRepairRetryAttempted = decision.decisionRepairRetryAttempted,
                decisionRepairRetrySucceeded = decision.decisionRepairRetrySucceeded,
                kingPath = kingPathForStep(selection.primaryPiece, toolCall.toolName),
                notes = outcome.notes,
            )
            steps += step
            engine.onStepRecorded(step, effectiveSnapshot, prompt)
            previousStep = step

            // 8) Deterministic exits in priority order.
            if (outcome.userCancelled) {
                val result = LoopResult(
                    exitReason = ExitReason.USER_CANCEL,
                    steps = steps,
                    iterations = iteration,
                    lastBoard = board,
                )
                engine.onLoopFinished(result)
                return result
            }

            // Token milestone is a pause boundary, not a King piece takeover.
            if (warnings.contains(KingWarningReason.TOKEN_MILESTONE)) {
                val result = LoopResult(
                    exitReason = ExitReason.TOKEN_MILESTONE_PAUSE,
                    steps = steps,
                    iterations = iteration,
                    lastBoard = board,
                )
                engine.onLoopFinished(result)
                return result
            }

            if (outcome.unrecoverableError) {
                val result = LoopResult(
                    exitReason = ExitReason.UNRECOVERABLE_ERROR,
                    steps = steps,
                    iterations = iteration,
                    lastBoard = board,
                )
                engine.onLoopFinished(result)
                return result
            }

            if (outcome.isComplete) {
                return completeWithKingClose(engine, effectiveSnapshot, prompt, steps, iteration, board)
            }

            iteration += 1
        }

        // Safety cap: loop never runs unbounded.
        val result = LoopResult(
            exitReason = ExitReason.MAX_ITERATIONS_REACHED,
            steps = steps,
            iterations = iteration - 1,
            lastBoard = lastBoard,
        )
        engine.onLoopFinished(result)
        return result
    }

    private fun kingPathForStep(primaryPiece: ChessPiece, calledToolName: String?): String? {
        if (primaryPiece != ChessPiece.KING) return null
        return if (calledToolName == "read_daily_memory") "awareness" else "safety"
    }

    private suspend fun completeWithKingClose(
        engine: LoopEngine,
        snapshot: IterationSnapshot,
        prompt: PromptPacket,
        steps: MutableList<LoopStepResult>,
        iteration: Int,
        board: BoardState,
    ): LoopResult {
        val kingCloseIteration = iteration + 1
        val closeOutcome = engine.runKingClose(snapshot, steps.toList())
        steps += LoopStepResult(
            kingScanTriggered = snapshot.kingWarnings.isNotEmpty(),
            situation = SituationType.DIRECT_ACTION,
            primaryPiece = ChessPiece.KING,
            primaryRole = PieceRole.SAFETY,
            selectedTools = kingCloseToolsSorted(),
            calledToolName = "KING_CLOSE",
            notes = if (closeOutcome.notes.isBlank()) "King Close housekeeping executed." else closeOutcome.notes,
        )
        engine.onStepRecorded(
            steps.last(),
            snapshot.copy(index = kingCloseIteration),
            prompt,
        )
        if (closeOutcome.unrecoverableError) {
            val result = LoopResult(
                exitReason = ExitReason.UNRECOVERABLE_ERROR,
                steps = steps,
                iterations = kingCloseIteration,
                lastBoard = board,
                notes = "King Close failed after completion signal.",
            )
            engine.onLoopFinished(result)
            return result
        }
        val result = LoopResult(
            exitReason = ExitReason.COMPLETE,
            steps = steps,
            iterations = kingCloseIteration,
            lastBoard = board,
        )
        engine.onLoopFinished(result)
        return result
    }

    private fun kingCloseToolsSorted(): List<String> = AgentBytePolicies.kingCloseAllowlist.toList().sorted()
}
