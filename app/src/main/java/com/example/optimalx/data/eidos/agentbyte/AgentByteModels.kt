package com.example.optimalx.data.eidos.agentbyte

enum class OperatingMode {
    GENERAL,
    PARENT_FOLDER,
    SUBFOLDER,
    PANEL_WORKSHOP,
}

enum class SituationType {
    EMOTIONAL_PERSONAL,
    LOGICAL_RETRIEVAL,
    DIRECT_ACTION,
    BLOCKED_OR_BROKEN,
    TROUBLESHOOT,
    DESTRUCTIVE,
    TOKEN_MILESTONE,
}

enum class ChessPiece {
    PAWN,
    ROOK,
    BISHOP,
    KNIGHT,
    QUEEN,
    KING,
}

enum class PieceRole {
    CAPTURE,
    SETUP,
    SAFETY,
}

data class PieceSelection(
    val primaryPiece: ChessPiece,
    val role: PieceRole,
    val supportingPieces: List<ChessPiece> = emptyList(),
)

data class BoardState(
    val operatingMode: OperatingMode,
    val scopeType: String,
    val scopeId: Long?,
    val userMessage: String,
    // Classifier-facing intent markers produced by orchestration/board scan.
    // Suggested values: "direct_action", "logical_retrieval", "emotional_personal", "troubleshoot".
    val intentSignals: Set<String> = emptySet(),
    val tokenUsage: Long = 0L,
    val tokenMilestoneStep: Long = 200_000L,
    val tokenUsageEstimate: Int = 0,
    val hasErrorSignal: Boolean = false,
    val isDestructiveIntent: Boolean = false,
    val pathBlocked: Boolean = false,
)

data class LoopStepResult(
    val kingScanTriggered: Boolean,
    val situation: SituationType,
    val primaryPiece: ChessPiece,
    val primaryRole: PieceRole,
    val supportingPieces: List<ChessPiece> = emptyList(),
    val selectedTools: List<String> = emptyList(),
    val calledToolName: String? = null,
    val calledToolArguments: String? = null,
    val decisionText: String? = null,
    val llmResponseText: String? = null,
    val stateBefore: String? = null,
    val stateAfter: String? = null,
    val decisionRequired: Boolean? = null,
    val decisionSkippedSingleInternalHandle: Boolean? = null,
    val decisionParseFailed: Boolean? = null,
    val decisionRepairRetryAttempted: Boolean? = null,
    val decisionRepairRetrySucceeded: Boolean? = null,
    val kingPath: String? = null,
    val notes: String = "",
)
