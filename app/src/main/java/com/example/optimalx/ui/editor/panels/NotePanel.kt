package com.example.optimalx.ui.editor.panels

import android.Manifest
import android.content.pm.PackageManager
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.runtime.collectAsState
import androidx.core.content.ContextCompat
import com.example.optimalx.voice.VoiceController
import com.example.optimalx.voice.VoiceSessionState
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
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.mohamedrejeb.richeditor.model.rememberRichTextState
import com.mohamedrejeb.richeditor.ui.material3.RichTextEditor
import com.mohamedrejeb.richeditor.ui.material3.RichTextEditorDefaults
import com.example.optimalx.ui.editor.components.EditorDropdownMenu
import com.example.optimalx.ui.editor.components.FormattingToolbar
import com.example.optimalx.ui.editor.components.NoteDictationBar
import com.example.optimalx.ui.editor.components.NoteReadAloudBar
import com.example.optimalx.ui.components.ClearVoiceRecordingDialog
import com.example.optimalx.ui.editor.components.NoteFontSize
import com.example.optimalx.ui.theme.DmSansFamily
import com.example.optimalx.ui.theme.LocalOptimalXColors
import kotlinx.coroutines.flow.SharedFlow

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun NotePanel(
    initialContent: String,
    initialContentReady: Boolean,
    isViewMode: Boolean,
    isAiLocked: Boolean,
    isAiBlind: Boolean,
    restoreContentFlow: SharedFlow<String>,
    onContentChanged: (String) -> Unit,
    onUndo: () -> Unit,
    onRedo: () -> Unit,
    onToggleViewMode: () -> Unit,
    onToggleAiLock: () -> Unit,
    onToggleAiBlind: () -> Unit,
    hasNoteSummary: Boolean = false,
    summaryGenerating: Boolean = false,
    onGenerateSummary: () -> Unit = {},
    onReadNoteAloud: (plainText: String) -> Unit,
    onStopNoteSpeech: () -> Unit,
    noteReadAloudBarVisible: Boolean,
    noteReadAloudIsPlaying: Boolean,
    onToggleNoteReadAloudPlayback: () -> Unit,
    onNoteReadAloudRewind10: () -> Unit,
    onNoteReadAloudForward10: () -> Unit,
    voiceController: VoiceController? = null,
    modifier: Modifier = Modifier,
) {
    val colors = LocalOptimalXColors.current
    val context = LocalContext.current
    val richTextState = rememberRichTextState()

    var currentFontSize by remember { mutableStateOf(NoteFontSize.MEDIUM) }
    var showDropdown by remember { mutableStateOf(false) }
    var initialized by remember { mutableStateOf(false) }

    val sessionState = voiceController?.sessionState?.collectAsState()?.value ?: VoiceSessionState.IDLE
    val isNoteDictationActive = sessionState == VoiceSessionState.LISTENING ||
        sessionState == VoiceSessionState.TRANSCRIBING ||
        sessionState == VoiceSessionState.PAUSED
    var showClearRecordingDialog by remember { mutableStateOf(false) }

    val latestOnContentChanged = rememberUpdatedState(onContentChanged)
    val latestInitialized = rememberUpdatedState(initialized)

    val permissionLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestPermission(),
    ) { granted ->
        if (granted && voiceController != null) {
            startNoteDictation(voiceController, richTextState) { html ->
                latestOnContentChanged.value(html)
            }
        }
    }

    DisposableEffect(voiceController) {
        onDispose { voiceController?.stopSession() }
    }

    // Initialize from DB content once the note is actually loaded.
    // This avoids a race where we apply empty content before Room emits.
    LaunchedEffect(initialContentReady, initialContent) {
        if (!initialized && initialContentReady) {
            setRichTextContent(richTextState, initialContent)
            initialized = true
        }
    }

    // Restore content on undo/redo events from ViewModel
    LaunchedEffect(Unit) {
        restoreContentFlow.collect { html ->
            setRichTextContent(richTextState, html)
        }
    }

    // Persist when leaving the note (pager swaps away beyondViewportPageCount=0), leaving the editor,
    // or fragment/activity stopping — no idle debounced autosave.
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

        if (noteReadAloudBarVisible) {
            NoteReadAloudBar(
                isPlaying = noteReadAloudIsPlaying,
                onPlayPause = onToggleNoteReadAloudPlayback,
                onRewind10 = onNoteReadAloudRewind10,
                onForward10 = onNoteReadAloudForward10,
            )
        }

        if (isNoteDictationActive && voiceController != null) {
            NoteDictationBar(
                sessionState = sessionState,
                onPause = { voiceController.pauseListening() },
                onResume = { voiceController.resumeListening() },
                onDiscardRequest = { showClearRecordingDialog = true },
                onSend = { voiceController.commitVoiceThen { } },
            )
        }

        // Formatting toolbar + dropdown anchor
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
                readAloudSessionActive = noteReadAloudBarVisible,
                onReadAloudClick = {
                    if (noteReadAloudBarVisible) onStopNoteSpeech()
                    else onReadNoteAloud(richTextState.toText())
                },
                noteMicActive = isNoteDictationActive,
                noteMicEnabled = !isViewMode && voiceController != null && !isNoteDictationActive,
                onNoteMicClick = {
                    val controller = voiceController ?: return@FormattingToolbar
                    if (sessionState != VoiceSessionState.IDLE) return@FormattingToolbar
                    val granted = ContextCompat.checkSelfPermission(
                        context,
                        Manifest.permission.RECORD_AUDIO,
                    ) == PackageManager.PERMISSION_GRANTED
                    if (granted) {
                        startNoteDictation(controller, richTextState) { html ->
                            onContentChanged(html)
                        }
                    } else {
                        permissionLauncher.launch(Manifest.permission.RECORD_AUDIO)
                    }
                },
                onMoreClick = { showDropdown = true },
            )
            EditorDropdownMenu(
                expanded = showDropdown,
                isViewMode = isViewMode,
                isAiLocked = isAiLocked,
                isAiBlind = isAiBlind,
                hasNoteSummary = hasNoteSummary,
                summaryGenerating = summaryGenerating,
                onDismiss = { showDropdown = false },
                onGenerateSummary = onGenerateSummary,
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
                            "Export note",
                        )
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
                            "Share note",
                        )
                    )
                },
            )
        }

        // Main editor
        if (showClearRecordingDialog && voiceController != null) {
            ClearVoiceRecordingDialog(
                onConfirm = {
                    showClearRecordingDialog = false
                    voiceController.discardRecording()
                },
                onDismiss = { showClearRecordingDialog = false },
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

private fun startNoteDictation(
    voiceController: VoiceController,
    richTextState: com.mohamedrejeb.richeditor.model.RichTextState,
    onSaved: (String) -> Unit,
) {
    // Note body is appended in the callback; do not pass it as STT base or Whisper
    // will return base+speech and duplicate the entire note on commit.
    voiceController.startListening(existingText = "") { heard ->
        appendPlainTextToNote(richTextState, heard)
        onSaved(richTextState.toHtml())
    }
}

private fun appendPlainTextToNote(
    richTextState: com.mohamedrejeb.richeditor.model.RichTextState,
    heard: String,
) {
    val addition = heard.trim()
    if (addition.isBlank()) return
    val current = richTextState.toText().trim()
    val merged = if (current.isBlank()) addition else "$current\n\n$addition"
    setRichTextContent(richTextState, merged)
}
