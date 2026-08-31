package com.example.optimalx.data.eidos.prompt

import com.example.optimalx.data.eidos.RolloverPhase

/** Fixed task instructions for internal (background) LLM jobs — no chat tool-first rules. */
object EidosInternalPromptBlocks {

    val WORKSHOP_PROJECT_SUMMARY: String = """
        You compress panel workshop spec markdown into a project orientation summary for an AI assistant.
        Output only the summary — no preamble or fences. Max ~600 words.
        Cover purpose, structure, features, user flows, and design constraints.
        Do not include HTML, CSS, or JavaScript. Do not invent features not in the specs.
    """.trimIndent()

    fun taskBlockForProfile(profileId: String): String = when (profileId) {
        EidosScopeProfileIds.INTERNAL_CONTENT_SUMMARY -> WORKSHOP_PROJECT_SUMMARY
        else -> ""
    }
}

enum class RolloverPromptPhase {
    DECISION,
    DECISION_REPAIR,
    LOGICAL_PASS,
    REFLECTIVE_PASS,
    SYNTHESIS_WRITE,
}

object RolloverPromptBlocks {

    fun buildRolloverSystemPrompt(
        phase: RolloverPromptPhase,
        activePhase: RolloverPhase,
        allowedTools: List<String>,
    ): String = buildString {
        append(
            "You are Eidos executing memory rollover inside a Kotlin-guardrailed phase loop: " +
                "each iteration choose one next tool from the current phase allowlist, " +
                "or respond complete only when prompts say it is permitted.\n",
        )
        append("Current prompt phase: ${phase.name}. Current rollover phase: ${activePhase.name}.\n")
        when (phase) {
            RolloverPromptPhase.DECISION -> {
                append("Decide exactly one next action for this iteration.\n")
                append("Return strict JSON only.\n")
                append("You may only pick tools from this allowlist: ${allowedTools.joinToString(",").ifBlank { "(none)" }}.\n")
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
    }.trim()
}
