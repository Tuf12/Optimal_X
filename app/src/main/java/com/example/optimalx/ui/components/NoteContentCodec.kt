package com.example.optimalx.ui.components

import androidx.compose.ui.text.TextRange
import com.mohamedrejeb.richeditor.annotation.ExperimentalRichTextApi
import com.mohamedrejeb.richeditor.model.RichTextState

/**
 * Canonical load/save boundary for note and DumpEdit rich-text buffers.
 *
 * Storage format is markdown ([NotePersistFormat.MARKDOWN]). Legacy HTML rows are
 * converted on load via [normalizeLegacyToMarkdown] (lazy migration).
 */
@OptIn(ExperimentalRichTextApi::class)
object NoteContentCodec {

    var defaultPersistFormat: NotePersistFormat = NotePersistFormat.MARKDOWN

    fun normalizeLineEndings(text: String): String =
        text.replace("\r\n", "\n").replace('\r', '\n')

    /** Returns markdown for Room/UI; converts legacy HTML in place. */
    fun normalizeLegacyToMarkdown(stored: String): String {
        val normalized = normalizeLineEndings(stored)
        if (normalized.isBlank() || !normalized.noteTextLooksLikeHtml()) return normalized
        val state = RichTextState()
        state.setHtml(normalized)
        return state.toMarkdown()
    }

    fun needsLegacyMigration(stored: String): Boolean =
        stored.isNotBlank() && stored.noteTextLooksLikeHtml()

    fun loadIntoRichText(state: RichTextState, stored: String) {
        val normalized = normalizeLineEndings(stored)
        if (normalized.isEmpty()) {
            state.setHtml("")
            return
        }
        if (normalized.noteTextLooksLikeHtml()) {
            state.setHtml(normalized)
        } else {
            state.setMarkdown(normalized)
        }
    }

    /**
     * Ingest clipboard text through the markdown/HTML parsers instead of the
     * richeditor [TextField] paste path.
     *
     * Native paste inserts characters as-is, so `# Title` stays literal until the
     * next [loadIntoRichText], and large replacements crash in `checkForParagraphs`
     * (`StringIndexOutOfBoundsException`).
     */
    fun pasteIntoRichText(state: RichTextState, clipboard: String) {
        val incoming = normalizeLineEndings(clipboard)
        if (incoming.isEmpty()) return
        runCatching {
            pasteIncoming(state, incoming)
        }.getOrElse {
            loadIntoRichText(state, incoming)
            state.selection = TextRange.Zero
        }
    }

    private fun pasteIncoming(state: RichTextState, incoming: String) {
        val visualLen = state.annotatedString.text.length
        val selection = state.selection
        val replacingAll = visualLen == 0 ||
            (!selection.collapsed && selection.min <= 0 && selection.max >= visualLen)
        if (replacingAll) {
            loadIntoRichText(state, incoming)
            state.selection = TextRange.Zero
            return
        }
        if (!selection.collapsed) {
            state.removeSelectedText()
        }
        val insertAt = state.selection.min.coerceIn(0, state.annotatedString.text.length)
        if (incoming.noteTextLooksLikeHtml()) {
            state.insertHtml(incoming, insertAt)
        } else {
            state.insertMarkdown(incoming, insertAt)
        }
    }

    /**
     * Convert a clipboard payload to canonical markdown.
     *
     * Browsers and office apps put formatted content on `text/html` and a
     * stripped string on `text/plain`. VS Code and other markdown editors put
     * source on `text/plain`. Prefer HTML→markdown when the clip is HTML;
     * otherwise keep the plain text (already markdown or plain prose).
     */
    fun clipboardToMarkdown(plain: String?, html: String? = null): String {
        val htmlNorm = html?.let { normalizeLineEndings(it) }.orEmpty()
        if (htmlNorm.isNotBlank() && htmlNorm.noteTextLooksLikeHtml()) {
            val converted = normalizeLegacyToMarkdown(htmlNorm)
            if (converted.isNotBlank()) return converted
        }
        return normalizeLineEndings(plain.orEmpty())
    }

    fun persistFromRichText(
        state: RichTextState,
        format: NotePersistFormat = defaultPersistFormat,
    ): String = normalizeLineEndings(
        when (format) {
            NotePersistFormat.HTML -> state.toHtml()
            NotePersistFormat.MARKDOWN -> state.toMarkdown()
        },
    )

    fun mergePlainTextAppend(currentPlain: String, addition: String): String {
        val trimmed = addition.trim()
        if (trimmed.isBlank()) return currentPlain
        val base = currentPlain.trim()
        return if (base.isBlank()) trimmed else "$base\n\n$trimmed"
    }

    fun appendPlainTextToRichText(state: RichTextState, heard: String) {
        val merged = mergePlainTextAppend(state.toText(), heard)
        loadIntoRichText(state, merged)
    }
}

enum class NotePersistFormat {
    HTML,
    MARKDOWN,
}
