package com.example.optimalx.data.eidos

import com.example.optimalx.data.eidos.model.EidosMessage
import com.example.optimalx.data.eidos.model.EidosRole
import java.util.Locale

object WorkshopEidosModeResolver {

    /** After this many user messages in one conversation, nudge to start a new chat. */
    const val USER_TURN_NUDGE_THRESHOLD: Int = 10

    /** Keep this many recent user+assistant pairs in workshop history (excludes current send). */
    private val debugKeywords = listOf(
        "broken", "bug", "error", "not working", "doesn't work", "doesnt work",
        "crash", "stuck", "doesn't calculate", "doesnt calculate", "not calculating",
        "$0", "scroll", "webview", "preview",
    )
    private val planKeywords = listOf(
        "plan", "spec", "feature", "flow.md", "design.md", "readme",
        "requirements", "should we", "structure.md", "features.md",
    )
    private val chatKeywords = listOf(
        "explain", "what if", "how would", "compare", "without changing",
        "just asking", "no code", "don't change", "dont change",
    )

    /**
     * Heuristic mode when the user has not picked a chip (override wins).
     * Defaults to Chat; debug keywords suggest Edit (QuickJS when phase allows).
     */
    fun suggestMode(
        userMessage: String?,
        userOverride: WorkshopEidosMode?,
        phase: WorkshopProjectPhase? = null,
        updateSection: WorkshopUpdateSection? = null,
    ): WorkshopEidosMode {
        userOverride?.let { return WorkshopEidosMode.normalizeToUserChip(it) }
        when (phase) {
            WorkshopProjectPhase.INTAKE -> return WorkshopEidosMode.CHAT
            WorkshopProjectPhase.SPEC_REVIEW -> {
                val msg = userMessage?.lowercase(Locale.US).orEmpty()
                if (msg.isNotBlank()) {
                    if (chatKeywords.any { msg.contains(it) }) return WorkshopEidosMode.CHAT
                    if (planKeywords.any { msg.contains(it) }) return WorkshopEidosMode.PLAN
                }
                return WorkshopEidosMode.PLAN
            }
            WorkshopProjectPhase.DESIGN_BUILD,
            WorkshopProjectPhase.DESIGN_REVIEW,
            WorkshopProjectPhase.LOGIC_BUILD,
            WorkshopProjectPhase.LOGIC_REVIEW,
            -> {
                val msg = userMessage?.lowercase(Locale.US).orEmpty()
                if (msg.isNotBlank()) {
                    if (chatKeywords.any { msg.contains(it) }) return WorkshopEidosMode.CHAT
                    if (debugKeywords.any { msg.contains(it) } && phase.allowsDebugMode) {
                        return WorkshopEidosMode.EDIT
                    }
                }
                return WorkshopEidosMode.CHAT
            }
            WorkshopProjectPhase.COMPLETE,
            WorkshopProjectPhase.UPDATE,
            -> {
                val msg = userMessage?.lowercase(Locale.US).orEmpty()
                if (msg.isNotBlank()) {
                    if (planKeywords.any { msg.contains(it) }) return WorkshopEidosMode.PLAN
                    if (chatKeywords.any { msg.contains(it) }) return WorkshopEidosMode.CHAT
                    if (debugKeywords.any { msg.contains(it) } && phase.allowsDebugMode) {
                        return WorkshopEidosMode.EDIT
                    }
                }
                return WorkshopEidosMode.CHAT
            }
            null -> Unit
        }
        val msg = userMessage?.lowercase(Locale.US).orEmpty()
        if (msg.isNotBlank()) {
            if (planKeywords.any { msg.contains(it) }) return WorkshopEidosMode.PLAN
            if (chatKeywords.any { msg.contains(it) }) return WorkshopEidosMode.CHAT
        }
        return WorkshopEidosMode.CHAT
    }

    /**
     * Preserves user chip selection; does not auto-upgrade Plan → BUILD_* (primary buttons only).
     * Doc-align passes use PLAN and must not be coerced.
     */
    fun coerceModeForPhase(
        mode: WorkshopEidosMode,
        phase: WorkshopProjectPhase?,
        docAlignScope: WorkshopDocAlignScope? = null,
        activeBuildKickoff: WorkshopBuildKickoff? = null,
    ): WorkshopEidosMode {
        if (docAlignScope != null && mode == WorkshopEidosMode.PLAN) return mode
        if (phase == null) return WorkshopEidosMode.normalizeToUserChip(mode)
        val chip = WorkshopEidosMode.normalizeToUserChip(mode)
        return when (phase) {
            WorkshopProjectPhase.DESIGN_BUILD -> when {
                activeBuildKickoff == WorkshopBuildKickoff.DESIGN && mode.isBuildFamily -> mode
                else -> chip
            }
            WorkshopProjectPhase.LOGIC_BUILD -> when {
                activeBuildKickoff == WorkshopBuildKickoff.LOGIC && mode.isBuildFamily -> mode
                else -> chip
            }
            WorkshopProjectPhase.UPDATE -> when {
                activeBuildKickoff == WorkshopBuildKickoff.PLAN && mode == WorkshopEidosMode.BUILD_PLAN -> mode
                else -> chip
            }
            else -> chip
        }
    }

    /** BUILD_* kickoff modes apply only while [activeBuildKickoff] is set for the one-shot primary action. */
    fun isBuildKickoffModeActive(
        mode: WorkshopEidosMode,
        phase: WorkshopProjectPhase?,
        activeBuildKickoff: WorkshopBuildKickoff? = null,
    ): Boolean {
        if (activeBuildKickoff == null) return false
        return when (activeBuildKickoff) {
            WorkshopBuildKickoff.DESIGN ->
                phase == WorkshopProjectPhase.DESIGN_BUILD &&
                    (mode == WorkshopEidosMode.BUILD_DESIGN || mode == WorkshopEidosMode.BUILD)
            WorkshopBuildKickoff.LOGIC ->
                phase == WorkshopProjectPhase.LOGIC_BUILD &&
                    (mode == WorkshopEidosMode.BUILD_LOGIC || mode == WorkshopEidosMode.BUILD)
            WorkshopBuildKickoff.PLAN ->
                phase == WorkshopProjectPhase.UPDATE && mode == WorkshopEidosMode.BUILD_PLAN
        }
    }

    fun countUserTurns(history: List<EidosMessage>): Int =
        history.count { it.role == EidosRole.USER }

    fun shouldNudgeNewChat(userTurnsInConversation: Int): Boolean =
        userTurnsInConversation >= USER_TURN_NUDGE_THRESHOLD

}
