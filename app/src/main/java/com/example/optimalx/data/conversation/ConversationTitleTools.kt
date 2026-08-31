package com.example.optimalx.data.conversation

private val autoTimestampTitleRegex = Regex("^\\d{4}-\\d{2}-\\d{2} — \\d{1,2}:\\d{2} [AP]M$")

fun looksLikeAutoTimestampTitle(title: String): Boolean {
    return autoTimestampTitleRegex.matches(title.trim())
}

fun buildConversationTitleFromText(
    text: String,
    maxWords: Int = 8,
    maxChars: Int = 56,
): String {
    val normalized = text
        .replace(Regex("\\s+"), " ")
        .trim()
        .trim('"', '\'')
    if (normalized.isBlank()) return "Untitled chat"

    val candidate = normalized
        .split(" ")
        .filter { it.isNotBlank() }
        .take(maxWords)
        .joinToString(" ")

    if (candidate.length <= maxChars) return candidate

    val clipped = candidate.take(maxChars).trimEnd()
    val safe = clipped.substringBeforeLast(" ", missingDelimiterValue = clipped).trimEnd()
    return (if (safe.isNotBlank()) safe else clipped) + "..."
}
