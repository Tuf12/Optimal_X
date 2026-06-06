package com.example.optimalx.data.eidos

/**
 * User-facing chat memory depth and tool-first context rules (see EIDOS_LLM_CONTEXT_CLEANUP.md).
 */
object EidosContextLimits {

    const val MEMORY_LOW = "low"
    const val MEMORY_MEDIUM = "medium"
    const val MEMORY_HIGH = "high"

    val MEMORY_OPTIONS: List<String> = listOf(MEMORY_LOW, MEMORY_MEDIUM, MEMORY_HIGH)

    /** [maxUserExchanges] = user turns kept; [maxChars] is a secondary safety cap on total text. */
    data class HistoryBudget(
        val maxUserExchanges: Int,
        val maxChars: Int,
    )

    fun normalizeMemoryDepth(value: String?): String? {
        val trimmed = value?.trim()?.lowercase().orEmpty()
        if (trimmed.isBlank()) return null
        return trimmed.takeIf { it in MEMORY_OPTIONS }
    }

    /** Per-conversation override, else Settings default. */
    fun effectiveMemoryDepth(
        conversationStored: String?,
        settingsDefault: String,
    ): String {
        return normalizeMemoryDepth(conversationStored)
            ?: normalizeMemoryDepth(settingsDefault)
            ?: MEMORY_LOW
    }

    fun displayLabel(depth: String?, settingsDefault: String): String {
        val effective = effectiveMemoryDepth(depth, settingsDefault)
        val tier = effective.replaceFirstChar { it.uppercase() }
        return if (depth == null || normalizeMemoryDepth(depth) == null) {
            "Memory: $tier (default)"
        } else {
            "Memory: $tier"
        }
    }

    /** Cycles: inherit settings → low → medium → high → inherit. */
    fun nextConversationMemoryDepth(current: String?): String? = when (normalizeMemoryDepth(current)) {
        null -> MEMORY_LOW
        MEMORY_LOW -> MEMORY_MEDIUM
        MEMORY_MEDIUM -> MEMORY_HIGH
        else -> null
    }

    fun historyBudget(depth: String): HistoryBudget = when (depth.lowercase()) {
        MEMORY_MEDIUM -> HistoryBudget(maxUserExchanges = 16, maxChars = 30_000)
        MEMORY_HIGH -> HistoryBudget(maxUserExchanges = 40, maxChars = 80_000)
        else -> HistoryBudget(maxUserExchanges = 8, maxChars = 12_000)
    }

    /** Injected on every Eidos call — keep stable for provider prompt-cache prefixes. */
    val TOOL_FIRST_CONTEXT_RULES: String = """
        Context policy (OptimalX):
        - Do not assume full note, file, folder-tree, or entire workshop project source is inlined in this prompt.
        - Panel Workshop scope includes a compact project file list (fileReferenceId per file) and a bounded open-tab excerpt when applicable.
        - Retrieval: call search_semantic(query) — it returns chunk_text passages with location and ids. Answer from those chunks directly.
        - Use read_note/read_file/workshop_read_file only to expand a line range or before editing — not as a required second hop for Q&A.
        - Prefer scopeType=local_first with current subfolder/parent ids when the question is location-specific; expansionPolicy defaults to expand_if_weak.
        - search_chat_history is keyword fallback for exact chat phrases only.
        - Active location IDs in this prompt are authoritative for default create/write targets.
        - Stored summaries in prompt are orientation only; trust search_semantic chunks for facts.
    """.trimIndent()
}
