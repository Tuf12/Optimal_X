package com.example.optimalx.data.eidos

import com.example.optimalx.data.eidos.model.EidosMessage
import com.example.optimalx.data.eidos.model.EidosRole
import com.example.optimalx.data.model.ChatMessage

/** Maps a persisted chat row to an API history message (incl. Kimi reasoning replay). */
fun ChatMessage.toEidosApiMessage(): EidosMessage = EidosMessage(
    role = if (role == "user") EidosRole.USER else EidosRole.ASSISTANT,
    content = content,
    assistantReasoningContent = assistantReasoningContent,
)

/**
 * Builds conversation history for [EidosApiClient.send], excluding the latest user turn when it
 * matches [userText] (that text is sent separately as [EidosRequest.userMessage]).
 */
fun List<ChatMessage>.toEidosApiHistoryExcludingLatestUser(userText: String): List<EidosMessage> {
    if (isEmpty()) return emptyList()
    val trimmedInput = userText.trim()
    val trimmed = if (last().role == "user" && last().content.trim() == trimmedInput) {
        dropLast(1)
    } else {
        this
    }
    return trimmed.map { it.toEidosApiMessage() }
}

/**
 * The initial provider request sends [activeUserMessage] separately as [EidosRequest.userMessage]
 * while [history] excludes that turn ([toEidosApiHistoryExcludingLatestUser]). Tool continuation
 * rounds replay [history] only — without this, round 2+ would see the previous user turn.
 */
fun ensureActiveUserTurnInHistory(
    history: MutableList<EidosMessage>,
    activeUserMessage: String,
) {
    val text = activeUserMessage.trim()
    if (text.isBlank()) return
    val last = history.lastOrNull()
    if (last?.role == EidosRole.USER && last.content.trim() == text) return
    history.add(EidosMessage(role = EidosRole.USER, content = activeUserMessage))
}
