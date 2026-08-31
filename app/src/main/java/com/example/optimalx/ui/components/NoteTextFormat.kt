package com.example.optimalx.ui.components

import com.example.optimalx.data.eidos.ContentSummaryService

/** True when note body was saved or authored as rich-text HTML. */
internal fun String.noteTextLooksLikeHtml(): Boolean =
    contains("<p", ignoreCase = true) ||
        contains("<div", ignoreCase = true) ||
        contains("<br", ignoreCase = true) ||
        contains("<span", ignoreCase = true)

/**
 * Heuristic for markdown-ish source (headers, fences, tables, emphasis).
 * Works on plain text; for HTML blobs use [notePlainTextForMarkdownProbe] first.
 */
internal fun String.noteTextLooksLikeMarkdown(): Boolean {
    if (isBlank()) return false
    return contains(Regex("(?m)^#{1,6}\\s")) ||
        contains("**") ||
        contains("__") ||
        contains("```") ||
        contains(Regex("(?m)^\\s*[-*+]\\s")) ||
        contains(Regex("(?m)^\\s*\\d+\\.\\s")) ||
        contains(Regex("(?m)^\\|.+\\|"))
}

/** Plain text probe — strips HTML wrappers so saved `<p># Title</p>` still counts as markdown. */
internal fun String.notePlainTextForMarkdownProbe(): String =
    if (noteTextLooksLikeHtml()) ContentSummaryService.noteContentToPlain(this) else this

/** True when view mode should render via [MarkdownRichText] instead of read-only WYSIWYG. */
internal fun String.noteTextHasRenderableMarkdown(): Boolean {
    if (isBlank()) return false
    val probe = notePlainTextForMarkdownProbe()
    return !noteTextLooksLikeHtml() || probe.noteTextLooksLikeMarkdown()
}
