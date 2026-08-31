package com.example.optimalx.ui.editor

import android.app.Application
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Construction
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalLifecycleOwner
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import com.example.optimalx.data.model.CustomPanelAssignment
import com.example.optimalx.data.model.FileReference
import com.example.optimalx.ui.components.NoteContentCodec
import com.example.optimalx.ui.editor.NoteEditorSaveStatus
import com.example.optimalx.ui.editor.toStatusLabel
import com.example.optimalx.ui.editor.components.EditorTopBar
import com.example.optimalx.ui.editor.panels.DocxViewerPanel
import com.example.optimalx.ui.editor.panels.FilesPanel
import com.example.optimalx.ui.editor.panels.ImageViewerPanel
import com.example.optimalx.ui.editor.panels.NotePanel
import com.example.optimalx.ui.imagestudio.ImageStudioPanel
import com.example.optimalx.ui.navigation.RegisterNavigationLeaveGuard
import com.example.optimalx.ui.editor.panels.PdfViewerPanel
import com.example.optimalx.ui.eidos.EidosChatViewModel
import com.example.optimalx.ui.theme.DmMonoFamily
import com.example.optimalx.ui.theme.DmSansFamily
import com.example.optimalx.ui.theme.LocalOptimalXColors
import com.example.optimalx.ui.theme.SyneFamily
import com.example.optimalx.ui.web.WebPanel
import com.example.optimalx.ui.web.WebPanelScope
import com.example.optimalx.ui.workshop.WorkshopPreviewPanel
import com.example.optimalx.voice.VoiceController
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.launch
import java.io.File

private const val PAGE_NOTE = 0
private const val PAGE_FILES = 1
private const val PAGE_IMAGE_STUDIO = 2
private const val PAGE_WEB = 3
private const val PAGE_CUSTOM_PANELS_START = 4

private val IMAGE_FILE_EXTENSIONS = setOf("jpg", "jpeg", "png", "gif", "webp", "heic")

private fun FileReference.isImageAttachment(): Boolean {
    val type = fileType.lowercase()
    if (type == "image" || type in IMAGE_FILE_EXTENSIONS) return true
    return fileName.substringAfterLast('.', "").lowercase() in IMAGE_FILE_EXTENSIONS
}

@Composable
fun EditorScreen(
    subfolderId: Long,
    eidosViewModel: EidosChatViewModel,
    onBack: () -> Unit,
    onEidosClick: () -> Unit = { /* Phase 7 */ },
    onEidosSectionClick: (() -> Unit)? = null,
    onSettingsClick: (() -> Unit)? = null,
    onOpenDiffReview: (Long) -> Unit = {},
) {
    val viewModel: EditorViewModel = viewModel(
        key = "editor_$subfolderId",
        factory = viewModelFactory {
            initializer {
                val app = this[androidx.lifecycle.ViewModelProvider.AndroidViewModelFactory.APPLICATION_KEY]!!
                EditorViewModel(app, subfolderId)
            }
        }
    )
    val noteVoiceController: VoiceController = viewModel(
        key = "editor_note_voice_$subfolderId",
        factory = viewModelFactory {
            initializer {
                val app = this[androidx.lifecycle.ViewModelProvider.AndroidViewModelFactory.APPLICATION_KEY]!!
                VoiceController(app)
            }
        },
    )

    val colors = LocalOptimalXColors.current
    val scope = rememberCoroutineScope()

    val subfolderName by viewModel.subfolderName.collectAsState()
    val parentFolderId by viewModel.parentFolderId.collectAsState()
    val note by viewModel.note.collectAsState()
    val isViewMode by viewModel.isViewMode.collectAsState()
    val userHasEdited by viewModel.userHasEdited.collectAsState()
    val noteSaveStatus by viewModel.noteSaveStatus.collectAsState()
    val isDirtyVsHead by viewModel.isDirtyVsHead.collectAsState()
    val isAiLocked by viewModel.isAiLocked.collectAsState()
    val isAiBlind by viewModel.isAiBlind.collectAsState()
    val noteSummarySections by viewModel.noteSummarySections.collectAsState()
    val noteSummaryUpdatedAt by viewModel.noteSummaryUpdatedAt.collectAsState()
    val noteReadAloudBarVisible by viewModel.noteReadAloudBarVisible.collectAsState()
    val noteReadAloudIsPlaying by viewModel.noteReadAloudIsPlaying.collectAsState()
    val fileReferences by viewModel.fileReferences.collectAsState()
    val openFiles by viewModel.openFiles.collectAsState()
    val fileFetchBusy by viewModel.fileFetchBusy.collectAsState()
    val fileFetchError by viewModel.fileFetchError.collectAsState()
    val customPanels by viewModel.customPanelAssignments.collectAsState()
    val workshopProjects by viewModel.workshopProjects.collectAsState()
    val noteCheckpoints by viewModel.noteCheckpoints.collectAsState()
    val pendingChangeCount by viewModel.pendingChangeCount.collectAsState()
    val isNoteDirty by viewModel.isNoteDirty.collectAsState()
    RegisterNavigationLeaveGuard(hasUnsavedChanges = isNoteDirty)
    var hadPendingNoteReview by remember { mutableStateOf(false) }
    LaunchedEffect(pendingChangeCount) {
        if (pendingChangeCount > 0) {
            hadPendingNoteReview = true
        } else if (hadPendingNoteReview) {
            viewModel.reloadNoteAfterDiffAccept()
            hadPendingNoteReview = false
        }
    }
    var pendingOpenFileId by remember { mutableStateOf<Long?>(null) }
    var showAddPanelDialog by remember { mutableStateOf(false) }
    var summaryDialogMessage by remember { mutableStateOf<String?>(null) }
    var showHistorySheet by remember { mutableStateOf(false) }
    var restoreFeedbackMessage by remember { mutableStateOf<String?>(null) }
    var commitFeedbackMessage by remember { mutableStateOf<String?>(null) }

    LaunchedEffect(viewModel) {
        viewModel.summaryFeedback.collect { summaryDialogMessage = it }
    }

    LaunchedEffect(viewModel) {
        viewModel.restoreFeedback.collect { restoreFeedbackMessage = it }
    }

    LaunchedEffect(viewModel) {
        viewModel.commitFeedback.collect { commitFeedbackMessage = it }
    }

    LaunchedEffect(viewModel) {
        viewModel.fileOpenReady.collect { ref ->
            pendingOpenFileId = ref.id
        }
    }

    fileFetchError?.let { message ->
        AlertDialog(
            onDismissRequest = viewModel::clearFileFetchError,
            title = { Text("Could not load file", color = colors.textPrimary, fontFamily = SyneFamily) },
            text = {
                Text(message, color = colors.textDim, fontFamily = DmMonoFamily, fontSize = 12.sp)
            },
            confirmButton = {
                TextButton(onClick = viewModel::clearFileFetchError) {
                    Text("OK", color = colors.accent)
                }
            },
        )
    }

    val filesOffset = PAGE_CUSTOM_PANELS_START + customPanels.size
    val totalPages = filesOffset + openFiles.size
    val pagerState = rememberPagerState(pageCount = { totalPages })

    LaunchedEffect(openFiles, pendingOpenFileId, customPanels) {
        val targetId = pendingOpenFileId ?: return@LaunchedEffect
        val fileIdx = openFiles.indexOfFirst { it.id == targetId }
        if (fileIdx >= 0) {
            val targetPage = filesOffset + fileIdx
            if (targetPage < pagerState.pageCount) {
                // Jump directly — animating would compose Image Studio + Web panels en route.
                pagerState.scrollToPage(targetPage)
                pendingOpenFileId = null
            }
        }
    }

    DisposableEffect(subfolderId) {
        onDispose { eidosViewModel.clearSubfolderEditorSurfaceReport(subfolderId) }
    }

    DisposableEffect(viewModel, eidosViewModel) {
        eidosViewModel.noteFlushBeforeSend = {
            viewModel.flushNoteToDbForEidos()
        }
        onDispose {
            eidosViewModel.noteFlushBeforeSend = null
        }
    }

    LaunchedEffect(eidosViewModel) {
        var wasSending = false
        eidosViewModel.isSending.collect { sending ->
            if (wasSending && !sending) {
                viewModel.reloadNoteAfterExternalWrite()
            }
            wasSending = sending
        }
    }

    val lifecycleOwner = LocalLifecycleOwner.current
    DisposableEffect(lifecycleOwner, viewModel) {
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_RESUME) {
                viewModel.reloadNoteAfterExternalWrite()
            }
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose { lifecycleOwner.lifecycle.removeObserver(observer) }
    }

    LaunchedEffect(subfolderId, openFiles, customPanels, eidosViewModel) {
        snapshotFlow { pagerState.currentPage }
            .distinctUntilChanged()
            .collect { page ->
                when (page) {
                    PAGE_WEB -> eidosViewModel.setWebEditorScope(subfolderId)
                    PAGE_IMAGE_STUDIO -> eidosViewModel.setImageStudioScope(
                        hub = false,
                        saveSubfolderId = subfolderId,
                    )
                    else -> {
                        eidosViewModel.setSubfolderScope(subfolderId)
                        val description = when {
                            page == PAGE_NOTE -> "Note editor (rich text note for this subfolder)."
                            page == PAGE_FILES -> "Attached files list — no single file is open in the viewer."
                            page in PAGE_CUSTOM_PANELS_START until filesOffset -> {
                                val panel = customPanels.getOrNull(page - PAGE_CUSTOM_PANELS_START)
                                "Custom panel: \"${panel?.panelTitle ?: "unknown"}\"."
                            }
                            page >= filesOffset -> {
                                val file = openFiles.getOrNull(page - filesOffset)
                                if (file != null) {
                                    "Open file viewer: \"${file.fileName}\" (type: ${file.fileType}, file id: ${file.id})."
                                } else {
                                    "Open file panel (empty slot)."
                                }
                            }
                            else -> "Unknown editor page."
                        }
                        eidosViewModel.reportSubfolderEditorSurface(subfolderId, description)
                    }
                }
            }
    }

    LaunchedEffect(eidosViewModel, subfolderId, scope) {
        eidosViewModel.pendingImageStudioDraftHandoff.collect { handoff ->
            if (handoff != null && !handoff.hub && handoff.saveSubfolderId == subfolderId) {
                scope.launch { pagerState.animateScrollToPage(PAGE_IMAGE_STUDIO) }
            }
        }
    }

    val exitEditor: () -> Unit = {
        scope.launch {
            viewModel.flushNoteBeforeExit()
            onBack()
        }
    }

    // Back behavior:
    // - From any open file panel, return directly to Files in one press.
    // - From Web, return to Image.
    // - From Image, return to Files.
    // - From Files, return to Note.
    // - From Note, exit editor screen.
    BackHandler {
        when {
            pagerState.currentPage >= filesOffset ->
                scope.launch { pagerState.animateScrollToPage(PAGE_FILES) }
            pagerState.currentPage in PAGE_CUSTOM_PANELS_START until filesOffset ->
                scope.launch { pagerState.animateScrollToPage(PAGE_NOTE) }
            pagerState.currentPage == PAGE_WEB ->
                scope.launch { pagerState.animateScrollToPage(PAGE_IMAGE_STUDIO) }
            pagerState.currentPage == PAGE_IMAGE_STUDIO ->
                scope.launch { pagerState.animateScrollToPage(PAGE_FILES) }
            pagerState.currentPage == PAGE_FILES ->
                scope.launch { pagerState.animateScrollToPage(PAGE_NOTE) }
            else -> exitEditor()
        }
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(colors.background),
    ) {
        EditorTopBar(
            subfolderName = subfolderName,
            onTitleClick = exitEditor,
            saveStatusLabel = if (pagerState.currentPage == PAGE_NOTE) {
                noteSaveStatus.toStatusLabel(isDirtyVsHead = isDirtyVsHead)
            } else {
                null
            },
            showCommit = pagerState.currentPage == PAGE_NOTE && isDirtyVsHead,
            onCommitClick = if (pagerState.currentPage == PAGE_NOTE && isDirtyVsHead) {
                { viewModel.commitNoteToHead() }
            } else {
                null
            },
            onEidosClick = onEidosClick,
            onEidosSectionClick = onEidosSectionClick,
            onSettingsClick = onSettingsClick,
            pendingChangeCount = pendingChangeCount,
            onReviewClick = if (pendingChangeCount > 0) {
                { onOpenDiffReview(subfolderId) }
            } else {
                null
            },
            onAddPanelClick = {
                viewModel.loadWorkshopProjects()
                showAddPanelDialog = true
            },
            onHistoryClick = if (pagerState.currentPage == PAGE_NOTE) {
                { showHistorySheet = true }
            } else null,
        )

        HorizontalPager(
            state = pagerState,
            modifier = Modifier
                .fillMaxSize()
                .weight(1f),
        ) { page ->
            when (page) {
                PAGE_NOTE -> NotePanel(
                    initialContent = NoteContentCodec.normalizeLegacyToMarkdown(note?.content ?: ""),
                    initialContentReady = note != null,
                    noteUpdatedAt = note?.updatedAt ?: 0L,
                    isViewMode = isViewMode,
                    isAiLocked = isAiLocked,
                    isAiBlind = isAiBlind,
                    restoreContentFlow = viewModel.restoreContent,
                    onEditorSnapshot = viewModel::onNoteEditorSnapshot,
                    onNoteEditorLoaded = viewModel::markNoteEditorLoaded,
                    onNoteEditSessionStarted = viewModel::onNoteEditSessionStarted,
                    onRegisterLiveContentProvider = viewModel::setLiveNoteContentProvider,
                    onContentChanged = viewModel::onContentSave,
                    onDictationSaved = { viewModel.onContentSave(it, force = true) },
                    isNoteDirty = viewModel.isNoteDirty.collectAsState().value,
                    userHasEdited = userHasEdited,
                    onToggleViewMode = viewModel::toggleViewMode,
                    onToggleAiLock = viewModel::toggleAiLock,
                    onToggleAiBlind = viewModel::toggleAiBlind,
                    memoryBullets = noteSummarySections.memoryBullets,
                    contentDigest = noteSummarySections.contentDigest,
                    summaryUpdatedAt = noteSummaryUpdatedAt,
                    onSaveNoteSummary = viewModel::saveNoteSummary,
                    exportBaseName = subfolderName.ifBlank { "note" },
                    onFlushBeforeExport = viewModel::flushNoteBeforeExport,
                    onReadNoteAloud = viewModel::speakNoteAloud,
                    onStopNoteSpeech = viewModel::stopNoteSpeech,
                    noteReadAloudBarVisible = noteReadAloudBarVisible,
                    noteReadAloudIsPlaying = noteReadAloudIsPlaying,
                    onToggleNoteReadAloudPlayback = viewModel::toggleNoteReadAloudPlayback,
                    onNoteReadAloudRewind10 = viewModel::noteReadAloudRewind10Seconds,
                    onNoteReadAloudForward10 = viewModel::noteReadAloudForward10Seconds,
                    voiceController = noteVoiceController,
                )

                PAGE_FILES -> FilesPanel(
                    files = fileReferences,
                    onFileClick = { ref -> viewModel.requestOpenFile(ref) },
                    loading = fileFetchBusy,
                    onImport = { uri ->
                        viewModel.importFile(viewModel.getApplication<Application>(), uri)
                    },
                    onDelete = viewModel::deleteFileReference,
                )

                PAGE_IMAGE_STUDIO -> ImageStudioPanel(
                    saveSubfolderId = subfolderId,
                    onOpenFiles = {
                        scope.launch { pagerState.animateScrollToPage(PAGE_FILES) }
                    },
                    onSettingsClick = onSettingsClick,
                    eidosViewModel = eidosViewModel,
                )

                PAGE_WEB -> if (pagerState.currentPage == PAGE_WEB) {
                    key("web_panel_${WebPanelScope.editor(subfolderId)}") {
                        WebPanel(
                            panelTitle = "Subfolder Web panel",
                            eidosViewModel = eidosViewModel,
                            scopeKey = WebPanelScope.editor(subfolderId),
                        )
                    }
                } else {
                    Box(
                        modifier = Modifier
                            .fillMaxSize()
                            .background(colors.background),
                    )
                }

                in PAGE_CUSTOM_PANELS_START until filesOffset -> {
                    val assignment = customPanels.getOrNull(page - PAGE_CUSTOM_PANELS_START)
                    if (assignment != null) {
                        val html = viewModel.getCustomPanelHtml(assignment)
                        WorkshopPreviewPanel(
                            html = html,
                            htmlFilePath = null,
                            workshopSubfolderId = assignment.workshopSubfolderId,
                            panelContextType = "custom_panel",
                            panelStateScopeKey = com.example.optimalx.data.eidos.PanelStateScope.forHostSubfolder(subfolderId),
                            isVisibleAndFocused = pagerState.currentPage == page,
                        )
                    }
                }

                else -> {
                    val file = openFiles.getOrNull(page - filesOffset)
                    if (file != null) {
                        OpenFilePanel(
                            file = file,
                            initialPdfRotation = viewModel.getPdfRotation(file.id),
                            initialImageRotation = viewModel.getImageRotation(file.id),
                            onPdfRotationCommit = { rotation ->
                                viewModel.savePdfRotation(file.id, rotation)
                            },
                            onImageRotationCommit = { rotation ->
                                viewModel.saveImageRotation(file.id, rotation)
                            },
                            onClose = {
                                viewModel.closeFile(file)
                                scope.launch { pagerState.animateScrollToPage(PAGE_FILES) }
                            },
                        )
                    }
                }
            }
        }
    }

    if (showAddPanelDialog) {
        AddPanelDialog(
            projects = workshopProjects,
            existingAssignments = customPanels,
            onDismiss = { showAddPanelDialog = false },
            onSelect = { project ->
                viewModel.addCustomPanel(project.id, project.name)
                showAddPanelDialog = false
            },
        )
    }

    summaryDialogMessage?.let { msg ->
        AlertDialog(
            onDismissRequest = { summaryDialogMessage = null },
            containerColor = colors.surface,
            title = { Text("Note summary", color = colors.textPrimary, fontFamily = DmSansFamily) },
            text = { Text(msg, color = colors.textMid, fontFamily = DmSansFamily) },
            confirmButton = {
                TextButton(onClick = { summaryDialogMessage = null }) {
                    Text("OK", color = colors.accent, fontFamily = DmSansFamily)
                }
            },
        )
    }

    if (showHistorySheet) {
        com.example.optimalx.ui.workshop.components.ContentHistorySheet(
            sourceLabel = subfolderName.ifBlank { "Note" },
            checkpoints = noteCheckpoints,
                    workingCopy = NoteContentCodec.normalizeLegacyToMarkdown(note?.content ?: ""),
            onRestore = { checkpointId ->
                viewModel.restoreNoteCheckpoint(checkpointId)
                showHistorySheet = false
            },
            onDismiss = { showHistorySheet = false },
        )
    }

    restoreFeedbackMessage?.let { msg ->
        AlertDialog(
            onDismissRequest = { restoreFeedbackMessage = null },
            containerColor = colors.surface,
            title = { Text("History", color = colors.textPrimary, fontFamily = DmSansFamily) },
            text = { Text(msg, color = colors.textMid, fontFamily = DmSansFamily) },
            confirmButton = {
                TextButton(onClick = { restoreFeedbackMessage = null }) {
                    Text("OK", color = colors.accent, fontFamily = DmSansFamily)
                }
            },
        )
    }

    commitFeedbackMessage?.let { msg ->
        AlertDialog(
            onDismissRequest = { commitFeedbackMessage = null },
            containerColor = colors.surface,
            title = { Text("Commit", color = colors.textPrimary, fontFamily = DmSansFamily) },
            text = { Text(msg, color = colors.textMid, fontFamily = DmSansFamily) },
            confirmButton = {
                TextButton(onClick = { commitFeedbackMessage = null }) {
                    Text("OK", color = colors.accent, fontFamily = DmSansFamily)
                }
            },
        )
    }
}

@Composable
private fun AddPanelDialog(
    projects: List<com.example.optimalx.data.model.Subfolder>,
    existingAssignments: List<CustomPanelAssignment>,
    onDismiss: () -> Unit,
    onSelect: (com.example.optimalx.data.model.Subfolder) -> Unit,
) {
    val colors = LocalOptimalXColors.current
    val assignedIds = existingAssignments.map { it.workshopSubfolderId }.toSet()
    val available = projects.filter { it.id !in assignedIds }

    AlertDialog(
        onDismissRequest = onDismiss,
        containerColor = colors.surface,
        title = {
            Text(
                text = "Add Custom Panel",
                color = colors.textPrimary,
                fontFamily = com.example.optimalx.ui.theme.SyneFamily,
                fontWeight = androidx.compose.ui.text.font.FontWeight.Bold,
            )
        },
        text = {
            if (available.isEmpty()) {
                Text(
                    text = if (projects.isEmpty()) {
                        "No published panels yet. Finish a panel in Panel Workshop (Accept logic) first."
                    } else {
                        "All published panels are already added."
                    },
                    color = colors.textMid,
                    fontFamily = DmSansFamily,
                    fontSize = 14.sp,
                )
            } else {
                LazyColumn {
                    items(available, key = { it.id }) { project ->
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .clickable { onSelect(project) }
                                .padding(vertical = 12.dp, horizontal = 4.dp),
                            verticalAlignment = androidx.compose.ui.Alignment.CenterVertically,
                        ) {
                            Icon(
                                Icons.Default.Construction,
                                contentDescription = null,
                                tint = colors.accent,
                            )
                            Spacer(Modifier.width(12.dp))
                            Text(
                                text = project.name,
                                color = colors.textPrimary,
                                fontFamily = DmSansFamily,
                                fontSize = 15.sp,
                            )
                        }
                    }
                }
            }
        },
        confirmButton = {},
        dismissButton = {
            TextButton(onClick = onDismiss) {
                Text("Cancel", color = colors.textMid)
            }
        },
    )
}

@Composable
private fun OpenFilePanel(
    file: FileReference,
    initialPdfRotation: Float,
    initialImageRotation: Float,
    onPdfRotationCommit: (Float) -> Unit,
    onImageRotationCommit: (Float) -> Unit,
    onClose: () -> Unit,
) {
    val docExtensions = setOf("docx", "odt", "doc")
    val ext = file.fileType.lowercase()

    when {
        ext == "pdf" -> PdfViewerPanel(
            fileId = file.id,
            filePath = file.filePath,
            initialRotation = initialPdfRotation,
            onRotationCommit = onPdfRotationCommit,
        )
        file.isImageAttachment() -> ImageViewerPanel(
            filePath = file.filePath,
            initialRotation = initialImageRotation,
            onRotationCommit = onImageRotationCommit,
        )
        ext in docExtensions -> DocxViewerPanel(filePath = file.filePath)
        else -> TextFilePanel(filePath = file.filePath)
    }
}

@Composable
private fun TextFilePanel(filePath: String) {
    val colors = LocalOptimalXColors.current
    val content = remember(filePath) {
        runCatching { File(filePath).readText() }.getOrDefault("")
    }
    val scrollState = rememberScrollState()

    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(colors.surface),
    ) {
        Text(
            text = content,
            color = colors.textPrimary,
            fontFamily = DmSansFamily,
            fontSize = 15.sp,
            modifier = Modifier
                .fillMaxSize()
                .verticalScroll(scrollState)
                .navigationBarsPadding()
                .padding(16.dp),
        )
    }
}
