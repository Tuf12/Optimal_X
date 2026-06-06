package com.example.optimalx.data.eidos.agentbyte

import com.example.optimalx.data.eidos.EidosIndexFeature
import java.util.Locale

enum class KingWarningReason {
    DESTRUCTIVE_INTENT,
    TOKEN_MILESTONE,
    SYSTEM_FOLDER_MODIFICATION,
    IRREVERSIBLE_TOOL,
    ERROR_SIGNAL,
}

data class ModeBoardScanPlan(
    val mode: OperatingMode,
    val description: String,
    val recommendedOpeningTools: List<String>,
)

object AgentBytePolicies {

    /** ON HOLD — Eidos Index tools; omitted from allowlists while [EidosIndexFeature] is off. */
    private val eidosIndexToolsOnHold = setOf(
        "read_tag_hints",
        "upsert_tag_hint",
        "remove_tag_hint",
        "notify_user",
    )

    private fun withoutIndexTools(tools: Set<String>): Set<String> =
        if (EidosIndexFeature.isActive) tools else tools - eidosIndexToolsOnHold

    // King is split into two policy groups:
    // - Guard: high-risk tools that always require safety confirmation
    // - Close: end-of-loop housekeeping and state finalization
    val kingGuardAllowlist: Set<String> = setOf(
        "move_to_trash",
        "prune_long_term_memory",
        "edit_note_section",
        "clear_daily_memory",
    )

    val kingCloseAllowlist: Set<String> = setOf(
        "read_daily_memory",
        "read_tag_hints",
        "upsert_tag_hint",
        "remove_tag_hint",
        "notify_user",
        "update_subfolder_memory_cache",
        "write_journal_entry",
        "voice_handoff",
    )

    private val pieceToolAllowlist: Map<ChessPiece, Set<String>> = mapOf(
        ChessPiece.PAWN to setOf(
            "write_daily_memory",
            "write_long_term_memory",
            "write_journal_entry",
            "upsert_tag_hint",
            "append_note",
            "describe_image",
            "read_conversation",
        ),
        ChessPiece.ROOK to setOf(
            // Rollover core reads (Rook-led logical path)
            "read_daily_memory",
            "read_long_term_memory",
            "read_journal",
            "read_note",
            "read_file",
            "read_subfolder_memory_cache",
        ),
        ChessPiece.BISHOP to setOf(
            "read_daily_memory",
            "read_long_term_memory",
            "read_journal",
            "read_note",
            "read_subfolder_memory_cache",
            
        ),
        ChessPiece.KNIGHT to setOf(
            // Discovery/search ownership (including rollover verification reads)
            "list_folder_contents",
            "read_tag_hints",
            "search_semantic",
            "search_chat_history",
            "read_log",
            "read_journal",
        ),
        ChessPiece.QUEEN to setOf(
            "read_daily_memory",
            "create_parent_folder",
            "create_subfolder",
            "rename_folder",
            "write_note",
            "append_note",
            "write_quick_note",
            "write_daily_memory",
            "upsert_tag_hint",
            "remove_tag_hint",
        ),
        ChessPiece.KING to kingGuardAllowlist + kingCloseAllowlist,
    )

    private val irreversibleTools: Set<String> = kingGuardAllowlist

    fun toolsForPiece(piece: ChessPiece): Set<String> =
        withoutIndexTools(pieceToolAllowlist[piece].orEmpty())

    fun toolsForSelection(selection: PieceSelection): Set<String> {
        val out = linkedSetOf<String>()
        out += toolsForPiece(selection.primaryPiece)
        selection.supportingPieces.forEach { out += toolsForPiece(it) }
        return out
    }

    fun modeBoardScanPlan(mode: OperatingMode): ModeBoardScanPlan {
        return when (mode) {
            OperatingMode.GENERAL -> ModeBoardScanPlan(
                mode = mode,
                description = "Scan recent conversation and classify user intent; use semantic search for app-wide retrieval.",
                recommendedOpeningTools = listOf("search_semantic", "search_chat_history", "read_daily_memory"),
            )
            OperatingMode.PARENT_FOLDER -> ModeBoardScanPlan(
                mode = mode,
                description = "List subfolders and check parent chat context; use semantic search when needed.",
                recommendedOpeningTools = listOf("list_folder_contents", "search_semantic", "search_chat_history"),
            )
            OperatingMode.SUBFOLDER -> ModeBoardScanPlan(
                mode = mode,
                description = "Scan subfolder operating memory first (behavioral ruleset), then note content.",
                recommendedOpeningTools = listOf("read_subfolder_memory_cache", "read_note", "search_semantic"),
            )
            OperatingMode.PANEL_WORKSHOP -> ModeBoardScanPlan(
                mode = mode,
                description = "Scan workshop context, docs/code state, and any interrupted reasoning trace.",
                recommendedOpeningTools = listOf("read_note", "list_folder_contents", "search_semantic"),
            )
        }
    }

    fun kingWarnings(
        board: BoardState,
        pendingToolName: String? = null,
        modifyingSystemFolder: Boolean = false,
    ): Set<KingWarningReason> {
        val warnings = linkedSetOf<KingWarningReason>()

        if (board.isDestructiveIntent) warnings += KingWarningReason.DESTRUCTIVE_INTENT
        if (board.hasErrorSignal) warnings += KingWarningReason.ERROR_SIGNAL

        if (board.tokenUsage > 0L && board.tokenMilestoneStep > 0L &&
            board.tokenUsage % board.tokenMilestoneStep == 0L
        ) {
            warnings += KingWarningReason.TOKEN_MILESTONE
        }

        val tool = pendingToolName?.trim()?.lowercase(Locale.US)
        if (!tool.isNullOrBlank() && tool in irreversibleTools) {
            warnings += KingWarningReason.IRREVERSIBLE_TOOL
        }

        if (modifyingSystemFolder) warnings += KingWarningReason.SYSTEM_FOLDER_MODIFICATION

        return warnings
    }

    fun inferOperatingMode(scopeType: String): OperatingMode {
        return when (scopeType.lowercase(Locale.US)) {
            "parent" -> OperatingMode.PARENT_FOLDER
            "subfolder", "quick_notes_day" -> OperatingMode.SUBFOLDER
            "panel_workshop" -> OperatingMode.PANEL_WORKSHOP
            else -> OperatingMode.GENERAL
        }
    }
}
