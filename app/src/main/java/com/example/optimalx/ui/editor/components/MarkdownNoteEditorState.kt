package com.example.optimalx.ui.editor.components

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.text.TextRange
import androidx.compose.ui.text.input.TextFieldValue
import com.example.optimalx.ui.components.NoteContentCodec

/**
 * In-memory markdown source buffer for [NotePanel] / [DumpEditPanel].
 *
 * Canonical storage is the [markdown] string. The rich-text library is not
 * used as the document model — it only converts HTML clipboard / legacy rows
 * via [NoteContentCodec].
 */
class MarkdownNoteEditorState(
    initial: String = "",
) {
    var value by mutableStateOf(TextFieldValue(initial, TextRange.Zero))
        private set

    val markdown: String
        get() = value.text

    val selection: TextRange
        get() = value.selection

    fun load(stored: String) {
        val normalized = NoteContentCodec.normalizeLegacyToMarkdown(stored)
        value = TextFieldValue(normalized, TextRange.Zero)
    }

    fun setMarkdown(text: String, selection: TextRange = TextRange(text.length)) {
        value = TextFieldValue(text, selection)
    }

    fun onValueChange(next: TextFieldValue) {
        value = next.copy(text = NoteContentCodec.normalizeLineEndings(next.text))
    }

    fun apply(transform: (String, TextRange) -> NoteMarkdownActions.Result) {
        val result = transform(value.text, value.selection)
        value = TextFieldValue(result.text, result.selection)
    }

    fun pasteClipboard(plain: String?, html: String? = null) {
        val incoming = NoteContentCodec.clipboardToMarkdown(plain, html)
        if (incoming.isEmpty()) return
        apply { text, selection -> NoteMarkdownActions.insertMarkdown(text, selection, incoming) }
    }

    fun appendPlain(heard: String) {
        val merged = NoteContentCodec.mergePlainTextAppend(markdown, heard)
        value = TextFieldValue(merged, TextRange(merged.length))
    }

    val headingLevel: NoteHeadingLevel
        get() = NoteMarkdownActions.detectHeadingLevel(value.text, value.selection)

    val isBold: Boolean
        get() = NoteMarkdownActions.isBold(value.text, value.selection)

    val isItalic: Boolean
        get() = NoteMarkdownActions.isItalic(value.text, value.selection)

    val isStrike: Boolean
        get() = NoteMarkdownActions.isStrike(value.text, value.selection)

    val isCode: Boolean
        get() = NoteMarkdownActions.isInlineCode(value.text, value.selection)

    val isLink: Boolean
        get() = NoteMarkdownActions.isLink(value.text, value.selection)

    val isBullet: Boolean
        get() = NoteMarkdownActions.isUnorderedList(value.text, value.selection)

    val isNumbered: Boolean
        get() = NoteMarkdownActions.isOrderedList(value.text, value.selection)
}
