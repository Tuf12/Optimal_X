package com.example.optimalx.data.eidos

import com.example.optimalx.data.model.ChatMessage

/** Builds persisted intake text from workshop chat (Phase 1+). */
object WorkshopIntakeSummary {

    const val MAX_CHARS: Int = 8000

    fun fromChatMessages(messages: List<ChatMessage>, maxChars: Int = MAX_CHARS): String =
        fromUserTexts(
            texts = messages
                .filter { it.role.equals("user", ignoreCase = true) }
                .map { it.content },
            maxChars = maxChars,
        )

    fun fromUserTexts(texts: List<String>, maxChars: Int = MAX_CHARS): String =
        texts
            .map { it.trim() }
            .filter { it.isNotEmpty() }
            .joinToString(separator = "\n\n")
            .take(maxChars)
            .trim()
}
