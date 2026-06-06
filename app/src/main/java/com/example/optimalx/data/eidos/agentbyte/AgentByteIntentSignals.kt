package com.example.optimalx.data.eidos.agentbyte

import java.util.Locale

/**
 * Deterministic intent-signal enrichment that avoids brittle keyword parsing.
 * Primary source should still be engine-provided signals from board assembly.
 */
object AgentByteIntentSignals {

    fun enrich(board: BoardState, previous: LoopStepResult?): Set<String> {
        if (board.intentSignals.isNotEmpty()) return board.intentSignals

        val signals = linkedSetOf<String>()

        // Safety/setup-first signals
        if (board.hasErrorSignal || board.pathBlocked) {
            signals += "troubleshoot"
            return signals
        }

        // Scope/mode-based intent defaults
        val scope = board.scopeType.lowercase(Locale.US)
        when {
            scope == "rollover" -> signals += "logical_retrieval"
            scope == "panel_workshop" -> signals += "direct_action"
            scope == "subfolder" || scope == "quick_notes_day" -> signals += "direct_action"
            scope == "parent" -> signals += "logical_retrieval"
            else -> {
                when (board.operatingMode) {
                    OperatingMode.PANEL_WORKSHOP,
                    OperatingMode.SUBFOLDER -> signals += "direct_action"
                    OperatingMode.PARENT_FOLDER,
                    OperatingMode.GENERAL -> signals += "logical_retrieval"
                }
            }
        }

        // If we were just in troubleshooting/setup, keep one follow-through signal.
        if (previous?.primaryPiece == ChessPiece.KNIGHT &&
            previous.primaryRole == PieceRole.SETUP &&
            "troubleshoot" !in signals
        ) {
            signals += "logical_retrieval"
        }

        return signals
    }
}
