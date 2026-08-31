package com.example.optimalx.data.eidos

import com.example.optimalx.data.dao.ChatMessageDao
import com.example.optimalx.data.eidos.model.EidosMessage
import com.example.optimalx.data.model.ChatMessage
import com.example.optimalx.data.model.Conversation
import com.example.optimalx.data.litert.GemmaLocalPolicy

/**
 * Builds API conversation history: verbatim tail with a hard char cap.
 * Older turns come from active-conversation prefetch, not a stored summary.
 * Full [ChatMessage] rows remain in DB for UI and semantic search.
 */
object ConversationOutboundHistory {

    const val OUTBOUND_HARD_CAP_CHARS: Int = 30_000

    suspend fun build(
        chatMessageDao: ChatMessageDao,
        conversation: Conversation,
        activeUserText: String,
        excludeMessageId: Long? = null,
    ): List<EidosMessage> {
        val allMessages = chatMessageDao.getAllByConversation(conversation.id)
        return buildFromMessages(allMessages, activeUserText, excludeMessageId)
    }

    internal fun buildFromMessages(
        allMessages: List<ChatMessage>,
        activeUserText: String,
        excludeMessageId: Long? = null,
    ): List<EidosMessage> {
        val verbatim = excludeActiveTurn(allMessages, activeUserText, excludeMessageId)
            .map { it.toEidosApiMessage() }
        return applyHardCap(verbatim)
    }

    /**
     * Local Gemma transport — decay trim only. See [app/docs/GemmaLocal.md].
     */
    suspend fun buildForLocalGemma(
        chatMessageDao: ChatMessageDao,
        conversation: Conversation,
        activeUserText: String,
        excludeMessageId: Long? = null,
    ): List<EidosMessage> {
        val allMessages = chatMessageDao.getAllByConversation(conversation.id)
        return buildForLocalGemmaFromMessages(allMessages, activeUserText, excludeMessageId)
    }

    internal fun buildForLocalGemmaFromMessages(
        allMessages: List<ChatMessage>,
        activeUserText: String,
        excludeMessageId: Long? = null,
    ): List<EidosMessage> {
        val verbatim = excludeActiveTurn(allMessages, activeUserText, excludeMessageId)
            .map { it.toEidosApiMessage() }
        return GemmaLocalPolicy.trimOutboundHistory(verbatim)
    }

    /**
     * Drop the active user turn (and anything after it) so [activeUserText] is sent once as
     * [com.example.optimalx.data.eidos.model.EidosRequest.userMessage].
     *
     * Text-only matching is not enough on retry/edit: if a prior assistant row was not deleted,
     * the last row is not the user message and the same text would be sent again.
     */
    internal fun excludeActiveTurn(
        messages: List<ChatMessage>,
        userText: String,
        excludeMessageId: Long? = null,
    ): List<ChatMessage> {
        if (messages.isEmpty()) return messages
        if (excludeMessageId != null) {
            val idx = messages.indexOfFirst { it.id == excludeMessageId }
            if (idx >= 0) return messages.take(idx)
        }
        return excludeLatestMatchingUser(messages, userText)
    }

    internal fun excludeLatestMatchingUser(
        messages: List<ChatMessage>,
        userText: String,
    ): List<ChatMessage> {
        if (messages.isEmpty()) return messages
        val trimmedInput = userText.trim()
        return if (messages.last().role == "user" && messages.last().content.trim() == trimmedInput) {
            messages.dropLast(1)
        } else {
            messages
        }
    }

    /** True when an indexed conversation batch still contains the active user line. */
    fun chunkContainsActiveUserTurn(chunkText: String, userText: String): Boolean {
        val trimmed = userText.trim()
        if (trimmed.isEmpty()) return false
        val needle = "user: $trimmed"
        return chunkText.lines().any { it.trim() == needle }
    }

    private fun applyHardCap(outbound: List<EidosMessage>): List<EidosMessage> {
        if (outbound.isEmpty()) return outbound
        if (outbound.sumOf { it.approxChars() } <= OUTBOUND_HARD_CAP_CHARS) return outbound

        val exchanges = EidosHistoryTrimmer.splitIntoUserExchanges(outbound)
        if (exchanges.isEmpty()) return outbound

        val kept = exchanges.toMutableList()
        while (kept.size > 1) {
            val candidate = kept.flatten()
            if (candidate.sumOf { it.approxChars() } <= OUTBOUND_HARD_CAP_CHARS) break
            kept.removeAt(0)
        }
        return kept.flatten()
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
