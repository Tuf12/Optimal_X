package com.example.optimalx.data.eidos.agentbyte

import java.util.Locale

object AgentByteClassifier {

    /**
     * King always opens with board awareness checks; this helper only signals whether
     * a safety condition should be surfaced before normal capture/setup routing.
     */
    fun kingScanTriggered(board: BoardState): Boolean {
        if (board.isDestructiveIntent) return true
        if (board.hasErrorSignal) return true
        if (board.tokenUsage > 0L && board.tokenMilestoneStep > 0L) {
            val crossedMilestone = (board.tokenUsage / board.tokenMilestoneStep) >
                ((board.tokenUsage - 1).coerceAtLeast(0L) / board.tokenMilestoneStep)
            if (crossedMilestone) return true
        }
        return false
    }

    fun classifySituation(board: BoardState): SituationType {
        if (board.tokenUsage > 0L && board.tokenMilestoneStep > 0L && board.tokenUsage % board.tokenMilestoneStep == 0L) {
            return SituationType.TOKEN_MILESTONE
        }
        if (board.isDestructiveIntent) return SituationType.DESTRUCTIVE
        if (board.hasErrorSignal || board.pathBlocked) return SituationType.BLOCKED_OR_BROKEN

        val signals = board.intentSignals.map { it.lowercase(Locale.US) }.toSet()

        if ("emotional_personal" in signals) return SituationType.EMOTIONAL_PERSONAL
        if ("logical_retrieval" in signals) return SituationType.LOGICAL_RETRIEVAL
        if ("direct_action" in signals) return SituationType.DIRECT_ACTION
        if ("troubleshoot" in signals) return SituationType.TROUBLESHOOT

        // If intent signals are missing/ambiguous, do not guess a capture piece.
        // Route to Knight setup so the loop can gather context first, then hand off.
        return SituationType.TROUBLESHOOT
    }

    fun selectPiecesForSituation(situation: SituationType): PieceSelection {
        return when (situation) {
            SituationType.DIRECT_ACTION -> PieceSelection(
                primaryPiece = ChessPiece.QUEEN,
                role = PieceRole.CAPTURE,
            )
            SituationType.LOGICAL_RETRIEVAL -> PieceSelection(
                primaryPiece = ChessPiece.ROOK,
                role = PieceRole.CAPTURE,
                // Rook handles direct logical reads; Knight repositions when blocked and
                // Pawn expands execution options for follow-through actions.
                supportingPieces = listOf(ChessPiece.KNIGHT, ChessPiece.PAWN),
            )
            SituationType.EMOTIONAL_PERSONAL -> PieceSelection(
                primaryPiece = ChessPiece.BISHOP,
                role = PieceRole.CAPTURE,
                // Bishop reads personal/context memory; Knight repositions discovery and
                // Pawn executes write/summarize follow-up actions when needed.
                supportingPieces = listOf(ChessPiece.KNIGHT, ChessPiece.PAWN),
            )
            SituationType.BLOCKED_OR_BROKEN,
            SituationType.TROUBLESHOOT -> PieceSelection(
                primaryPiece = ChessPiece.KNIGHT,
                role = PieceRole.SETUP,
                // Knight sets a new path, then hands off to capture pieces.
                supportingPieces = listOf(ChessPiece.ROOK, ChessPiece.BISHOP, ChessPiece.PAWN),
            )
            SituationType.DESTRUCTIVE,
            SituationType.TOKEN_MILESTONE -> PieceSelection(
                primaryPiece = ChessPiece.KING,
                role = PieceRole.SAFETY,
            )
        }
    }
}
