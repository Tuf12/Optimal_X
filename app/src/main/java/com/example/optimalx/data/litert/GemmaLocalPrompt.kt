package com.example.optimalx.data.litert

/**
 * Slim system instruction for on-device Gemma — see [app/docs/GemmaLocal.md].
 *
 * [compose] switches between tools mode (default) and chat-only when the local
 * tools toggle is off ([SettingsKeys.LOCAL_GEMMA_TOOLS_ENABLED]).
 */
object GemmaLocalPrompt {

    private val CORE_WITH_TOOLS: String = """
        You are Eidos inside OptimalX, running on-device (Gemma 4B). Be concise and practical.
        Use tools when the user asks to search notes or save content to folders.
        OptimalX: parent folder (project) → subfolder (workspace). Tools use parentFolderId and subfolderId.
    """.trimIndent()

    private val CORE_CHAT_ONLY: String = """
        You are Eidos inside OptimalX, running on-device (Gemma 4B). Be concise and practical.
        Chat only — tools are not available. Answer from the conversation and general knowledge.
        OptimalX organizes work as parent folder (project) → subfolder (workspace).
    """.trimIndent()

    private val TOOL_RULES: String = """
        Local tools:
        - search_semantic(query): find note/file/chat passages — answer from chunk_text when possible.
        - create_parent_folder / create_subfolder: resolve folder names before writing.
        - write_note(subfolderId, content): append markdown at the END of that subfolder's note only.
        Do not claim edits outside these tools.
    """.trimIndent()

    fun compose(
        scopeType: String?,
        subfolderId: Long?,
        parentFolderId: Long?,
        toolsEnabled: Boolean = true,
    ): String = buildString {
        if (toolsEnabled) {
            appendLine(CORE_WITH_TOOLS)
            appendLine(TOOL_RULES)
        } else {
            appendLine(CORE_CHAT_ONLY)
        }
        val location = activeLocationLine(scopeType, subfolderId, parentFolderId)
        if (location != null) {
            appendLine()
            append(location)
        }
    }.trim()

    private fun activeLocationLine(
        scopeType: String?,
        subfolderId: Long?,
        parentFolderId: Long?,
    ): String? = when {
        subfolderId != null ->
            "Active location: subfolderId=$subfolderId (scope=${scopeType ?: "subfolder"})."
        parentFolderId != null ->
            "Active location: parentFolderId=$parentFolderId (scope=${scopeType ?: "parent"})."
        else -> null
    }
}
