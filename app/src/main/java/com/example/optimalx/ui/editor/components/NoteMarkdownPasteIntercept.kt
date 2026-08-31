package com.example.optimalx.ui.editor.components

import android.util.Log
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.platform.Clipboard
import androidx.compose.ui.platform.ClipboardManager
import androidx.compose.ui.platform.ClipEntry
import androidx.compose.ui.platform.NativeClipboard
import androidx.compose.ui.platform.TextToolbar
import androidx.compose.ui.platform.TextToolbarStatus
import androidx.compose.ui.text.AnnotatedString
import com.example.optimalx.ui.components.NoteContentCodec
import com.mohamedrejeb.richeditor.annotation.ExperimentalRichTextApi
import com.mohamedrejeb.richeditor.model.RichTextState

private const val NOTE_PASTE_TAG = "OptimalX.NotePaste"

private val pasteInProgress = java.util.concurrent.atomic.AtomicBoolean(false)

/** Runs [block] on the main thread; skips re-entry and optional snapshot suppression. */
@OptIn(ExperimentalRichTextApi::class)
internal fun performMarkdownPaste(
    clipboardManager: ClipboardManager,
    state: RichTextState,
    wrapProgrammaticEdit: (() -> Unit) -> Unit = { it() },
) {
    if (!pasteInProgress.compareAndSet(false, true)) return
    val text = clipboardManager.getText()?.text
    if (text.isNullOrEmpty()) {
        pasteInProgress.set(false)
        return
    }
    wrapProgrammaticEdit {
        runCatching {
            NoteContentCodec.pasteIntoRichText(state, text)
        }.onFailure { error ->
            Log.e(NOTE_PASTE_TAG, "Safe paste failed; loading clipboard as a new document", error)
            runCatching { NoteContentCodec.loadIntoRichText(state, text) }
        }
    }
    pasteInProgress.set(false)
}

/**
 * Compose Foundation 1.8+ reads [Clipboard.getClipEntry] for paste-menu visibility.
 * Do not ingest clipboard here — IME churn can call [getClipEntry] again and trigger accidental paste.
 */
@OptIn(ExperimentalRichTextApi::class)
internal class MarkdownPasteClipboard(
    private val parent: Clipboard,
) : Clipboard {
    override suspend fun getClipEntry(): ClipEntry? = parent.getClipEntry()

    override suspend fun setClipEntry(clipEntry: ClipEntry?) {
        parent.setClipEntry(clipEntry)
    }

    override val nativeClipboard: NativeClipboard
        get() = parent.nativeClipboard
}

/**
 * Floating-toolbar paste must not call TextFieldSelectionManager.paste (plain AnnotatedString merge).
 */
@OptIn(ExperimentalRichTextApi::class)
internal class MarkdownPasteTextToolbar(
    private val delegate: TextToolbar,
    private val clipboardManager: ClipboardManager,
    private val state: RichTextState,
    private val wrapProgrammaticEdit: (() -> Unit) -> Unit,
) : TextToolbar {
    override val status: TextToolbarStatus
        get() = delegate.status

    override fun hide() {
        delegate.hide()
    }

    override fun showMenu(
        rect: Rect,
        onCopyRequested: (() -> Unit)?,
        onPasteRequested: (() -> Unit)?,
        onCutRequested: (() -> Unit)?,
        onSelectAllRequested: (() -> Unit)?,
        onAutofillRequested: (() -> Unit)?,
    ) {
        delegate.showMenu(
            rect = rect,
            onCopyRequested = onCopyRequested,
            onPasteRequested = onPasteRequested?.let {
                {
                    performMarkdownPaste(clipboardManager, state, wrapProgrammaticEdit)
                }
            },
            onCutRequested = onCutRequested,
            onSelectAllRequested = onSelectAllRequested,
            onAutofillRequested = onAutofillRequested,
        )
    }

    override fun showMenu(
        rect: Rect,
        onCopyRequested: (() -> Unit)?,
        onPasteRequested: (() -> Unit)?,
        onCutRequested: (() -> Unit)?,
        onSelectAllRequested: (() -> Unit)?,
    ) {
        delegate.showMenu(
            rect = rect,
            onCopyRequested = onCopyRequested,
            onPasteRequested = onPasteRequested?.let {
                {
                    performMarkdownPaste(clipboardManager, state, wrapProgrammaticEdit)
                }
            },
            onCutRequested = onCutRequested,
            onSelectAllRequested = onSelectAllRequested,
        )
    }
}

/**
 * Legacy copy path still uses [ClipboardManager]; block paste via [getText] for any caller that
 * still reads the clipboard manager API.
 */
@OptIn(ExperimentalRichTextApi::class)
internal class MarkdownPasteClipboardManager(
    private val parent: ClipboardManager,
    private val state: RichTextState,
    private val wrapProgrammaticEdit: (() -> Unit) -> Unit,
) : ClipboardManager {
    override fun hasText(): Boolean = parent.hasText()

    override fun setText(annotatedString: AnnotatedString) {
        parent.setText(annotatedString)
    }

    override fun getText(): AnnotatedString? {
        val clip = parent.getText() ?: return null
        if (clip.text.isEmpty()) return clip
        performMarkdownPaste(parent, state, wrapProgrammaticEdit)
        return null
    }
}
