package com.example.optimalx.data.dumpedit

import android.content.Context
import com.example.optimalx.data.preferences.DumpEditPreferences
import com.example.optimalx.data.preferences.DumpEditState
import com.example.optimalx.data.semantic.ContentSectionRetriever
import com.example.optimalx.data.semantic.SegmentMode

object DumpEditContextLimits {
    /** Inline full buffer in Eidos system prompt when at or below this size. */
    const val FULL_CONTEXT_CHAR_THRESHOLD = 8_192
}

object DumpEditContext {

    suspend fun buildPromptBlock(
        context: Context,
        userMessage: String?,
        contentSectionRetriever: ContentSectionRetriever,
    ): String {
        val state = DumpEditPreferences.readState(context)
        return buildString {
            appendLine("DumpEdit scratch buffer:")
            appendLine(formatStateBlock(state, userMessage, contentSectionRetriever))
        }.trim()
    }

    private fun formatStateBlock(
        state: DumpEditState,
        userMessage: String?,
        contentSectionRetriever: ContentSectionRetriever,
    ): String {
        if (state.aiBlind) {
            return "Blind from Eidos — do not request buffer content."
        }
        if (state.content.isBlank()) {
            return "Empty — nothing to read yet."
        }
        val length = state.content.length
        if (state.aiLocked) {
            return buildString {
                appendLine("AI locked ($length chars). Use read_dump_edit(query=...) when you need sections.")
                if (length <= DumpEditContextLimits.FULL_CONTEXT_CHAR_THRESHOLD) {
                    appendLine("Buffer preview is withheld while locked.")
                }
            }.trim()
        }
        if (length <= DumpEditContextLimits.FULL_CONTEXT_CHAR_THRESHOLD) {
            return buildString {
                appendLine("Full buffer ($length chars):")
                append(state.content)
            }.trim()
        }
        val query = userMessage?.trim()?.takeIf { it.isNotBlank() } ?: "overview"
        val sections = contentSectionRetriever.retrieveByQuery(
            fullText = state.content,
            query = query,
            mode = SegmentMode.NOTE,
        )
        return buildString {
            appendLine("Large buffer ($length chars) — excerpts for this turn (query: \"$query\"):")
            append(sections.json)
            appendLine()
            append("Use read_dump_edit(query=...) or startLine/endLine for more sections.")
        }.trim()
    }
}
