package com.example.optimalx.data.eidos

import com.example.optimalx.data.eidos.model.EidosMessage
import com.example.optimalx.data.eidos.model.EidosRequestPhase
import com.example.optimalx.data.eidos.model.EidosRole

/**
 * Trims chat history by exchange/char budget without splitting assistant/tool rounds.
 * Main-chat outbound history uses [ConversationOutboundHistory] (verbatim tail + hard cap).
 *
 * Kimi outbound shaping: persist full reasoning in DB/UI, but only send `reasoning_content` to
 * Moonshot during an in-flight tool loop (see [prepareKimiOutboundHistory]).
 */
object EidosHistoryTrimmer {

    data class HistoryBudget(
        val maxUserExchanges: Int,
        val maxChars: Int,
    )

    fun trimHistoryIfNeeded(
        history: List<EidosMessage>,
        budget: HistoryBudget,
    ): List<EidosMessage> {
        if (history.isEmpty()) return history
        if (!isOverBudget(history, budget)) return history

        val exchanges = splitIntoUserExchanges(history)
        if (exchanges.isEmpty()) return history

        val kept = exchanges.toMutableList()
        while (kept.size > 1 && isOverBudget(kept.flatten(), budget)) {
            kept.removeAt(0)
        }
        return kept.flatten()
    }

    /**
     * Drops historical `assistantReasoningContent` from API payloads where safe.
     *
     * Moonshot requires `reasoning_content` on assistant rows that include `tool_calls` when
     * thinking is enabled — never strip those.
     *
     * - [EidosRequestPhase.FULL]: strip reasoning on text-only assistant replies; keep tool-call rows.
     * - [EidosRequestPhase.TOOL_CONTINUATION]: keep current-turn tool-chain reasoning. The active
     *   user message is often only in [com.example.optimalx.data.eidos.model.EidosRequest.userMessage]
     *   (not yet in history), so [lastUserIndex] may be -1 or point at a prior turn.
     */
    fun prepareKimiOutboundHistory(
        history: List<EidosMessage>,
        phase: EidosRequestPhase,
    ): List<EidosMessage> {
        if (history.isEmpty()) return history
        val lastUserIndex = history.indexOfLast { it.role == EidosRole.USER }
        return history.mapIndexed { index, message ->
            if (message.role != EidosRole.ASSISTANT || message.assistantReasoningContent.isNullOrBlank()) {
                message
            } else if (message.assistantToolCalls.isNotEmpty()) {
                message
            } else when (phase) {
                EidosRequestPhase.FULL -> message.copy(assistantReasoningContent = null)
                EidosRequestPhase.TOOL_CONTINUATION -> {
                    val inCurrentTurn = lastUserIndex < 0 || index > lastUserIndex
                    if (inCurrentTurn) message else message.copy(assistantReasoningContent = null)
                }
            }
        }
    }

    /** Read-only tool results whose bodies are safe to stub after the model has acted on them. */
    private val STUBBABLE_READ_TOOLS = setOf(
        "search_semantic",
        "read_file",
        "workshop_read_file",
        "read_conversation",
        "read_dump_edit",
        "read_note",
    )

    /** Do not bother stubbing short results — the overhead isn't worth it. */
    private const val MIN_STUB_CHARS = 400

    /**
     * Bounds tool-result *replay* on outbound history: keeps the most recent [replayRounds] tool
     * rounds verbatim and replaces older bulky read-tool result bodies (see [STUBBABLE_READ_TOOLS])
     * with a short pointer. Write results and recent reads are never stubbed. DB/UI history is
     * unaffected — this only shapes what hits the wire, preventing quadratic replay growth over a
     * long tool loop.
     *
     * A "round" increments at each assistant message carrying tool calls; every following TOOL
     * message belongs to that round.
     */
    fun prepareOutboundHistory(
        history: List<EidosMessage>,
        replayRounds: Int,
    ): List<EidosMessage> {
        if (history.isEmpty() || replayRounds < 1) return history

        var roundCounter = 0
        val roundOfMessage = IntArray(history.size) { -1 }
        history.forEachIndexed { index, message ->
            if (message.role == EidosRole.ASSISTANT && message.assistantToolCalls.isNotEmpty()) {
                roundCounter += 1
            }
            if (message.role == EidosRole.TOOL) {
                roundOfMessage[index] = roundCounter
            }
        }
        if (roundCounter <= replayRounds) return history

        val firstVerbatimRound = roundCounter - replayRounds + 1
        return history.mapIndexed { index, message ->
            if (message.role != EidosRole.TOOL) return@mapIndexed message
            if (roundOfMessage[index] >= firstVerbatimRound) return@mapIndexed message
            val name = message.toolName ?: return@mapIndexed message
            if (name !in STUBBABLE_READ_TOOLS || message.content.length < MIN_STUB_CHARS) {
                return@mapIndexed message
            }
            message.copy(content = stubbedReadResult(name, message.toolCallId))
        }
    }

    private fun stubbedReadResult(toolName: String, toolCallId: String?): String =
        "[Earlier $toolName result — omitted from API replay to save tokens; " +
            "toolCallId=${toolCallId ?: "?"}. Call $toolName again if you still need it.]"

    /** True when history ends with an in-flight assistant tool call or tool result (needs `keep: all`). */
    fun kimiNeedsPreservedThinking(history: List<EidosMessage>): Boolean {
        val last = history.lastOrNull() ?: return false
        return when (last.role) {
            EidosRole.TOOL -> true
            EidosRole.ASSISTANT -> last.assistantToolCalls.isNotEmpty()
            else -> false
        }
    }

    /** User-led segments: each starts with [EidosRole.USER] and runs until the next user message. */
    internal fun splitIntoUserExchanges(history: List<EidosMessage>): List<List<EidosMessage>> {
        if (history.isEmpty()) return emptyList()
        val exchanges = mutableListOf<List<EidosMessage>>()
        var current = mutableListOf<EidosMessage>()
        for (message in history) {
            if (message.role == EidosRole.USER && current.isNotEmpty()) {
                exchanges += current
                current = mutableListOf()
            }
            current += message
        }
        if (current.isNotEmpty()) exchanges += current
        return exchanges
    }

    internal fun isOverBudget(
        history: List<EidosMessage>,
        budget: HistoryBudget,
    ): Boolean {
        val userExchanges = splitIntoUserExchanges(history).size
        return userExchanges > budget.maxUserExchanges ||
            history.sumOf { it.approxChars() } > budget.maxChars
    }

    private fun EidosMessage.approxChars(): Int {
        var chars = content.length
        assistantReasoningContent?.let { chars += it.length }
        assistantToolCalls.forEach { call ->
            chars += call.name.length + call.argumentsJson.length
        }
        return chars
    }
}
