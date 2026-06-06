package com.example.optimalx.ui.dumpedit

import android.content.Intent
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.imePadding
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalLifecycleOwner
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import com.example.optimalx.ui.editor.components.DumpEditDropdownMenu
import com.example.optimalx.ui.editor.components.FormattingToolbar
import com.example.optimalx.ui.editor.components.NoteFontSize
import com.example.optimalx.ui.theme.DmSansFamily
import com.example.optimalx.ui.theme.LocalOptimalXColors
import com.mohamedrejeb.richeditor.model.rememberRichTextState
import com.mohamedrejeb.richeditor.ui.material3.RichTextEditor
import com.mohamedrejeb.richeditor.ui.material3.RichTextEditorDefaults
import kotlinx.coroutines.flow.SharedFlow

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun DumpEditPanel(
    initialContent: String,
    initialContentReady: Boolean,
    isViewMode: Boolean,
    isAiLocked: Boolean,
    isAiBlind: Boolean,
    undoClearAvailable: Boolean,
    restoreContentFlow: SharedFlow<String>,
    onContentChanged: (String) -> Unit,
    onUndo: () -> Unit,
    onRedo: () -> Unit,
    onToggleViewMode: () -> Unit,
    onToggleAiLock: () -> Unit,
    onToggleAiBlind: () -> Unit,
    onClear: () -> Unit,
    onUndoClear: () -> Unit,
    onPromote: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val colors = LocalOptimalXColors.current
    val context = LocalContext.current
    val richTextState = rememberRichTextState()

    var currentFontSize by remember { mutableStateOf(NoteFontSize.MEDIUM) }
    var showDropdown by remember { mutableStateOf(false) }
    var initialized by remember { mutableStateOf(false) }

    LaunchedEffect(initialContentReady, initialContent) {
        if (!initialized && initialContentReady) {
            setRichTextContent(richTextState, initialContent)
            initialized = true
        }
    }

    LaunchedEffect(Unit) {
        restoreContentFlow.collect { html ->
            setRichTextContent(richTextState, html)
        }
    }

    val latestOnContentChanged = rememberUpdatedState(onContentChanged)
    val latestInitialized = rememberUpdatedState(initialized)
    DisposableEffect(Unit) {
        onDispose {
            if (latestInitialized.value) {
                latestOnContentChanged.value(richTextState.toHtml())
            }
        }
    }
    val lifecycleOwner = LocalLifecycleOwner.current
    DisposableEffect(lifecycleOwner, richTextState) {
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_STOP && latestInitialized.value) {
                latestOnContentChanged.value(richTextState.toHtml())
            }
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose { lifecycleOwner.lifecycle.removeObserver(observer) }
    }

    Column(modifier = modifier.fillMaxSize()) {
        Box {
            FormattingToolbar(
                richTextState = richTextState,
                currentFontSize = currentFontSize,
                onFontSizeCycle = {
                    currentFontSize = currentFontSize.next()
                    richTextState.addSpanStyle(SpanStyle(fontSize = currentFontSize.sp))
                },
                onUndo = onUndo,
                onRedo = onRedo,
                showReadAloud = false,
                readAloudSessionActive = false,
                onReadAloudClick = {},
                onMoreClick = { showDropdown = true },
            )
            DumpEditDropdownMenu(
                expanded = showDropdown,
                isViewMode = isViewMode,
                isAiLocked = isAiLocked,
                isAiBlind = isAiBlind,
                undoClearAvailable = undoClearAvailable,
                onDismiss = { showDropdown = false },
                onToggleStrikethrough = {
                    richTextState.toggleSpanStyle(SpanStyle(textDecoration = TextDecoration.LineThrough))
                },
                onToggleViewMode = onToggleViewMode,
                onToggleAiLock = onToggleAiLock,
                onToggleAiBlind = onToggleAiBlind,
                onExport = {
                    val text = richTextState.toText()
                    context.startActivity(
                        Intent.createChooser(
                            Intent(Intent.ACTION_SEND).apply {
                                type = "text/plain"
                                putExtra(Intent.EXTRA_TEXT, text)
                            },
                            "Export buffer",
                        ),
                    )
                },
                onShare = {
                    val text = richTextState.toText()
                    context.startActivity(
                        Intent.createChooser(
                            Intent(Intent.ACTION_SEND).apply {
                                type = "text/plain"
                                putExtra(Intent.EXTRA_TEXT, text)
                            },
                            "Share buffer",
                        ),
                    )
                },
                onClear = onClear,
                onUndoClear = onUndoClear,
                onPromote = onPromote,
            )
        }

        RichTextEditor(
            state = richTextState,
            readOnly = isViewMode,
            modifier = Modifier
                .fillMaxWidth()
                .weight(1f)
                .background(colors.surface)
                .imePadding(),
            textStyle = TextStyle(
                color = colors.textPrimary,
                fontFamily = DmSansFamily,
                fontWeight = FontWeight.Normal,
                fontSize = 15.sp,
                lineHeight = 24.sp,
            ),
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

private fun setRichTextContent(
    richTextState: com.mohamedrejeb.richeditor.model.RichTextState,
    content: String,
) {
    val isHtml = content.contains("<p", ignoreCase = true) ||
        content.contains("<div", ignoreCase = true) ||
        content.contains("<br", ignoreCase = true) ||
        content.contains("<span", ignoreCase = true)
    if (isHtml) {
        richTextState.setHtml(content)
    } else {
        richTextState.setMarkdown(content)
    }
}
