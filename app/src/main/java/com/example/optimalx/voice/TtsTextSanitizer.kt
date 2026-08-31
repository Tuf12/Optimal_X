package com.example.optimalx.voice

/**
 * Converts assistant-rendered text into speech-friendly prose.
 *
 * Chat UI keeps the original Markdown/citations visible, but TTS should not read
 * URL punctuation, source-list formatting, or raw markup from provider web output.
 */
internal fun stripMarkdownForTts(text: String): String {
    var s = text

    s = s.replace(Regex("```(?:\\w+)?\\s*([\\s\\S]*?)```")) { match ->
        match.groupValues[1].trim().replace(Regex("\\s+"), " ")
    }
    s = s.replace(Regex("`([^`]+)`"), "$1")

    s = s.replace(Regex("(?is)\\n+\\s*(sources?|references?|citations?)\\s*:\\s*[\\s\\S]*$"), "")

    s = s.replace(Regex("!\\[([^]]*)]\\([^)]+\\)"), "$1")
    s = s.replace(Regex("\\[([^]]+)]\\((?:https?://|www\\.)[^)]+\\)"), "$1")
    s = s.replace(Regex("\\[(\\d+)]"), "")
    s = s.replace(Regex("https?://\\S+|www\\.\\S+"), "")

    s = s.replace(Regex("<[^>]+>"), " ")
    s = s.replace("&nbsp;", " ")
        .replace("&amp;", " and ")
        .replace("&lt;", " ")
        .replace("&gt;", " ")
        .replace("&quot;", "\"")
        .replace("&#39;", "'")

    s = s.replace(Regex("^\\|(.+)\\|$", RegexOption.MULTILINE)) { match ->
        match.groupValues[1]
            .split("|")
            .map { it.trim() }
            .filter { it.isNotBlank() && !it.matches(Regex("[-: ]+")) }
            .joinToString(", ")
    }
    s = s.replace(Regex("^[|: \\-]+$", RegexOption.MULTILINE), "")

    s = s.replace(Regex("^#{1,6}\\s+", RegexOption.MULTILINE), "")
    s = s.replace(Regex("\\*{1,3}([^*]+)\\*{1,3}"), "$1")
    s = s.replace(Regex("_{1,3}([^_]+)_{1,3}"), "$1")
    s = s.replace(Regex("~~([^~]+)~~"), "$1")
    s = s.replace(Regex("^[-*_]{3,}\\s*$", RegexOption.MULTILINE), "")
    s = s.replace(Regex("^>+\\s?", RegexOption.MULTILINE), "")
    s = s.replace(Regex("^[\\-*+]\\s+", RegexOption.MULTILINE), "")
    s = s.replace(Regex("^\\d+\\.\\s+", RegexOption.MULTILINE), "")

    s = s.replace(Regex("[<>*_#`~^|{}\\[\\]\\\\]"), " ")
    s = s.replace(Regex("[()/]"), " ")
    s = s.replace(Regex("\\s+([,.!?;:])"), "$1")
    s = s.replace(Regex("[ \\t]{2,}"), " ")
    s = s.replace(Regex("\\n[ \\t]+"), "\n")
    s = s.replace(Regex("\n{3,}"), "\n\n")

    return s.trim()
}
