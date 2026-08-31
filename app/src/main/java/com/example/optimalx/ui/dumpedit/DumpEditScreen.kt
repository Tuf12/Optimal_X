package com.example.optimalx.ui.dumpedit

import android.app.Application
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarDuration
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.SnackbarResult
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.sp
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import androidx.compose.runtime.rememberCoroutineScope
import com.example.optimalx.ui.components.NoteContentCodec
import com.example.optimalx.ui.editor.NoteEditorSaveStatus
import com.example.optimalx.ui.editor.toStatusLabel
import com.example.optimalx.ui.eidos.EidosChatViewModel
import com.example.optimalx.ui.navigation.RegisterNavigationLeaveGuard
import com.example.optimalx.ui.theme.DmSansFamily
import com.example.optimalx.ui.theme.LocalOptimalXColors
import com.example.optimalx.ui.theme.SyneFamily
import com.example.optimalx.voice.VoiceController
import kotlinx.coroutines.launch

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun DumpEditScreen(
    eidosViewModel: EidosChatViewModel,
    onBack: () -> Unit,
    onEidosClick: () -> Unit,
    onSettingsClick: (() -> Unit)? = null,
    onPromotedToEditor: ((Long) -> Unit)? = null,
) {
    val viewModel: DumpEditViewModel = viewModel(
        key = "dump_edit",
        factory = viewModelFactory {
            initializer {
                val app = this[androidx.lifecycle.ViewModelProvider.AndroidViewModelFactory.APPLICATION_KEY]!!
                DumpEditViewModel(app as Application)
            }
        },
    )
    val voiceController: VoiceController = viewModel(
        key = "dump_edit_voice",
        factory = viewModelFactory {
            initializer {
                val app = this[androidx.lifecycle.ViewModelProvider.AndroidViewModelFactory.APPLICATION_KEY]!!
                VoiceController(app)
            }
        },
    )

    val colors = LocalOptimalXColors.current
    val state by viewModel.state.collectAsState()
    val contentReady by viewModel.contentReady.collectAsState()
    val isViewMode by viewModel.isViewMode.collectAsState()
    val userHasEdited by viewModel.userHasEdited.collectAsState()
    val isDirty by viewModel.isDirty.collectAsState()
    val saveStatus by viewModel.saveStatus.collectAsState()
    val isAiLocked by viewModel.isAiLocked.collectAsState()
    val isAiBlind by viewModel.isAiBlind.collectAsState()
    val readAloudBarVisible by viewModel.readAloudBarVisible.collectAsState()
    val readAloudIsPlaying by viewModel.readAloudIsPlaying.collectAsState()
    val showClearUndoSnackbar by viewModel.showClearUndoSnackbar.collectAsState()
    val parentFolderOptions by viewModel.parentFolderOptions.collectAsState()

    var showClearConfirm by remember { mutableStateOf(false) }
    var showPromoteDialog by remember { mutableStateOf(false) }
    var promoteFeedback by remember { mutableStateOf<String?>(null) }
    RegisterNavigationLeaveGuard(hasUnsavedChanges = isDirty)

    val snackbarHostState = remember { SnackbarHostState() }
    val scope = rememberCoroutineScope()

    val exitDumpEdit: () -> Unit = {
        scope.launch {
            viewModel.flushBeforeExit()
            onBack()
        }
    }

    LaunchedEffect(showClearUndoSnackbar) {
        if (!showClearUndoSnackbar) return@LaunchedEffect
        val result = snackbarHostState.showSnackbar(
            message = "Buffer cleared",
            actionLabel = "Undo",
            duration = SnackbarDuration.Long,
        )
        viewModel.dismissClearUndoSnackbar()
        if (result == SnackbarResult.ActionPerformed) {
            viewModel.undoClear()
        }
    }

    LaunchedEffect(viewModel) {
        viewModel.promoteResult.collect { result ->
            when (result) {
                is PromoteResult.Success -> {
                    showPromoteDialog = false
                    promoteFeedback =
                        "Saved to ${result.parentName} / ${result.subfolderName}. Buffer unchanged."
                    onPromotedToEditor?.invoke(result.subfolderId)
                }
                is PromoteResult.Error -> {
                    promoteFeedback = result.message
                }
            }
        }
    }

    BackHandler(onBack = exitDumpEdit)

    DisposableEffect(voiceController) {
        onDispose { voiceController.stopSession() }
    }

    Scaffold(
        modifier = Modifier.fillMaxSize(),
        containerColor = colors.background,
        snackbarHost = { SnackbarHost(snackbarHostState) },
        topBar = {
            TopAppBar(
                modifier = Modifier.statusBarsPadding(),
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = colors.background,
                    titleContentColor = colors.textPrimary,
                    navigationIconContentColor = colors.textMid,
                ),
                title = {
                    Column {
                        Text(
                            text = "DumpEdit",
                            fontFamily = SyneFamily,
                            fontWeight = FontWeight.ExtraBold,
                            color = colors.textPrimary,
                            fontSize = 18.sp,
                        )
                        Text(
                            text = "Scratch buffer — not saved to a folder",
                            fontFamily = DmSansFamily,
                            color = colors.textDim,
                            fontSize = 13.sp,
                        )
                        saveStatus.toStatusLabel()?.let { label ->
                            Text(
                                text = label,
                                fontFamily = DmSansFamily,
                                color = colors.textDim,
                                fontSize = 12.sp,
                            )
                        }
                    }
                },
                navigationIcon = {
                    IconButton(onClick = exitDumpEdit) {
                        Icon(
                            imageVector = Icons.AutoMirrored.Filled.ArrowBack,
                            contentDescription = "Back",
                            tint = colors.textMid,
                        )
                    }
                },
                actions = {
                    TextButton(onClick = onEidosClick) {
                        Text("Eidos", color = colors.accent, fontFamily = DmSansFamily)
                    }
                    if (onSettingsClick != null) {
                        TextButton(onClick = onSettingsClick) {
                            Text("⋯", color = colors.textMid, fontSize = 18.sp)
                        }
                    }
                },
            )
        },
    ) { padding ->
        DumpEditPanel(
            initialContent = NoteContentCodec.normalizeLegacyToMarkdown(state.content),
            initialContentReady = contentReady,
            isViewMode = isViewMode,
            isAiLocked = isAiLocked,
            isAiBlind = isAiBlind,
            undoClearAvailable = viewModel.canUndoClear,
            restoreContentFlow = viewModel.restoreContent,
            onEditorSnapshot = viewModel::onEditorSnapshot,
            onEditorLoaded = viewModel::markEditorLoaded,
            onEditSessionStarted = viewModel::onEditSessionStarted,
            onRegisterLiveContentProvider = viewModel::setLiveContentProvider,
            onContentChanged = viewModel::onContentSave,
            isDirty = isDirty,
            userHasEdited = userHasEdited,
            onToggleViewMode = viewModel::toggleViewMode,
            onToggleAiLock = viewModel::toggleAiLock,
            onToggleAiBlind = viewModel::toggleAiBlind,
            onClear = { showClearConfirm = true },
            onUndoClear = viewModel::undoClear,
            onPromote = {
                viewModel.loadPromoteTargets()
                showPromoteDialog = true
            },
            exportBaseName = "dump-edit",
            onFlushBeforeExport = viewModel::flushBeforeExport,
            onReadAloud = viewModel::speakAloud,
            onStopSpeech = viewModel::stopSpeech,
            readAloudBarVisible = readAloudBarVisible,
            readAloudIsPlaying = readAloudIsPlaying,
            onToggleReadAloudPlayback = viewModel::toggleReadAloudPlayback,
            onReadAloudRewind10 = viewModel::readAloudRewind10Seconds,
            onReadAloudForward10 = viewModel::readAloudForward10Seconds,
            voiceController = voiceController,
            modifier = Modifier
                .fillMaxSize()
                .background(colors.background)
                .padding(padding),
        )
    }

    if (showClearConfirm) {
        AlertDialog(
            onDismissRequest = { showClearConfirm = false },
            containerColor = colors.surface,
            title = {
                Text(
                    text = "Clear buffer?",
                    color = colors.textPrimary,
                    fontFamily = SyneFamily,
                    fontWeight = FontWeight.Bold,
                )
            },
            text = {
                Text(
                    text = "This removes everything in DumpEdit. You can undo once before leaving this screen.",
                    color = colors.textMid,
                    fontFamily = DmSansFamily,
                )
            },
            confirmButton = {
                TextButton(
                    onClick = {
                        showClearConfirm = false
                        viewModel.confirmClear()
                    },
                ) {
                    Text("Clear", color = colors.accent, fontFamily = DmSansFamily)
                }
            },
            dismissButton = {
                TextButton(onClick = { showClearConfirm = false }) {
                    Text("Cancel", color = colors.textMid, fontFamily = DmSansFamily)
                }
            },
        )
    }

    if (showPromoteDialog) {
        PromoteDumpEditDialog(
            parentFolders = parentFolderOptions,
            onDismiss = { showPromoteDialog = false },
            onPromote = { parentId, name ->
                viewModel.promoteToFolder(parentId, name)
            },
        )
    }

    promoteFeedback?.let { msg ->
        AlertDialog(
            onDismissRequest = { promoteFeedback = null },
            containerColor = colors.surface,
            title = {
                Text("Promote", color = colors.textPrimary, fontFamily = SyneFamily, fontWeight = FontWeight.Bold)
            },
            text = {
                Text(msg, color = colors.textMid, fontFamily = DmSansFamily)
            },
            confirmButton = {
                TextButton(onClick = { promoteFeedback = null }) {
                    Text("OK", color = colors.accent, fontFamily = DmSansFamily)
                }
            },
        )
    }
}
