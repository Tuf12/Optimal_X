package com.example.optimalx.ui.editor.components

import androidx.compose.ui.text.TextRange

/**
 * Toolbar / paste transforms on canonical markdown source.
 *
 * The note editor stores markdown bytes. These helpers wrap the caret or
 * selection in ATX / emphasis / list syntax instead of going through a
 * WYSIWYG span buffer (which re-encodes and drops Eidos-authored markup).
 */
object NoteMarkdownActions {

    data class Result(val text: String, val selection: TextRange)

    fun toggleBold(text: String, selection: TextRange): Result =
        toggleWrap(text, selection, "**")

    fun toggleItalic(text: String, selection: TextRange): Result =
        toggleWrap(text, selection, "*")

    fun toggleStrike(text: String, selection: TextRange): Result =
        toggleWrap(text, selection, "~~")

    fun toggleInlineCode(text: String, selection: TextRange): Result =
        toggleWrap(text, selection, "`")

    fun isBold(text: String, selection: TextRange): Boolean =
        isWrapped(text, selection, "**")

    fun isItalic(text: String, selection: TextRange): Boolean =
        isWrapped(text, selection, "*") && !isWrapped(text, selection, "**")

    fun isStrike(text: String, selection: TextRange): Boolean =
        isWrapped(text, selection, "~~")

    fun isInlineCode(text: String, selection: TextRange): Boolean =
        isWrapped(text, selection, "`")

    fun isLink(text: String, selection: TextRange): Boolean {
        val selected = selectedOrWord(text, selection)
        return selected.looksLikeMarkdownLink()
    }

    fun detectHeadingLevel(text: String, selection: TextRange): NoteHeadingLevel {
        val line = currentLine(text, selection.min)
        return when {
            HEADING_1.matches(line) -> NoteHeadingLevel.TITLE
            HEADING_2.matches(line) -> NoteHeadingLevel.SECTION
            HEADING_3.matches(line) -> NoteHeadingLevel.SUBSECTION
            else -> NoteHeadingLevel.BODY
        }
    }

    fun cycleHeading(text: String, selection: TextRange): Result =
        applyHeading(text, selection, detectHeadingLevel(text, selection).next())

    fun applyHeading(text: String, selection: TextRange, level: NoteHeadingLevel): Result {
        val lineRange = lineRangeForSelection(text, selection.min)
        val line = text.substring(lineRange.start, lineRange.end)
        val stripped = line.replace(HEADING_PREFIX, "").trimStart()
        val next = when (level) {
            NoteHeadingLevel.BODY -> stripped
            NoteHeadingLevel.TITLE -> "# $stripped"
            NoteHeadingLevel.SECTION -> "## $stripped"
            NoteHeadingLevel.SUBSECTION -> "### $stripped"
        }
        val newText = text.replaceRange(lineRange.start, lineRange.end, next)
        val caret = (lineRange.start + next.length).coerceIn(0, newText.length)
        return Result(newText, TextRange(caret))
    }

    fun isUnorderedList(text: String, selection: TextRange): Boolean =
        BULLET_LINE.matches(currentLine(text, selection.min))

    fun isOrderedList(text: String, selection: TextRange): Boolean =
        NUMBERED_LINE.matches(currentLine(text, selection.min))

    fun toggleUnorderedList(text: String, selection: TextRange): Result =
        toggleLinePrefix(text, selection, addPrefix = "- ", matches = { BULLET_LINE.matches(it) }) { line ->
            line.replace(BULLET_PREFIX, "")
        }

    fun toggleOrderedList(text: String, selection: TextRange): Result =
        toggleLinePrefix(text, selection, addPrefix = "1. ", matches = { NUMBERED_LINE.matches(it) }) { line ->
            line.replace(NUMBERED_PREFIX, "")
        }

    fun applyLink(text: String, selection: TextRange, url: String): Result {
        val trimmed = url.trim()
        if (trimmed.isEmpty()) return Result(text, selection)
        val start = selection.min.coerceIn(0, text.length)
        val end = selection.max.coerceIn(start, text.length)
        val label = if (start == end) trimmed else text.substring(start, end)
        val markdown = "[$label]($trimmed)"
        val newText = text.substring(0, start) + markdown + text.substring(end)
        return Result(newText, TextRange(start, start + markdown.length))
    }

    fun insertMarkdown(text: String, selection: TextRange, incoming: String): Result {
        val start = selection.min.coerceIn(0, text.length)
        val end = selection.max.coerceIn(start, text.length)
        val newText = text.substring(0, start) + incoming + text.substring(end)
        val caret = start + incoming.length
        return Result(newText, TextRange(caret))
    }

    fun toggleWrap(text: String, selection: TextRange, marker: String): Result {
        val start = selection.min.coerceIn(0, text.length)
        val end = selection.max.coerceIn(start, text.length)
        if (start == end) {
            val inserted = marker + marker
            val newText = text.substring(0, start) + inserted + text.substring(end)
            val inner = start + marker.length
            return Result(newText, TextRange(inner))
        }
        val selected = text.substring(start, end)
        if (selected.startsWith(marker) &&
            selected.endsWith(marker) &&
            selected.length >= marker.length * 2
        ) {
            val inner = selected.removePrefix(marker).removeSuffix(marker)
            val newText = text.substring(0, start) + inner + text.substring(end)
            return Result(newText, TextRange(start, start + inner.length))
        }
        val before = text.substring(0, start)
        val after = text.substring(end)
        if (before.endsWith(marker) && after.startsWith(marker)) {
            val newText = before.dropLast(marker.length) + selected + after.drop(marker.length)
            return Result(newText, TextRange(start - marker.length, end - marker.length))
        }
        val wrapped = marker + selected + marker
        val newText = text.substring(0, start) + wrapped + text.substring(end)
        return Result(newText, TextRange(start, start + wrapped.length))
    }

    fun isWrapped(text: String, selection: TextRange, marker: String): Boolean {
        val start = selection.min.coerceIn(0, text.length)
        val end = selection.max.coerceIn(start, text.length)
        if (start < end) {
            val selected = text.substring(start, end)
            if (selected.startsWith(marker) &&
                selected.endsWith(marker) &&
                selected.length >= marker.length * 2
            ) {
                return true
            }
        }
        val before = text.substring(0, start)
        val after = text.substring(end)
        return before.endsWith(marker) && after.startsWith(marker)
    }

    internal fun lineRangeForSelection(editorText: String, selectionIndex: Int): TextRange {
        val length = editorText.length
        if (length == 0) return TextRange(0, 0)
        val pos = selectionIndex.coerceIn(0, length)
        val lineStart = if (pos == 0) {
            0
        } else {
            val prevNl = editorText.lastIndexOf('\n', startIndex = pos - 1)
            if (prevNl == -1) 0 else prevNl + 1
        }
        val rawLineEnd = editorText.indexOf('\n', startIndex = pos).let { if (it == -1) length else it }
        return TextRange(lineStart, rawLineEnd.coerceIn(lineStart, length))
    }

    private fun currentLine(text: String, selectionIndex: Int): String {
        val range = lineRangeForSelection(text, selectionIndex)
        return text.substring(range.start, range.end)
    }

    private fun selectedOrWord(text: String, selection: TextRange): String {
        val start = selection.min.coerceIn(0, text.length)
        val end = selection.max.coerceIn(start, text.length)
        return if (start == end) currentLine(text, start) else text.substring(start, end)
    }

    private fun toggleLinePrefix(
        text: String,
        selection: TextRange,
        addPrefix: String,
        matches: (String) -> Boolean,
        strip: (String) -> String,
    ): Result {
        val rangeStart = lineRangeForSelection(text, selection.min).start
        val rangeEnd = lineRangeForSelection(text, selection.max).end
        val block = text.substring(rangeStart, rangeEnd)
        val lines = block.split('\n')
        val allMatch = lines.isNotEmpty() && lines.all { it.isBlank() || matches(it) }
        val rewritten = lines.joinToString("\n") { line ->
            if (line.isBlank()) line
            else if (allMatch) strip(line) else addPrefix + strip(line)
        }
        val newText = text.substring(0, rangeStart) + rewritten + text.substring(rangeEnd)
        return Result(newText, TextRange(rangeStart, rangeStart + rewritten.length))
    }

    private fun String.looksLikeMarkdownLink(): Boolean =
        startsWith("[") && contains("](") && endsWith(")")

    private val HEADING_PREFIX = Regex("^#{1,3}\\s+")
    private val HEADING_1 = Regex("^#\\s+.*")
    private val HEADING_2 = Regex("^##\\s+.*")
    private val HEADING_3 = Regex("^###\\s+.*")
    private val BULLET_LINE = Regex("^\\s*[-*+]\\s+.*")
    private val BULLET_PREFIX = Regex("^\\s*[-*+]\\s+")
    private val NUMBERED_LINE = Regex("^\\s*\\d+\\.\\s+.*")
    private val NUMBERED_PREFIX = Regex("^\\s*\\d+\\.\\s+")
}
