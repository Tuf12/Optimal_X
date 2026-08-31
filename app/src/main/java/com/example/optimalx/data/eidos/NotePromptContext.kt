package com.example.optimalx.data.eidos

import com.example.optimalx.data.model.Note
import com.example.optimalx.ui.components.NoteContentCodec

/**
 * Builds subfolder note context for Eidos system prompts (inject tiers).
 * Large notes do not inline body text — prefetch + search_semantic / read_note cover that.
 */
object NotePromptContext {

    enum class InjectTier {
        EMPTY,
        INLINE_FULL,
        LARGE_RETRIEVE,
    }

    fun resolveTier(bodyLength: Int): InjectTier = when {
        bodyLength == 0 -> InjectTier.EMPTY
        bodyLength <= NoteSummaryPolicy.INLINE_NOTE_MAX_CHARS -> InjectTier.INLINE_FULL
        else -> InjectTier.LARGE_RETRIEVE
    }

    fun formatForPrompt(
        note: Note?,
        subfolderId: Long,
        bodyMarkdown: String,
        aiLocked: Boolean = note?.aiLocked == true,
    ): String {
        if (note == null) return "Note: none."
        if (note.aiBlind) return "Note: blind from Eidos (do not request content)."

        val body = bodyMarkdown.trim()
        val lockPrefix = if (aiLocked) "Note: AI lock — " else "Note: present — "
        val tier = resolveTier(body.length)

        return when (tier) {
            InjectTier.EMPTY -> {
                if (aiLocked) {
                    formatSummaryOnly(note, lockPrefix + "no body yet; folder memory below if set.")
                } else {
                    "Note: empty."
                }
            }
            InjectTier.INLINE_FULL -> buildString {
                append(lockPrefix)
                appendLine("full body inline (subfolderId=$subfolderId):")
                append(body)
                appendMemoryHintIfPresent(note)
            }.trim()
            InjectTier.LARGE_RETRIEVE -> buildString {
                append(lockPrefix)
                appendLine("folder memory only — body not inlined (subfolderId=$subfolderId):")
                append(formatSummarySections(note))
                appendLine()
                append(retrievalHint())
            }.trim()
        }
    }

    internal fun formatSummarySections(note: Note): String {
        val sections = NoteSummaryCodec.parse(note.summary)
        if (sections.isEmpty) return "Folder memory: (none yet)\n"
        return buildString {
            if (sections.memoryBullets.isNotEmpty()) {
                appendLine("Folder memory:")
                sections.memoryBullets.forEach { appendLine("- $it") }
            } else {
                appendLine("Folder memory: (none yet)")
            }
            if (sections.contentDigest.isNotBlank()) {
                appendLine()
                appendLine("Content digest (orientation only — use search_semantic for passages):")
                appendLine(sections.contentDigest.trim())
            }
        }.trimEnd() + "\n"
    }

    private fun formatSummaryOnly(note: Note, header: String): String = buildString {
        appendLine(header)
        val sections = NoteSummaryCodec.parse(note.summary)
        if (!sections.isEmpty) {
            append(formatSummarySections(note))
        }
    }.trim()

    private fun StringBuilder.appendMemoryHintIfPresent(note: Note) {
        val bullets = NoteSummaryCodec.parse(note.summary).memoryBullets
        if (bullets.isEmpty()) return
        appendLine()
        appendLine("Folder memory:")
        bullets.forEach { appendLine("- $it") }
    }

    private fun retrievalHint(): String = """
        Retrieval: use search_semantic, read_note, or read_note_section — full body is not inlined.
        Edits: edit_note_section — preferred startLine/endLine from read_note or search_semantic; fallback oldString from chunk_text (unique match); retry using tool error snippets.
        Folder memory: write_note_summary to append durable folder bullets; user may edit summary in the editor. App-wide personal facts (location, age, preferences): write_long_term_memory.
    """.trimIndent()

    fun normalizeBody(storedContent: String): String =
        NoteContentCodec.normalizeLegacyToMarkdown(storedContent)

    /**
     * Optional one-liner when a large note has no folder memory yet (volatile "this turn" nudge).
     */
    fun folderMemoryNudge(note: Note?, bodyLength: Int): String? {
        if (note == null || note.aiBlind) return null
        if (bodyLength <= NoteSummaryPolicy.FOLD_TRIGGER_CHARS) return null
        if (NoteSummaryCodec.parse(note.summary).memoryBullets.isNotEmpty()) return null
        return "Note body is large — use write_note_summary (append) for durable folder facts if the user stated preferences."
    }
}
