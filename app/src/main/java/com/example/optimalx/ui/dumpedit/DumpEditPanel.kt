package com.example.optimalx.ui.dumpedit

import android.Manifest
import android.content.pm.PackageManager
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.imePadding
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalLifecycleOwner
import androidx.core.content.ContextCompat
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import com.example.optimalx.ui.components.ClearVoiceRecordingDialog
import com.example.optimalx.ui.components.NoteContentCodec
import com.example.optimalx.ui.editor.NoteExportFormat
import com.example.optimalx.ui.editor.rememberNoteExportActions
import com.example.optimalx.ui.editor.components.DumpEditDropdownMenu
import com.example.optimalx.ui.editor.components.EditorModeFab
import com.example.optimalx.ui.editor.components.FormattingToolbar
import com.example.optimalx.ui.editor.components.MarkdownNoteEditorState
import com.example.optimalx.ui.editor.components.MarkdownNoteSourceEditor
import com.example.optimalx.ui.editor.components.MarkdownNoteViewPanel
import com.example.optimalx.ui.editor.components.NoteDictationBar
import com.example.optimalx.ui.editor.components.NoteReadAloudBar
import com.example.optimalx.voice.VoiceController
import com.example.optimalx.voice.VoiceSessionState
import com.example.optimalx.voice.sanitizeNoteContentForTts
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
    onEditorSnapshot: (String) -> Unit,
    onEditorLoaded: (String) -> Unit,
    onEditSessionStarted: (String) -> Unit,
    onRegisterLiveContentProvider: ((() -> String)?) -> Unit,
    onContentChanged: (String) -> Unit,
    isDirty: Boolean,
    userHasEdited: Boolean,
    onToggleViewMode: () -> Unit,
    onToggleAiLock: () -> Unit,
    onToggleAiBlind: () -> Unit,
    onClear: () -> Unit,
    onUndoClear: () -> Unit,
    onPromote: () -> Unit,
    onReadAloud: (plainText: String) -> Unit,
    onStopSpeech: () -> Unit,
    readAloudBarVisible: Boolean,
    readAloudIsPlaying: Boolean,
    onToggleReadAloudPlayback: () -> Unit,
    onReadAloudRewind10: () -> Unit,
    onReadAloudForward10: () -> Unit,
    voiceController: VoiceController? = null,
    exportBaseName: String = "dump-edit",
    onFlushBeforeExport: suspend (String) -> Unit = {},
    modifier: Modifier = Modifier,
) {
    val context = LocalContext.current
    val editorState = remember { MarkdownNoteEditorState() }

    var showDropdown by remember { mutableStateOf(false) }
    var initialized by remember { mutableStateOf(false) }
    var showClearRecordingDialog by remember { mutableStateOf(false) }

    val sessionState = voiceController?.sessionState?.collectAsState()?.value ?: VoiceSessionState.IDLE
    val isDictationActive = sessionState == VoiceSessionState.LISTENING ||
        sessionState == VoiceSessionState.TRANSCRIBING ||
        sessionState == VoiceSessionState.PAUSED

    val latestOnContentChanged = rememberUpdatedState(onContentChanged)
    val latestOnEditorSnapshot = rememberUpdatedState(onEditorSnapshot)
    val latestOnEditorLoaded = rememberUpdatedState(onEditorLoaded)
    val latestOnEditSessionStarted = rememberUpdatedState(onEditSessionStarted)
    val latestOnRegisterLiveContentProvider = rememberUpdatedState(onRegisterLiveContentProvider)
    val latestInitialized = rememberUpdatedState(initialized)
    val latestEditorState = rememberUpdatedState(editorState)

    fun currentMarkdown(): String = editorState.markdown

    fun emitEditorSnapshot() {
        if (!isViewMode) {
            latestOnEditorSnapshot.value(currentMarkdown())
        }
    }

    fun startDictation(controller: VoiceController) {
        controller.startListening(existingText = "") { heard ->
            editorState.appendPlain(heard)
            val markdown = currentMarkdown()
            latestOnEditorSnapshot.value(markdown)
            latestOnContentChanged.value(markdown)
        }
    }

    val permissionLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestPermission(),
    ) { granted ->
        if (granted && voiceController != null) {
            startDictation(voiceController)
        }
    }

    DisposableEffect(voiceController) {
        onDispose { voiceController?.stopSession() }
    }

    LaunchedEffect(initialContentReady, initialContent, userHasEdited) {
        if (!initialized && initialContentReady) {
            editorState.load(NoteContentCodec.normalizeLegacyToMarkdown(initialContent))
            initialized = true
            latestOnEditorLoaded.value(editorState.markdown)
        }
    }

    var previousIsViewMode by remember { mutableStateOf<Boolean?>(null) }
    LaunchedEffect(isViewMode, initialized) {
        if (!initialized) return@LaunchedEffect
        val wasViewMode = previousIsViewMode
        previousIsViewMode = isViewMode
        if (isViewMode) return@LaunchedEffect
        val enteringEdit = wasViewMode == null || wasViewMode
        if (enteringEdit) {
            latestOnEditSessionStarted.value(currentMarkdown())
        }
    }

    LaunchedEffect(Unit) {
        restoreContentFlow.collect { content ->
            editorState.load(content)
            latestOnEditorLoaded.value(editorState.markdown)
            if (!isViewMode) {
                latestOnEditSessionStarted.value(editorState.markdown)
            }
        }
    }

    DisposableEffect(isViewMode) {
        latestOnRegisterLiveContentProvider.value { latestEditorState.value.markdown }
        onDispose { latestOnRegisterLiveContentProvider.value(null) }
    }

    DisposableEffect(Unit) {
        onDispose {
            if (latestInitialized.value) {
                latestOnContentChanged.value(latestEditorState.value.markdown)
            }
        }
    }
    val lifecycleOwner = LocalLifecycleOwner.current
    DisposableEffect(lifecycleOwner) {
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_STOP && latestInitialized.value) {
                latestOnContentChanged.value(latestEditorState.value.markdown)
            }
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose { lifecycleOwner.lifecycle.removeObserver(observer) }
    }

    fun handleToggleViewMode() {
        if (isViewMode) {
            latestOnEditSessionStarted.value(currentMarkdown())
        } else {
            latestOnContentChanged.value(currentMarkdown())
        }
        onToggleViewMode()
    }

    val noteExport = rememberNoteExportActions(
        exportBaseName = exportBaseName,
        shareChooserTitle = "Share buffer",
        onFlushBeforeExport = onFlushBeforeExport,
    )

    Column(modifier = modifier.fillMaxSize()) {
        if (readAloudBarVisible) {
            NoteReadAloudBar(
                isPlaying = readAloudIsPlaying,
                onPlayPause = onToggleReadAloudPlayback,
                onRewind10 = onReadAloudRewind10,
                onForward10 = onReadAloudForward10,
            )
        }

        if (isDictationActive && voiceController != null) {
            NoteDictationBar(
                sessionState = sessionState,
                onPause = { voiceController.pauseListening() },
                onResume = { voiceController.resumeListening() },
                onDiscardRequest = { showClearRecordingDialog = true },
                onSend = { voiceController.commitVoiceThen { } },
            )
        }

        FormattingToolbar(
            editorState = editorState,
            onAfterFormatAction = ::emitEditorSnapshot,
            isViewMode = isViewMode,
            onToggleViewMode = { handleToggleViewMode() },
            readAloudSessionActive = readAloudBarVisible,
            onReadAloudClick = {
                if (readAloudBarVisible) onStopSpeech()
                else onReadAloud(sanitizeNoteContentForTts(currentMarkdown()))
            },
            noteMicActive = isDictationActive,
            noteMicEnabled = !isViewMode && voiceController != null && !isDictationActive,
            onNoteMicClick = {
                val controller = voiceController ?: return@FormattingToolbar
                if (sessionState != VoiceSessionState.IDLE) return@FormattingToolbar
                val granted = ContextCompat.checkSelfPermission(
                    context,
                    Manifest.permission.RECORD_AUDIO,
                ) == PackageManager.PERMISSION_GRANTED
                if (granted) {
                    startDictation(controller)
                } else {
                    permissionLauncher.launch(Manifest.permission.RECORD_AUDIO)
                }
            },
            onMoreClick = { showDropdown = true },
            moreMenuContent = {
                DumpEditDropdownMenu(
                    expanded = showDropdown,
                    isViewMode = isViewMode,
                    isAiLocked = isAiLocked,
                    isAiBlind = isAiBlind,
                    undoClearAvailable = undoClearAvailable,
                    onDismiss = { showDropdown = false },
                    onToggleViewMode = {
                        showDropdown = false
                        handleToggleViewMode()
                    },
                    onToggleAiLock = onToggleAiLock,
                    onToggleAiBlind = onToggleAiBlind,
                    onExportPdf = {
                        showDropdown = false
                        noteExport.export(currentMarkdown(), NoteExportFormat.PDF)
                    },
                    onExportMarkdown = {
                        showDropdown = false
                        noteExport.export(currentMarkdown(), NoteExportFormat.MARKDOWN)
                    },
                    onSharePdf = {
                        showDropdown = false
                        noteExport.sharePdf(currentMarkdown())
                    },
                    onShare = {
                        showDropdown = false
                        noteExport.share(currentMarkdown())
                    },
                    onClear = onClear,
                    onUndoClear = onUndoClear,
                    onPromote = onPromote,
                )
            },
        )

        if (showClearRecordingDialog && voiceController != null) {
            ClearVoiceRecordingDialog(
                onConfirm = {
                    showClearRecordingDialog = false
                    voiceController.discardRecording()
                },
                onDismiss = { showClearRecordingDialog = false },
            )
        }

        Box(
            modifier = Modifier
                .fillMaxWidth()
                .weight(1f)
                .imePadding(),
        ) {
            if (isViewMode) {
                MarkdownNoteViewPanel(
                    content = currentMarkdown(),
                    modifier = Modifier.fillMaxSize(),
                )
                EditorModeFab(
                    isViewMode = true,
                    onClick = { handleToggleViewMode() },
                    modifier = Modifier.align(Alignment.BottomEnd),
                )
            } else {
                MarkdownNoteSourceEditor(
                    state = editorState,
                    modifier = Modifier.fillMaxSize(),
                    onEdited = { emitEditorSnapshot() },
                )
            }
        }
    }
}
