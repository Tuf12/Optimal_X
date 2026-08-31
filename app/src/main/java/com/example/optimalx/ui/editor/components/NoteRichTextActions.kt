package com.example.optimalx.ui.editor.components

import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.TextRange
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.em
import com.mohamedrejeb.richeditor.model.RichTextState

/** Markdown-persisting heading levels aligned with richeditor ATX encoding. */
enum class NoteHeadingLevel(val shortLabel: String) {
    BODY("Norm"),
    TITLE("H1"),
    SECTION("H2"),
    SUBSECTION("H3"),
    ;

    fun next(): NoteHeadingLevel = entries[(ordinal + 1) % entries.size]
}

object NoteRichTextActions {

    private val titleSpanStyle = SpanStyle(fontSize = 2.em, fontWeight = FontWeight.Bold)
    private val sectionSpanStyle = SpanStyle(fontSize = 1.5.em, fontWeight = FontWeight.Bold)
    private val subsectionSpanStyle = SpanStyle(fontSize = 1.17.em, fontWeight = FontWeight.Bold)

    fun detectHeadingLevel(state: RichTextState): NoteHeadingLevel {
        val text = state.annotatedString.text
        val probeIndex = headingProbeIndex(state, text)
        if (probeIndex >= text.length) return NoteHeadingLevel.BODY
        val style = state.getSpanStyle(TextRange(probeIndex, probeIndex + 1))
        return headingLevelFromSpanStyle(style)
    }

    fun applyHeading(state: RichTextState, level: NoteHeadingLevel) {
        val text = state.annotatedString.text
        val targetRange = headingTargetRange(state, text)
        if (targetRange.collapsed || targetRange.start >= text.length) return

        val currentLevel = detectHeadingLevel(state)
        spanStyleFor(currentLevel)?.let { state.removeSpanStyle(it, targetRange) }
        spanStyleFor(level)?.let { state.addSpanStyle(it, targetRange) }
    }

    fun cycleHeading(state: RichTextState) {
        applyHeading(state, detectHeadingLevel(state).next())
    }

    fun toggleInlineCode(state: RichTextState) {
        state.toggleCodeSpan()
    }

    fun applyLink(state: RichTextState, url: String) {
        val trimmed = url.trim()
        if (trimmed.isEmpty()) return
        if (state.selection.collapsed) {
            state.addLink(text = trimmed, url = trimmed)
        } else {
            state.addLinkToSelection(trimmed)
        }
    }

    private fun spanStyleFor(level: NoteHeadingLevel): SpanStyle? = when (level) {
        NoteHeadingLevel.BODY -> null
        NoteHeadingLevel.TITLE -> titleSpanStyle
        NoteHeadingLevel.SECTION -> sectionSpanStyle
        NoteHeadingLevel.SUBSECTION -> subsectionSpanStyle
    }

    private fun headingProbeIndex(state: RichTextState, editorText: String): Int {
        if (!state.selection.collapsed) {
            return state.selection.min.coerceIn(0, editorText.length)
        }
        return lineRangeForSelection(editorText, state.selection.min).start
    }

    /** Highlighted text when selected; otherwise the first line of the current line. */
    internal fun headingTargetRange(state: RichTextState, editorText: String): TextRange {
        if (!state.selection.collapsed) {
            val start = state.selection.min.coerceIn(0, editorText.length)
            val end = state.selection.max.coerceIn(start, editorText.length)
            val selected = editorText.substring(start, end)
            val firstLineBreak = selected.indexOf('\n')
            val clippedEnd = if (firstLineBreak == -1) end else start + firstLineBreak
            return TextRange(start, clippedEnd)
        }
        val lineRange = lineRangeForSelection(editorText, state.selection.min)
        val lineText = editorText.substring(lineRange.start, lineRange.end)
        val firstLineBreak = lineText.indexOf('\n')
        return if (firstLineBreak == -1) {
            lineRange
        } else {
            TextRange(lineRange.start, lineRange.start + firstLineBreak)
        }
    }

    private fun headingLevelFromSpanStyle(style: SpanStyle): NoteHeadingLevel {
        val fontSize = style.fontSize
        if (fontSize.isEm) {
            return when {
                fontSize >= titleSpanStyle.fontSize -> NoteHeadingLevel.TITLE
                fontSize >= sectionSpanStyle.fontSize -> NoteHeadingLevel.SECTION
                fontSize >= subsectionSpanStyle.fontSize -> NoteHeadingLevel.SUBSECTION
                else -> NoteHeadingLevel.BODY
            }
        }
        if (fontSize.isSp) {
            return when {
                fontSize.value >= titleSpanStyle.fontSize.value * 16 -> NoteHeadingLevel.TITLE
                fontSize.value >= sectionSpanStyle.fontSize.value * 16 -> NoteHeadingLevel.SECTION
                fontSize.value >= subsectionSpanStyle.fontSize.value * 16 -> NoteHeadingLevel.SUBSECTION
                else -> NoteHeadingLevel.BODY
            }
        }
        return NoteHeadingLevel.BODY
    }

    /** Line bounds in [editorText] — must match [RichTextState.annotatedString.text], not [RichTextState.toText]. */
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
        val lineEnd = rawLineEnd.coerceIn(lineStart, length)
        return TextRange(lineStart, lineEnd)
    }
}
