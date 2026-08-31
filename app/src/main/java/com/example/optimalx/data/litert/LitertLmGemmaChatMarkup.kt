package com.example.optimalx.data.litert

/**
 * Gemma 4 LiteRT-LM chat template markers sometimes leak through [com.google.ai.edge.litertlm.Message.toString].
 */
object LitertLmGemmaChatMarkup {

    private val TURN_TOKENS = Regex(
        "<\\|?turn>\\|?(user|model)>?|<turn\\|>|<\\|turn\\|>",
        RegexOption.IGNORE_CASE,
    )

    private val ANGLE_CONTROL_TOKENS = Regex("<\\|[^>]{1,48}>")

    fun stripForDisplay(raw: String): String {
        if (raw.isBlank()) return ""
        var text = raw.replace(TURN_TOKENS, "")
        if (containsTurnMarkup(text)) {
            text = text.replace(ANGLE_CONTROL_TOKENS, "")
        }
        return text
            .replace(Regex("[ \t]+\n"), "\n")
            .replace(Regex("\n{3,}"), "\n\n")
            .trim()
    }

    fun containsTurnMarkup(text: String): Boolean =
        TURN_TOKENS.containsMatchIn(text) ||
            text.contains("<|turn", ignoreCase = true) ||
            text.contains("<turn|", ignoreCase = true)

    /** History fed back into LiteRT should not include prior template leakage. */
    fun stripForHistory(raw: String): String = stripForDisplay(raw)
}
