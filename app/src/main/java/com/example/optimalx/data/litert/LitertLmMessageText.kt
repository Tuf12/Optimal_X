package com.example.optimalx.data.litert

import com.google.ai.edge.litertlm.Message

/**
 * User-visible LiteRT-LM assistant text.
 *
 * Gallery contract for streaming:
 * - [Message.toString] yields **deltas** (new tokens only), not cumulative text
 * - UI accumulates: `content = content + message.toString()`
 * - then lightly normalizes (`\\n` → newline)
 *
 * Do not replace the bubble with each delta — that leaves only the last few tokens
 * (classic 2–3 word replies). Do not use [com.google.ai.edge.litertlm.Conversation.renderMessageIntoString]
 * for chat display; it re-renders turn templates.
 */
object LitertLmMessageText {

    /** Raw streaming delta from LiteRT (may be empty). */
    fun streamDelta(message: Message): String = message.toString()

    /**
     * Display text for a **fully accumulated** assistant string (after appending deltas),
     * or for a synchronous [com.google.ai.edge.litertlm.Conversation.sendMessage] result.
     */
    fun displayTextFromRaw(raw: String): String {
        val normalized = normalizeEscapedNewlines(raw)
        val stripped = LitertLmGemmaChatMarkup.stripForDisplay(normalized).trim()
        if (stripped.isEmpty() || isLikelyToolJson(stripped)) return ""
        return stripped
    }

    fun finalDisplayText(message: Message): String =
        displayTextFromRaw(message.toString())

    /**
     * True when the model collapsed into a repeated token ("the the the…").
     * Those replies poison the next turn if we persist them as history.
     */
    fun isDegenerateAssistantText(text: String): Boolean {
        val tokens = text.trim().split(Regex("\\s+")).filter { it.isNotEmpty() }
        if (tokens.size < 8) return false
        val topCount = tokens.groupingBy { it.lowercase() }.eachCount().values.maxOrNull() ?: 0
        return topCount >= 6 && topCount * 5 >= tokens.size * 2
    }

    internal fun normalizeEscapedNewlines(text: String): String =
        text.replace("\\n", "\n")

    internal fun isLikelyToolJson(text: String): Boolean {
        val trimmed = text.trim()
        if (trimmed.startsWith("[") && trimmed.endsWith("]")) return true
        if (!trimmed.startsWith("{")) return false
        return trimmed.contains("\"name\"") &&
            (trimmed.contains("\"arguments\"") || trimmed.contains("\"parameters\""))
    }
}
