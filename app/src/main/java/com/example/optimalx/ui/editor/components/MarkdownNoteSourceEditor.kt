package com.example.optimalx.ui.editor.components

import android.view.KeyEvent as AndroidKeyEvent
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.isCtrlPressed
import androidx.compose.ui.input.key.isMetaPressed
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.ClipboardManager
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.example.optimalx.ui.theme.DmSansFamily
import com.example.optimalx.ui.theme.LocalOptimalXColors

/**
 * Edit surface for notes: live preview of markdown (no visible `#` / `**`)
 * while the buffer stays canonical markdown source.
 */
@Composable
fun MarkdownNoteSourceEditor(
    state: MarkdownNoteEditorState,
    modifier: Modifier = Modifier,
    onEdited: () -> Unit = {},
) {
    val colors = LocalOptimalXColors.current
    val context = LocalContext.current
    val scrollState = rememberScrollState()
    val parentClipboard = LocalClipboardManager.current
    val copyClipboard = remember(parentClipboard, context) {
        RenderedMarkdownClipboardManager(context, parentClipboard)
    }

    fun pasteFromClipboard() {
        val (plain, html) = context.clipboardPlainAndHtml()
        state.pasteClipboard(plain, html)
        onEdited()
    }

    BoxWithConstraints(
        modifier = modifier.background(colors.surface),
    ) {
        val minEditorHeight = maxHeight
        Box(
            modifier = Modifier
                .fillMaxSize()
                .verticalScroll(scrollState)
                .onPreviewKeyEvent { event ->
                    val native = event.nativeKeyEvent
                    val isPasteShortcut =
                        event.type == KeyEventType.KeyDown &&
                            event.key == Key.V &&
                            (event.isCtrlPressed || event.isMetaPressed)
                    val isPasteKey =
                        native.keyCode == AndroidKeyEvent.KEYCODE_PASTE &&
                            native.action == AndroidKeyEvent.ACTION_DOWN
                    if (!isPasteShortcut && !isPasteKey) return@onPreviewKeyEvent false
                    pasteFromClipboard()
                    true
                },
        ) {
            CompositionLocalProvider(LocalClipboardManager provides copyClipboard) {
                BasicTextField(
                    value = state.value,
                    onValueChange = { next ->
                        state.onValueChange(next)
                        onEdited()
                    },
                    modifier = Modifier
                        .fillMaxWidth()
                        .heightIn(min = minEditorHeight)
                        .padding(horizontal = 16.dp, vertical = 12.dp),
                    textStyle = TextStyle(
                        fontFamily = DmSansFamily,
                        fontWeight = FontWeight.Normal,
                        fontSize = 15.sp,
                        lineHeight = 24.sp,
                        color = colors.textPrimary,
                    ),
                    cursorBrush = SolidColor(colors.accent),
                    visualTransformation = VisualTransformation { text ->
                        NoteMarkdownVisual.transform(text.text)
                    },
                )
            }
        }
    }
}

private class RenderedMarkdownClipboardManager(
    private val context: android.content.Context,
    private val parent: ClipboardManager,
) : ClipboardManager {
    override fun hasText(): Boolean = parent.hasText()

    override fun getText(): AnnotatedString? = parent.getText()

    override fun setText(annotatedString: AnnotatedString) {
        context.copyMarkdownWithRenderedHtml(annotatedString.text)
    }
}
