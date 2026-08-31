package com.example.optimalx.ui.editor.components

import android.view.KeyEvent as AndroidKeyEvent
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.ScrollState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.isCtrlPressed
import androidx.compose.ui.input.key.isMetaPressed
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.platform.LocalClipboard
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.platform.LocalTextToolbar
import androidx.compose.ui.text.TextRange
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.example.optimalx.ui.components.MarkdownRichText
import com.example.optimalx.ui.components.NoteContentCodec
import com.example.optimalx.ui.theme.DmSansFamily
import com.example.optimalx.ui.theme.LocalOptimalXColors
import com.example.optimalx.voice.VoiceController
import com.mohamedrejeb.richeditor.annotation.ExperimentalRichTextApi
import com.mohamedrejeb.richeditor.model.RichTextState
import com.mohamedrejeb.richeditor.ui.BasicRichText
import com.mohamedrejeb.richeditor.ui.material3.RichTextEditor
import com.mohamedrejeb.richeditor.ui.material3.RichTextEditorDefaults

/** Shared load/save/dictation helpers for [NotePanel] and [DumpEditPanel]. */
@OptIn(ExperimentalRichTextApi::class)
object RichTextNotePanelSupport {

    fun loadContent(state: RichTextState, stored: String) {
        NoteContentCodec.loadIntoRichText(state, stored)
        // setMarkdown leaves the caret at EOF; first focus/long-press then scrolls to the bottom.
        state.selection = TextRange.Zero
    }

    fun persistContent(state: RichTextState): String =
        NoteContentCodec.persistFromRichText(state)

    fun startDictation(
        voiceController: VoiceController,
        richTextState: RichTextState,
        onSaved: (String) -> Unit,
    ) {
        // Append in callback only — do not pass note body as STT base or Whisper duplicates it.
        voiceController.startListening(existingText = "") { heard ->
            NoteContentCodec.appendPlainTextToRichText(richTextState, heard)
            onSaved(persistContent(richTextState))
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class, ExperimentalRichTextApi::class)
@Composable
fun RichTextNoteEditor(
    state: RichTextState,
    readOnly: Boolean,
    scrollState: ScrollState,
    modifier: Modifier = Modifier,
    wrapProgrammaticEdit: (() -> Unit) -> Unit = { it() },
) {
    val colors = LocalOptimalXColors.current
    val keyboard = LocalSoftwareKeyboardController.current
    val selection = state.selection
    val parentClipboardManager = LocalClipboardManager.current
    val parentClipboard = LocalClipboard.current
    val parentTextToolbar = LocalTextToolbar.current
    val markdownPasteClipboard = remember(parentClipboard) {
        MarkdownPasteClipboard(parentClipboard)
    }
    val markdownPasteClipboardManager = remember(parentClipboardManager, state, wrapProgrammaticEdit) {
        MarkdownPasteClipboardManager(parentClipboardManager, state, wrapProgrammaticEdit)
    }
    val markdownPasteTextToolbar = remember(
        parentTextToolbar,
        parentClipboardManager,
        state,
        wrapProgrammaticEdit,
    ) {
        MarkdownPasteTextToolbar(
            delegate = parentTextToolbar,
            clipboardManager = parentClipboardManager,
            state = state,
            wrapProgrammaticEdit = wrapProgrammaticEdit,
        )
    }

    // Long-press selection focuses the field and opens the IME; hide it while highlighting.
    LaunchedEffect(selection.start, selection.end) {
        if (!selection.collapsed) {
            keyboard?.hide()
        }
    }

    BoxWithConstraints(
        modifier = modifier.background(colors.surface),
    ) {
        val minEditorHeight = maxHeight
        Box(
            modifier = Modifier
                .fillMaxSize()
                .verticalScroll(scrollState),
        ) {
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .onPreviewKeyEvent { event ->
                        if (readOnly) return@onPreviewKeyEvent false
                        val native = event.nativeKeyEvent
                        val isPasteShortcut =
                            event.type == KeyEventType.KeyDown &&
                                event.key == Key.V &&
                                (event.isCtrlPressed || event.isMetaPressed)
                        val isPasteKey =
                            native.keyCode == AndroidKeyEvent.KEYCODE_PASTE &&
                                native.action == AndroidKeyEvent.ACTION_DOWN
                        if (!isPasteShortcut && !isPasteKey) return@onPreviewKeyEvent false
                        performMarkdownPaste(parentClipboardManager, state, wrapProgrammaticEdit)
                        true
                    },
            ) {
                CompositionLocalProvider(
                    LocalClipboard provides markdownPasteClipboard,
                    LocalClipboardManager provides markdownPasteClipboardManager,
                    LocalTextToolbar provides markdownPasteTextToolbar,
                ) {
                    RichTextEditor(
                        state = state,
                        readOnly = readOnly,
                        modifier = Modifier
                            .fillMaxWidth()
                            .heightIn(min = minEditorHeight),
                        textStyle = noteRichTextStyle.copy(color = colors.textPrimary),
                        colors = RichTextEditorDefaults.richTextEditorColors(
                            textColor = colors.textPrimary,
                            containerColor = colors.surface,
                            cursorColor = colors.accent,
                            focusedIndicatorColor = colors.accent,
                            unfocusedIndicatorColor = colors.border,
                            placeholderColor = colors.textDim,
                        ),
                        contentPadding = PaddingValues(horizontal = 16.dp, vertical = 12.dp),
                    )
                }
            }
        }
    }
}

private val noteRichTextStyle = TextStyle(
    fontFamily = DmSansFamily,
    fontWeight = FontWeight.Normal,
    fontSize = 15.sp,
    lineHeight = 24.sp,
)

/**
 * Read-only note body using the same [RichTextState] as [RichTextNoteEditor].
 * [RichTextEditor] with `readOnly=true` disables selection; [BasicRichText] inside
 * [SelectionContainer] keeps WYSIWYG parity and allows copy.
 */
@OptIn(ExperimentalRichTextApi::class)
@Composable
fun RichTextNoteView(
    state: RichTextState,
    scrollState: ScrollState,
    modifier: Modifier = Modifier,
) {
    val colors = LocalOptimalXColors.current
    SelectionContainer(
        modifier = modifier
            .background(colors.surface)
            .verticalScroll(scrollState)
            .navigationBarsPadding()
            .padding(horizontal = 16.dp, vertical = 12.dp),
    ) {
        BasicRichText(
            state = state,
            modifier = Modifier.fillMaxWidth(),
            style = noteRichTextStyle.copy(color = colors.textPrimary),
        )
    }
}

@Composable
fun MarkdownNoteViewPanel(
    content: String,
    modifier: Modifier = Modifier,
    selectable: Boolean = true,
) {
    val colors = LocalOptimalXColors.current
    val scrollState = rememberScrollState()

    Box(
        modifier = modifier
            .background(colors.surface)
            .verticalScroll(scrollState)
            .navigationBarsPadding()
            .padding(horizontal = 16.dp, vertical = 12.dp),
    ) {
        MarkdownRichText(
            text = content,
            style = TextStyle(
                color = colors.textPrimary,
                fontFamily = DmSansFamily,
                fontSize = 15.sp,
                lineHeight = 24.sp,
            ),
            modifier = Modifier.fillMaxWidth(),
            selectable = selectable,
        )
    }
}
