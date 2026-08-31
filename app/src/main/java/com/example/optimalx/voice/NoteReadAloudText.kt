package com.example.optimalx.voice

import com.example.optimalx.data.eidos.ContentSummaryService
import com.example.optimalx.ui.components.NoteContentCodec
import com.example.optimalx.ui.components.noteTextLooksLikeHtml
import com.mohamedrejeb.richeditor.model.RichTextState

/** Speech-friendly prose from stored note content (markdown or legacy HTML). */
internal fun sanitizeNoteContentForTts(content: String): String {
    val trimmed = content.trim()
    if (trimmed.isBlank()) return ""
    return if (trimmed.noteTextLooksLikeHtml()) {
        ContentSummaryService.noteContentToPlain(trimmed)
    } else {
        stripMarkdownForTts(trimmed)
    }
}

/** Markdown source for read-aloud — prefers view snapshot, then live editor persist. */
internal fun resolveNoteContentForReadAloud(
    richTextState: RichTextState,
    viewSnapshot: String?,
): String {
    val markdown = resolveNoteMarkdownViewContent(viewSnapshot, richTextState)
    return markdown.ifBlank { richTextState.toText() }
}

/**
 * Canonical markdown for view-mode preview and read-aloud.
 * Storage is markdown-first; legacy HTML snapshots normalize on read.
 */
internal fun resolveNoteMarkdownViewContent(
    viewSnapshot: String?,
    richTextState: RichTextState,
): String {
    val snapshot = viewSnapshot?.trim().orEmpty()
    if (snapshot.isNotEmpty()) {
        return NoteContentCodec.normalizeLegacyToMarkdown(snapshot)
    }
    return NoteContentCodec.persistFromRichText(richTextState)
}

/** Capture markdown when entering view mode (unsaved WYSIWYG edits included). */
internal fun captureNoteViewSnapshot(
    richTextState: RichTextState,
    previousSnapshot: String?,
): String {
    val current = NoteContentCodec.persistFromRichText(richTextState)
    return current.ifBlank { previousSnapshot?.trim().orEmpty() }
}

internal fun shouldShowNoteMarkdownView(
    isViewMode: Boolean,
    viewSnapshot: String?,
    richTextState: RichTextState,
): Boolean {
    if (!isViewMode) return false
    return resolveNoteMarkdownViewContent(viewSnapshot, richTextState).isNotBlank()
}
