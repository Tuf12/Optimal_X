package com.example.optimalx.ui.workshop

import android.app.Application
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.relocation.BringIntoViewRequester
import androidx.compose.foundation.relocation.bringIntoViewRequester
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.ui.draw.clip
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.InsertDriveFile
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Code
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Description
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.History
import androidx.compose.material.icons.filled.Menu
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Visibility
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.DrawerValue
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.ModalDrawerSheet
import androidx.compose.material3.ModalNavigationDrawer
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TextField
import androidx.compose.material3.TextFieldDefaults
import androidx.compose.material3.rememberDrawerState
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
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.onFocusEvent
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.TextFieldValue
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.platform.LocalLifecycleOwner
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import com.example.optimalx.data.eidos.WorkshopProjectPhase
import com.example.optimalx.data.model.FileReference
import com.example.optimalx.ui.components.MarkdownRichText
import com.example.optimalx.ui.eidos.EidosChatViewModel
import com.example.optimalx.ui.navigation.RegisterNavigationLeaveGuard
import com.example.optimalx.ui.theme.DmMonoFamily
import com.example.optimalx.ui.theme.DmSansFamily
import com.example.optimalx.ui.theme.LocalOptimalXColors
import com.example.optimalx.ui.theme.SyneFamily
import kotlinx.coroutines.launch

@Composable
fun WorkshopEditorScreen(
    subfolderId: Long,
    eidosViewModel: EidosChatViewModel,
    onBack: () -> Unit,
    onEidosClick: () -> Unit,
    onOpenEidosSheet: () -> Unit,
    onOpenDiffReview: (subfolderId: Long) -> Unit = {},
) {
    val viewModel: WorkshopEditorViewModel = viewModel(
        key = "workshop_$subfolderId",
        factory = viewModelFactory {
            initializer {
                val app = this[androidx.lifecycle.ViewModelProvider.AndroidViewModelFactory.APPLICATION_KEY]!!
                WorkshopEditorViewModel(app as Application, subfolderId)
            }
        }
    )

    val colors = LocalOptimalXColors.current
    val scope = rememberCoroutineScope()
    val drawerState = rememberDrawerState(DrawerValue.Closed)

    val files by viewModel.files.collectAsState()
    val currentFileId by viewModel.currentFileId.collectAsState()
    val fileContent by viewModel.fileContent.collectAsState()
    val isPreviewMode by viewModel.isPreviewMode.collectAsState()
    val isMarkdownEditMode by viewModel.isMarkdownEditMode.collectAsState()
    val subfolderName by viewModel.subfolderName.collectAsState()
    val projectPhase by viewModel.projectPhase.collectAsState()
    val canAcceptSpecs by viewModel.canAcceptSpecs.collectAsState()
    val canAcceptDesign by viewModel.canAcceptDesign.collectAsState()
    val canAcceptLogic by viewModel.canAcceptLogic.collectAsState()
    val hasProjectSummary by viewModel.hasProjectSummary.collectAsState()
    val summaryGenerating by viewModel.summaryGenerating.collectAsState()
    val diskRevision by viewModel.diskRevision.collectAsState()
    val eidosSending by eidosViewModel.isSending.collectAsState()
    val pendingChangeCount by viewModel.pendingChangeCount.collectAsState()
    val workshopBackupBusy by viewModel.workshopBackupBusy.collectAsState()
    val workshopRestoreInProgress by viewModel.workshopRestoreInProgress.collectAsState()
    val workshopBackupProgress by viewModel.workshopBackupProgress.collectAsState()
    val workshopBackupMessage by viewModel.workshopBackupMessage.collectAsState()
    val workshopRestoreMessage by viewModel.workshopRestoreMessage.collectAsState()
    val needsDesktopFileRestore by viewModel.needsDesktopFileRestore.collectAsState()
    val acceptUpdateFinishPending by viewModel.acceptUpdateFinishPending.collectAsState()
    val checkpointsForCurrentFile by viewModel.checkpointsForCurrentFile.collectAsState()
    val persistenceFinishGate by viewModel.persistenceFinishGate.collectAsState()
    val isWorkshopDirty by viewModel.isDirty.collectAsState()
    RegisterNavigationLeaveGuard(hasUnsavedChanges = isWorkshopDirty)

    var summaryDialogMessage by remember { mutableStateOf<String?>(null) }
    var showHistorySheet by remember { mutableStateOf(false) }
    var restoreFeedbackMessage by remember { mutableStateOf<String?>(null) }
    var showRestoreFromPcConfirm by remember { mutableStateOf(false) }

    workshopBackupMessage?.let { message ->
        AlertDialog(
            onDismissRequest = viewModel::clearWorkshopBackupMessage,
            title = {
                Text(
                    text = "Workshop file sync",
                    color = colors.textPrimary,
                    fontFamily = SyneFamily,
                    fontWeight = FontWeight.Bold,
                )
            },
            text = {
                Text(message, color = colors.textDim, fontFamily = DmMonoFamily, fontSize = 12.sp)
            },
            confirmButton = {
                TextButton(onClick = viewModel::clearWorkshopBackupMessage) {
                    Text("OK", color = colors.accent, fontFamily = DmSansFamily)
                }
            },
        )
    }

    workshopRestoreMessage?.let { message ->
        AlertDialog(
            onDismissRequest = viewModel::clearWorkshopRestoreMessage,
            title = {
                Text(
                    text = "Sync from PC",
                    color = colors.textPrimary,
                    fontFamily = SyneFamily,
                    fontWeight = FontWeight.Bold,
                )
            },
            text = {
                Text(message, color = colors.textDim, fontFamily = DmMonoFamily, fontSize = 12.sp)
            },
            confirmButton = {
                TextButton(onClick = viewModel::clearWorkshopRestoreMessage) {
                    Text("OK", color = colors.accent, fontFamily = DmSansFamily)
                }
            },
        )
    }

    if (showRestoreFromPcConfirm) {
        AlertDialog(
            onDismissRequest = { showRestoreFromPcConfirm = false },
            title = {
                Text(
                    text = "Sync workshop files from PC?",
                    color = colors.textPrimary,
                    fontFamily = SyneFamily,
                    fontWeight = FontWeight.Bold,
                )
            },
            text = {
                Text(
                    text = "This overwrites workshop files on this device with the copy on the desktop PC " +
                        "(backups/mobile-workshop/). Unsaved local edits will be lost.",
                    color = colors.textDim,
                    fontFamily = DmMonoFamily,
                    fontSize = 12.sp,
                )
            },
            confirmButton = {
                TextButton(
                    onClick = {
                        showRestoreFromPcConfirm = false
                        viewModel.restoreWorkshopFromPc()
                    },
                ) {
                    Text("Sync", color = colors.accent, fontFamily = DmSansFamily)
                }
            },
            dismissButton = {
                TextButton(onClick = { showRestoreFromPcConfirm = false }) {
                    Text("Cancel", color = colors.textDim, fontFamily = DmSansFamily)
                }
            },
        )
    }

    LaunchedEffect(viewModel) {
        viewModel.summaryFeedback.collect { summaryDialogMessage = it }
    }

    LaunchedEffect(viewModel) {
        viewModel.restoreFeedback.collect { restoreFeedbackMessage = it }
    }

    LaunchedEffect(subfolderId, eidosViewModel) {
        eidosViewModel.setWorkshopScope(subfolderId)
    }

    LaunchedEffect(subfolderId, viewModel) {
        com.example.optimalx.ui.navigation.WorkshopNavigationState.pendingSelectPath?.let { path ->
            viewModel.openFileByRelativePath(path)
            com.example.optimalx.ui.navigation.WorkshopNavigationState.pendingSelectPath = null
        }
    }

    DisposableEffect(viewModel, eidosViewModel) {
        eidosViewModel.workshopFlushOpenFileBeforeSend = {
            viewModel.flushOpenFileToDiskForEidos()
            val ref = viewModel.getCurrentFileRef()
            eidosViewModel.reportWorkshopEditorFileState(
                ref?.fileName,
                if (ref != null) viewModel.fileContent.value else null,
            )
        }
        onDispose {
            eidosViewModel.workshopFlushOpenFileBeforeSend = null
        }
    }

    LaunchedEffect(viewModel, eidosViewModel, onOpenEidosSheet) {
        viewModel.bindUpdateCompletion(eidosViewModel, onOpenEidosSheet)
    }

    LaunchedEffect(projectPhase, eidosViewModel) {
        eidosViewModel.refreshWorkshopProjectPhase()
    }

    LaunchedEffect(viewModel) {
        viewModel.refreshProjectPhase()
    }

    val lifecycleOwner = LocalLifecycleOwner.current
    DisposableEffect(lifecycleOwner, viewModel, eidosViewModel) {
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_RESUME) {
                viewModel.refreshProjectPhase()
                eidosViewModel.refreshWorkshopProjectPhase()
            }
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose { lifecycleOwner.lifecycle.removeObserver(observer) }
    }

    // Eidos writes go to disk directly; reload editor/preview when a send finishes so stale buffers
    // do not overwrite tool results on the next save or file switch.
    LaunchedEffect(eidosViewModel) {
        var wasSending = false
        eidosViewModel.isSending.collect { sending ->
            if (wasSending && !sending) {
                viewModel.reloadFromDiskAfterExternalWrite()
                viewModel.onEidosSendCompleted(eidosViewModel)
                eidosViewModel.refreshWorkshopProjectPhase()
            }
            wasSending = sending
        }
    }

    DisposableEffect(subfolderId, eidosViewModel) {
        onDispose {
            eidosViewModel.clearSubfolderEditorSurfaceReport(subfolderId)
            eidosViewModel.reportWorkshopEditorFileState(null, null)
        }
    }

    LaunchedEffect(
        subfolderId,
        currentFileId,
        fileContent,
        isPreviewMode,
        isMarkdownEditMode,
        files,
        eidosViewModel,
    ) {
        val ref = files.firstOrNull { it.id == currentFileId }
        if (isPreviewMode) {
            eidosViewModel.reportSubfolderEditorSurface(subfolderId, "Workshop editor: panel preview mode")
            eidosViewModel.reportWorkshopEditorFileState(null, null)
        } else if (ref != null) {
            val isMd = ref.fileType.equals("md", ignoreCase = true)
            val surface = if (isMd && !isMarkdownEditMode) {
                "Workshop editor: viewing ${ref.fileName} (markdown view mode)"
            } else {
                "Workshop editor: editing ${ref.fileName} (code editor)"
            }
            eidosViewModel.reportSubfolderEditorSurface(subfolderId, surface)
            eidosViewModel.reportWorkshopEditorFileState(ref.fileName, fileContent)
        } else {
            eidosViewModel.reportSubfolderEditorSurface(subfolderId, "Workshop editor: no file open")
            eidosViewModel.reportWorkshopEditorFileState(null, null)
        }
    }

    val currentRef = viewModel.getCurrentFileRef()
    val isMarkdownFile = currentRef?.fileType?.equals("md", ignoreCase = true) == true
    val isCodeFile = currentRef?.fileType?.let {
        it.equals("html", ignoreCase = true) || it.equals("js", ignoreCase = true) || it.equals("css", ignoreCase = true)
    } == true

    var showNewFileDialog by remember { mutableStateOf(false) }

    BackHandler {
        when {
            drawerState.isOpen -> scope.launch { drawerState.close() }
            isPreviewMode -> viewModel.togglePreview()
            else -> onBack()
        }
    }

    ModalNavigationDrawer(
        drawerState = drawerState,
        drawerContent = {
            WorkshopDrawerContent(
                files = files,
                currentFileId = currentFileId,
                hasProjectSummary = hasProjectSummary,
                summaryGenerating = summaryGenerating,
                workshopBackupBusy = workshopBackupBusy,
                workshopRestoreInProgress = workshopRestoreInProgress,
                workshopBackupProgress = workshopBackupProgress,
                onFileClick = { fileId ->
                    viewModel.saveCurrentFile()
                    viewModel.openFile(fileId)
                    scope.launch { drawerState.close() }
                },
                onNewFileClick = { showNewFileDialog = true },
                onDeleteFile = { fileId -> viewModel.deleteFile(fileId) },
                onGenerateSummary = { viewModel.generateOrRegenerateProjectSummary() },
                onBackupWorkshopToPc = { viewModel.backupWorkshopToPc() },
                onRestoreWorkshopFromPc = { showRestoreFromPcConfirm = true },
            )
        },
    ) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .background(colors.background),
        ) {
            // Top bar
            WorkshopTopBar(
                projectName = subfolderName,
                projectPhase = projectPhase,
                canAcceptSpecs = canAcceptSpecs,
                canAcceptDesign = canAcceptDesign,
                canAcceptLogic = canAcceptLogic,
                onMenuClick = { scope.launch { drawerState.open() } },
                onBackClick = onBack,
                onPrimaryAgentClick = {
                    when (projectPhase) {
                        WorkshopProjectPhase.COMPLETE -> viewModel.enterUpdateEditMode(eidosViewModel)
                        else -> scope.launch {
                            viewModel.applyPrimaryBuildAction(onOpenEidosSheet, eidosViewModel)
                        }
                    }
                },
                onEidosClick = onEidosClick,
                isPreviewMode = isPreviewMode,
                isMarkdownFile = isMarkdownFile,
                isMarkdownEditMode = isMarkdownEditMode,
                onTogglePreview = { viewModel.togglePreview() },
                onToggleMarkdownEdit = { viewModel.toggleMarkdownEditMode() },
                emphasizePreview = projectPhase == WorkshopProjectPhase.DESIGN_REVIEW ||
                    projectPhase == WorkshopProjectPhase.LOGIC_REVIEW ||
                    projectPhase == WorkshopProjectPhase.UPDATE,
                pendingChangeCount = pendingChangeCount,
                onReviewClick = { onOpenDiffReview(subfolderId) },
                canShowHistory = currentFileId != null && !isPreviewMode,
                onHistoryClick = { showHistorySheet = true },
                currentFileName = currentRef?.fileName,
            )

            if (needsDesktopFileRestore) {
                RestoreFromDesktopBanner(
                    onRestore = { viewModel.restoreWorkshopFromPc() },
                    busy = workshopBackupBusy,
                )
            }

            if (projectPhase == WorkshopProjectPhase.UPDATE && acceptUpdateFinishPending) {
                AcceptUpdateFinishBanner(
                    onCancel = { viewModel.cancelPendingAcceptUpdate() },
                    onSkipDocSync = { viewModel.finishUpdateSkippingDocSync(eidosViewModel) },
                )
            }

            // Editor area
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .weight(1f),
            ) {
                when {
                    isPreviewMode -> {
                        key(diskRevision) {
                            WorkshopPreviewPanel(
                                html = viewModel.getCompositeHtml(),
                                htmlFilePath = null,
                                workshopSubfolderId = subfolderId,
                                panelContextType = "workshop_preview",
                                isVisibleAndFocused = true,
                                onConsoleError = { viewModel.addConsoleError(it) },
                            )
                        }
                    }
                    projectPhase == WorkshopProjectPhase.INTAKE && currentRef == null -> {
                        IntakePlaceholder(onOpenEidos = onEidosClick)
                    }
                    projectPhase == WorkshopProjectPhase.SPEC_REVIEW && currentRef == null -> {
                        SpecReviewPlaceholder(onOpenDocs = { scope.launch { drawerState.open() } })
                    }
                    projectPhase == WorkshopProjectPhase.DESIGN_REVIEW && currentRef == null && !isPreviewMode -> {
                        DesignReviewPlaceholder(onOpenPreview = { viewModel.openPreview() })
                    }
                    projectPhase == WorkshopProjectPhase.LOGIC_REVIEW && currentRef == null && !isPreviewMode -> {
                        LogicReviewPlaceholder(onOpenPreview = { viewModel.openPreview() })
                    }
                    projectPhase == WorkshopProjectPhase.UPDATE && currentRef == null && !isPreviewMode -> {
                        UpdateEditPlaceholder(
                            onOpenEidos = onEidosClick,
                            onOpenPreview = { viewModel.openPreview() },
                        )
                    }
                    currentRef == null -> {
                        EmptyEditorPlaceholder()
                    }
                    isMarkdownFile && !isMarkdownEditMode -> {
                        MarkdownViewPanel(
                            content = fileContent,
                            modifier = Modifier.fillMaxSize(),
                        )
                    }
                    else -> {
                        CodeEditorPanel(
                            content = fileContent,
                            onContentChange = { viewModel.onContentChange(it) },
                            onSave = { viewModel.saveCurrentFile() },
                            isMonospace = isCodeFile,
                            modifier = Modifier.fillMaxSize(),
                        )
                    }
                }
            }
        }
    }

    persistenceFinishGate?.let { gate ->
        PersistenceFinishGateDialog(
            warnings = gate.warnings,
            onDismiss = { viewModel.dismissPersistenceFinishGate() },
            onFinishAnyway = {
                scope.launch {
                    viewModel.confirmAcceptLogicDespitePersistenceWarnings(
                        onOpenEidosSheet,
                        eidosViewModel,
                    )
                }
            },
        )
    }

    if (showNewFileDialog) {
        NewFileDialog(
            onDismiss = { showNewFileDialog = false },
            onCreate = { name ->
                viewModel.createFile(name)
                showNewFileDialog = false
            },
        )
    }

    summaryDialogMessage?.let { msg ->
        AlertDialog(
            onDismissRequest = { summaryDialogMessage = null },
            containerColor = colors.surface,
            title = { Text("Project summary", color = colors.textPrimary, fontFamily = SyneFamily) },
            text = { Text(msg, color = colors.textMid, fontFamily = DmSansFamily) },
            confirmButton = {
                TextButton(onClick = { summaryDialogMessage = null }) {
                    Text("OK", color = colors.accent, fontFamily = DmSansFamily)
                }
            },
        )
    }

    if (showHistorySheet) {
        val currentRefForHistory = files.firstOrNull { it.id == currentFileId }
        com.example.optimalx.ui.workshop.components.ContentHistorySheet(
            sourceLabel = currentRefForHistory?.fileName ?: "(unknown)",
            checkpoints = checkpointsForCurrentFile,
            workingCopy = fileContent,
            onRestore = { checkpointId ->
                viewModel.restoreCheckpoint(checkpointId)
                showHistorySheet = false
            },
            onDismiss = { showHistorySheet = false },
        )
    }

    restoreFeedbackMessage?.let { msg ->
        AlertDialog(
            onDismissRequest = { restoreFeedbackMessage = null },
            containerColor = colors.surface,
            title = { Text("History", color = colors.textPrimary, fontFamily = SyneFamily) },
            text = { Text(msg, color = colors.textMid, fontFamily = DmSansFamily) },
            confirmButton = {
                TextButton(onClick = { restoreFeedbackMessage = null }) {
                    Text("OK", color = colors.accent, fontFamily = DmSansFamily)
                }
            },
        )
    }
}

@Composable
private fun RestoreFromDesktopBanner(
    onRestore: () -> Unit,
    busy: Boolean,
) {
    val colors = LocalOptimalXColors.current
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .background(colors.accentDim)
            .padding(horizontal = 12.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.SpaceBetween,
    ) {
        Text(
            text = "Workshop file contents live on the desktop until you sync them. Pull only updates metadata — " +
                "use Sync from PC to load script.js, README, and other bytes.",
            color = colors.textPrimary,
            fontFamily = DmSansFamily,
            fontSize = 11.sp,
            modifier = Modifier.weight(1f),
        )
        TextButton(onClick = onRestore, enabled = !busy) {
            Text(
                text = if (busy) "Syncing…" else "Sync from PC",
                color = colors.accent,
                fontFamily = DmSansFamily,
                fontSize = 12.sp,
            )
        }
    }
}

@Composable
private fun AcceptUpdateFinishBanner(
    onCancel: () -> Unit,
    onSkipDocSync: () -> Unit,
) {
    val colors = LocalOptimalXColors.current
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .background(colors.accentDim)
            .padding(horizontal = 12.dp, vertical = 8.dp),
    ) {
        Text(
            text = "Finish update armed — tap Accept update again to sync specs, cancel to keep editing, " +
                "or skip sync if Eidos is stuck.",
            color = colors.textPrimary,
            fontFamily = DmSansFamily,
            fontSize = 12.sp,
        )
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.End,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            TextButton(onClick = onCancel) {
                Text(
                    text = "Cancel finish",
                    color = colors.textMid,
                    fontFamily = DmSansFamily,
                    fontWeight = FontWeight.SemiBold,
                    fontSize = 12.sp,
                )
            }
            TextButton(onClick = onSkipDocSync) {
                Text(
                    text = "Skip spec sync",
                    color = colors.accent,
                    fontFamily = DmSansFamily,
                    fontWeight = FontWeight.SemiBold,
                    fontSize = 12.sp,
                )
            }
        }
    }
}

@Composable
private fun WorkshopTopBar(
    projectName: String,
    projectPhase: WorkshopProjectPhase,
    canAcceptSpecs: Boolean,
    canAcceptDesign: Boolean,
    canAcceptLogic: Boolean,
    onMenuClick: () -> Unit,
    onBackClick: () -> Unit,
    onPrimaryAgentClick: () -> Unit,
    onEidosClick: () -> Unit,
    isPreviewMode: Boolean,
    isMarkdownFile: Boolean,
    isMarkdownEditMode: Boolean,
    onTogglePreview: () -> Unit,
    onToggleMarkdownEdit: () -> Unit,
    emphasizePreview: Boolean = false,
    pendingChangeCount: Int = 0,
    onReviewClick: () -> Unit = {},
    canShowHistory: Boolean = false,
    onHistoryClick: () -> Unit = {},
    currentFileName: String? = null,
) {
    val colors = LocalOptimalXColors.current

    val primaryAction = when (projectPhase) {
        WorkshopProjectPhase.INTAKE -> "Generate specs" to true
        WorkshopProjectPhase.SPEC_REVIEW -> "Accept specs" to canAcceptSpecs
        WorkshopProjectPhase.DESIGN_BUILD ->
            if (canAcceptDesign) "Accept design" to true else "Build design" to true
        WorkshopProjectPhase.DESIGN_REVIEW -> "Accept design" to true
        WorkshopProjectPhase.LOGIC_BUILD ->
            if (canAcceptLogic) "Accept logic" to true else "Build logic" to true
        WorkshopProjectPhase.LOGIC_REVIEW -> "Accept logic" to true
        WorkshopProjectPhase.COMPLETE -> "Update" to true
        WorkshopProjectPhase.UPDATE -> "Accept update" to true
        else -> null
    }

    Column(
        modifier = Modifier
            .fillMaxWidth()
            .statusBarsPadding(),
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 8.dp, vertical = 6.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            IconButton(onClick = onMenuClick) {
                Icon(Icons.Default.Menu, contentDescription = "Files", tint = colors.textPrimary)
            }
            Text(
                text = projectName,
                color = colors.textPrimary,
                fontFamily = SyneFamily,
                fontWeight = FontWeight.ExtraBold,
                fontSize = 16.sp,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier
                    .weight(1f)
                    .clickable(onClick = onBackClick),
            )

            IconButton(onClick = onTogglePreview) {
                Icon(
                    imageVector = if (isPreviewMode) Icons.Default.Close else Icons.Default.PlayArrow,
                    contentDescription = if (isPreviewMode) "Close preview" else "Preview",
                    tint = when {
                        isPreviewMode -> colors.accent
                        emphasizePreview -> colors.accent
                        else -> colors.textMid
                    },
                    modifier = if (emphasizePreview && !isPreviewMode) {
                        Modifier.border(1.dp, colors.accent, CircleShape)
                    } else {
                        Modifier
                    },
                )
            }

            if (pendingChangeCount > 0) {
                Row(
                    modifier = Modifier
                        .clip(RoundedCornerShape(6.dp))
                        .background(colors.accentDim)
                        .border(1.dp, colors.accentBorder, RoundedCornerShape(6.dp))
                        .clickable(onClick = onReviewClick)
                        .padding(horizontal = 8.dp, vertical = 4.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text(
                        text = "Review $pendingChangeCount",
                        color = colors.accent,
                        fontFamily = DmSansFamily,
                        fontWeight = FontWeight.Medium,
                        fontSize = 12.sp,
                    )
                }
            }

            TextButton(onClick = onEidosClick) {
                Text(
                    text = "Eidos",
                    color = colors.accent,
                    fontFamily = DmSansFamily,
                    fontWeight = FontWeight.Medium,
                    fontSize = 15.sp,
                )
            }
        }

        if (!isPreviewMode && currentFileName != null) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 16.dp, vertical = 2.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(
                    text = currentFileName,
                    color = colors.textMid,
                    fontFamily = DmSansFamily,
                    fontSize = 13.sp,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.weight(1f),
                )
                Text(
                    text = projectPhase.phaseLabel(),
                    color = colors.textDim,
                    fontFamily = DmSansFamily,
                    fontSize = 11.sp,
                    maxLines = 1,
                    modifier = Modifier.padding(horizontal = 8.dp),
                )
                if (canShowHistory) {
                    IconButton(onClick = onHistoryClick) {
                        Icon(
                            imageVector = Icons.Default.History,
                            contentDescription = "History",
                            tint = colors.textMid,
                        )
                    }
                }
                if (isMarkdownFile) {
                    IconButton(onClick = onToggleMarkdownEdit) {
                        Icon(
                            imageVector = if (isMarkdownEditMode) Icons.Default.Visibility else Icons.Default.Edit,
                            contentDescription = if (isMarkdownEditMode) "View" else "Edit",
                            tint = colors.accent,
                        )
                    }
                }

                val primaryEnabled = primaryAction?.second == true
                val primaryLabel = primaryAction?.first
                if (primaryLabel != null) {
                    TextButton(
                        onClick = onPrimaryAgentClick,
                        enabled = primaryEnabled,
                    ) {
                        Text(
                            text = primaryLabel,
                            color = if (primaryEnabled) colors.accent else colors.textDim,
                            fontFamily = DmSansFamily,
                            fontWeight = FontWeight.Medium,
                            fontSize = 13.sp,
                        )
                    }
                }
            }
        } else {
            val primaryEnabled = primaryAction?.second == true
            val primaryLabel = primaryAction?.first
            val showSecondary = isPreviewMode || primaryLabel != null
            if (showSecondary) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 16.dp, vertical = 2.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text(
                        text = projectPhase.phaseLabel(),
                        color = colors.textDim,
                        fontFamily = DmSansFamily,
                        fontSize = 11.sp,
                        maxLines = 1,
                        modifier = Modifier.weight(1f),
                    )
                    if (primaryLabel != null) {
                        TextButton(
                            onClick = onPrimaryAgentClick,
                            enabled = primaryEnabled,
                        ) {
                            Text(
                                text = primaryLabel,
                                color = if (primaryEnabled) colors.accent else colors.textDim,
                                fontFamily = DmSansFamily,
                                fontWeight = FontWeight.Medium,
                                fontSize = 13.sp,
                            )
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun WorkshopDrawerContent(
    files: List<FileReference>,
    currentFileId: Long?,
    hasProjectSummary: Boolean,
    summaryGenerating: Boolean,
    workshopBackupBusy: Boolean,
    workshopRestoreInProgress: Boolean,
    workshopBackupProgress: com.example.optimalx.data.sync.WorkshopBackupProgress?,
    onFileClick: (Long) -> Unit,
    onNewFileClick: () -> Unit,
    onDeleteFile: (Long) -> Unit,
    onGenerateSummary: () -> Unit,
    onBackupWorkshopToPc: () -> Unit,
    onRestoreWorkshopFromPc: () -> Unit,
) {
    val colors = LocalOptimalXColors.current

    val mdFiles = files.filter { it.fileType.equals("md", ignoreCase = true) }
    val codeFiles = files.filter {
        !it.fileType.equals("md", ignoreCase = true)
    }

    ModalDrawerSheet(
        drawerContainerColor = colors.surface,
        modifier = Modifier
            .width(280.dp)
            .fillMaxHeight(),
    ) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(vertical = 16.dp),
        ) {
            Text(
                text = "Project Files",
                color = colors.textPrimary,
                fontFamily = SyneFamily,
                fontWeight = FontWeight.Bold,
                fontSize = 16.sp,
                modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp),
            )

            HorizontalDivider(color = colors.border)

            LazyColumn(
                modifier = Modifier.weight(1f),
            ) {
                if (mdFiles.isNotEmpty()) {
                    item {
                        DrawerSectionHeader(
                            title = "Docs",
                            icon = Icons.Default.Description,
                        )
                    }
                    items(mdFiles, key = { it.id }) { ref ->
                        DrawerFileItem(
                            ref = ref,
                            isSelected = ref.id == currentFileId,
                            onClick = { onFileClick(ref.id) },
                            onDelete = { onDeleteFile(ref.id) },
                        )
                    }
                }

                if (codeFiles.isNotEmpty()) {
                    item {
                        DrawerSectionHeader(
                            title = "Code",
                            icon = Icons.Default.Code,
                        )
                    }
                    items(codeFiles, key = { it.id }) { ref ->
                        DrawerFileItem(
                            ref = ref,
                            isSelected = ref.id == currentFileId,
                            onClick = { onFileClick(ref.id) },
                            onDelete = { onDeleteFile(ref.id) },
                        )
                    }
                }
            }

            HorizontalDivider(color = colors.border)

            TextButton(
                onClick = onBackupWorkshopToPc,
                enabled = !workshopBackupBusy,
                modifier = Modifier.padding(horizontal = 8.dp),
            ) {
                Text(
                    text = when {
                        workshopBackupBusy && !workshopRestoreInProgress && workshopBackupProgress != null -> {
                            val progress = workshopBackupProgress
                            if (progress.totalFiles == 0) {
                                "Backing up workshop…"
                            } else {
                                "Backing up ${progress.uploadedFiles}/${progress.totalFiles}" +
                                    (progress.currentPath?.let { " · $it" } ?: "")
                            }
                        }
                        workshopBackupBusy && !workshopRestoreInProgress -> "Syncing workshop to PC…"
                        else -> "Sync workshop files to PC"
                    },
                    color = if (workshopBackupBusy) colors.textDim else colors.accent,
                    fontFamily = DmSansFamily,
                    fontWeight = FontWeight.Medium,
                )
            }

            TextButton(
                onClick = onRestoreWorkshopFromPc,
                enabled = !workshopBackupBusy,
                modifier = Modifier.padding(horizontal = 8.dp),
            ) {
                Text(
                    text = when {
                        workshopBackupBusy && workshopRestoreInProgress && workshopBackupProgress != null -> {
                            val progress = workshopBackupProgress
                            if (progress.totalFiles == 0) {
                                "Restoring from PC…"
                            } else {
                                "Restoring ${progress.uploadedFiles}/${progress.totalFiles}" +
                                    (progress.currentPath?.let { " · $it" } ?: "")
                            }
                        }
                        workshopBackupBusy && workshopRestoreInProgress -> "Syncing from PC…"
                        else -> "Sync workshop files from PC"
                    },
                    color = if (workshopBackupBusy) colors.textDim else colors.accent,
                    fontFamily = DmSansFamily,
                    fontWeight = FontWeight.Medium,
                )
            }

            TextButton(
                onClick = onGenerateSummary,
                enabled = !summaryGenerating && !workshopBackupBusy,
                modifier = Modifier.padding(horizontal = 8.dp),
            ) {
                Text(
                    text = when {
                        summaryGenerating -> "Generating summary…"
                        hasProjectSummary -> "Regenerate Eidos summary"
                        else -> "Generate summary now"
                    },
                    color = if (summaryGenerating) colors.textDim else colors.accent,
                    fontFamily = DmSansFamily,
                    fontWeight = FontWeight.Medium,
                )
            }

            TextButton(
                onClick = onNewFileClick,
                enabled = !workshopBackupBusy,
                modifier = Modifier.padding(horizontal = 8.dp),
            ) {
                Icon(Icons.Default.Add, contentDescription = null, tint = colors.accent)
                Spacer(Modifier.width(8.dp))
                Text(
                    text = "Add file",
                    color = colors.accent,
                    fontFamily = DmSansFamily,
                    fontWeight = FontWeight.Medium,
                )
            }
        }
    }
}

@Composable
private fun DrawerSectionHeader(
    title: String,
    icon: androidx.compose.ui.graphics.vector.ImageVector,
) {
    val colors = LocalOptimalXColors.current
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(icon, contentDescription = null, tint = colors.textDim, modifier = Modifier.padding(end = 8.dp))
        Text(
            text = title,
            color = colors.textDim,
            fontFamily = DmSansFamily,
            fontWeight = FontWeight.SemiBold,
            fontSize = 12.sp,
            letterSpacing = 1.sp,
        )
    }
}

@Composable
private fun DrawerFileItem(
    ref: FileReference,
    isSelected: Boolean,
    onClick: () -> Unit,
    onDelete: () -> Unit,
) {
    val colors = LocalOptimalXColors.current
    val bgColor = if (isSelected) colors.accentDim else Color.Transparent

    Row(
        modifier = Modifier
            .fillMaxWidth()
            .background(bgColor)
            .clickable(onClick = onClick)
            .padding(horizontal = 16.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(
            Icons.AutoMirrored.Filled.InsertDriveFile,
            contentDescription = null,
            tint = if (isSelected) colors.accent else colors.textMid,
            modifier = Modifier.padding(end = 10.dp),
        )
        Text(
            text = ref.fileName,
            color = if (isSelected) colors.accent else colors.textPrimary,
            fontFamily = DmSansFamily,
            fontSize = 14.sp,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.weight(1f),
        )
        if (!ref.fileName.equals("README.md", ignoreCase = true)) {
            IconButton(onClick = onDelete) {
                Icon(
                    Icons.Default.Delete,
                    contentDescription = "Delete",
                    tint = colors.textDim,
                )
            }
        }
    }
}

@Composable
private fun MarkdownViewPanel(
    content: String,
    modifier: Modifier = Modifier,
) {
    val colors = LocalOptimalXColors.current
    val scrollState = rememberScrollState()

    Box(
        modifier = modifier
            .background(colors.surface)
            .verticalScroll(scrollState)
            .navigationBarsPadding()
            .padding(16.dp),
    ) {
        MarkdownRichText(
            text = content,
            style = TextStyle(
                color = colors.textPrimary,
                fontFamily = DmSansFamily,
                fontSize = 15.sp,
                lineHeight = 22.sp,
            ),
            modifier = Modifier.fillMaxWidth(),
        )
    }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun CodeEditorPanel(
    content: String,
    onContentChange: (String) -> Unit,
    onSave: () -> Unit,
    isMonospace: Boolean,
    modifier: Modifier = Modifier,
) {
    val colors = LocalOptimalXColors.current
    val scrollState = rememberScrollState()
    val fontFamily = if (isMonospace) FontFamily.Monospace else DmSansFamily
    val bringIntoViewRequester = remember { BringIntoViewRequester() }
    val editorScope = rememberCoroutineScope()
    var textFieldValue by remember { mutableStateOf(TextFieldValue(content)) }

    LaunchedEffect(content) {
        if (content != textFieldValue.text) {
            textFieldValue = TextFieldValue(content)
        }
    }

    BoxWithConstraints(
        modifier = modifier
            .background(colors.surface)
            .imePadding()
            .navigationBarsPadding(),
    ) {
        val minEditorHeight = maxHeight
        Box(
            modifier = Modifier
                .fillMaxSize()
                .verticalScroll(scrollState),
        ) {
            BasicTextField(
                value = textFieldValue,
                onValueChange = { newValue ->
                    textFieldValue = newValue
                    onContentChange(newValue.text)
                },
                onTextLayout = { layoutResult ->
                    val cursorOffset = textFieldValue.selection.end
                        .coerceIn(0, layoutResult.layoutInput.text.length)
                    editorScope.launch {
                        bringIntoViewRequester.bringIntoView(
                            layoutResult.getCursorRect(cursorOffset),
                        )
                    }
                },
                textStyle = TextStyle(
                    color = colors.textPrimary,
                    fontFamily = fontFamily,
                    fontSize = 14.sp,
                    lineHeight = 20.sp,
                ),
                cursorBrush = SolidColor(colors.accent),
                modifier = Modifier
                    .fillMaxWidth()
                    .heightIn(min = minEditorHeight)
                    .bringIntoViewRequester(bringIntoViewRequester)
                    .onFocusEvent { focusState ->
                        if (focusState.isFocused) {
                            editorScope.launch {
                                bringIntoViewRequester.bringIntoView()
                            }
                        }
                    }
                    .padding(16.dp),
            )
        }
    }
}

@Composable
private fun SpecReviewPlaceholder(onOpenDocs: () -> Unit) {
    val colors = LocalOptimalXColors.current
    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(colors.surface)
            .padding(24.dp),
        verticalArrangement = Arrangement.Center,
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Text(
            text = "Review your specs",
            color = colors.textPrimary,
            fontFamily = SyneFamily,
            fontWeight = FontWeight.Bold,
            fontSize = 20.sp,
        )
        Spacer(Modifier.height(12.dp))
        Text(
            text = "Open Docs to read README, FEATURES, FLOW, and DESIGN. " +
                "Chat with Eidos to adjust (Plan mode), then tap Accept specs when aligned.",
            color = colors.textMid,
            fontFamily = DmSansFamily,
            fontSize = 15.sp,
            lineHeight = 22.sp,
        )
        Spacer(Modifier.height(20.dp))
        TextButton(onClick = onOpenDocs) {
            Text(
                text = "Open Docs",
                color = colors.accent,
                fontFamily = DmSansFamily,
                fontWeight = FontWeight.SemiBold,
                fontSize = 16.sp,
            )
        }
    }
}

@Composable
private fun DesignReviewPlaceholder(onOpenPreview: () -> Unit) {
    val colors = LocalOptimalXColors.current
    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(colors.surface)
            .padding(24.dp),
        verticalArrangement = Arrangement.Center,
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Text(
            text = "Review your layout",
            color = colors.textPrimary,
            fontFamily = SyneFamily,
            fontWeight = FontWeight.Bold,
            fontSize = 20.sp,
        )
        Spacer(Modifier.height(12.dp))
        Text(
            text = "Open Preview to check the design build. Chat with Eidos in Design mode to tweak HTML/CSS, " +
                "then tap Accept design when every FLOW screen looks right.",
            color = colors.textMid,
            fontFamily = DmSansFamily,
            fontSize = 15.sp,
            lineHeight = 22.sp,
        )
        Spacer(Modifier.height(20.dp))
        TextButton(onClick = onOpenPreview) {
            Text(
                text = "Open Preview",
                color = colors.accent,
                fontFamily = DmSansFamily,
                fontWeight = FontWeight.SemiBold,
                fontSize = 16.sp,
            )
        }
    }
}

@Composable
private fun LogicReviewPlaceholder(onOpenPreview: () -> Unit) {
    val colors = LocalOptimalXColors.current
    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(colors.surface)
            .padding(24.dp),
        verticalArrangement = Arrangement.Center,
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Text(
            text = "Test your panel",
            color = colors.textPrimary,
            fontFamily = SyneFamily,
            fontWeight = FontWeight.Bold,
            fontSize = 20.sp,
        )
        Spacer(Modifier.height(12.dp))
        Text(
            text = "Open Preview to verify behavior when ready. In Eidos, use Edit or Debug to patch code — Preview does not need to be open for Eidos to write fixes.",
            color = colors.textMid,
            fontFamily = DmSansFamily,
            fontSize = 15.sp,
            lineHeight = 22.sp,
        )
        Spacer(Modifier.height(20.dp))
        TextButton(onClick = onOpenPreview) {
            Text(
                text = "Open Preview",
                color = colors.accent,
                fontFamily = DmSansFamily,
                fontWeight = FontWeight.SemiBold,
                fontSize = 16.sp,
            )
        }
    }
}

@Composable
private fun PersistenceFinishGateDialog(
    warnings: List<String>,
    onDismiss: () -> Unit,
    onFinishAnyway: () -> Unit,
) {
    val colors = LocalOptimalXColors.current
    AlertDialog(
        onDismissRequest = onDismiss,
        containerColor = colors.surface,
        title = {
            Text(
                text = "Persistence not ready",
                color = colors.textPrimary,
                fontFamily = SyneFamily,
            )
        },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(
                    text = "This panel looks like it saves user data, but script.js is missing persistence hooks. " +
                        "Add panelGetState and panelRestoreState (see FEATURES.md), then try Accept logic again.",
                    color = colors.textMid,
                    fontFamily = DmSansFamily,
                    fontSize = 14.sp,
                )
                warnings.forEach { warning ->
                    Text(
                        text = "• $warning",
                        color = colors.textMid,
                        fontFamily = DmSansFamily,
                        fontSize = 13.sp,
                    )
                }
                Text(
                    text = "Use Eidos Edit mode to patch script.js and bridge.js. Test save/restore in Panel Gallery, not Preview only.",
                    color = colors.textMid,
                    fontFamily = DmSansFamily,
                    fontSize = 13.sp,
                )
            }
        },
        confirmButton = {
            TextButton(onClick = onDismiss) {
                Text("Fix in Edit", color = colors.accent, fontFamily = DmSansFamily)
            }
        },
        dismissButton = {
            TextButton(onClick = onFinishAnyway) {
                Text("Finish anyway", color = colors.textMid, fontFamily = DmSansFamily)
            }
        },
    )
}

@Composable
private fun UpdateEditPlaceholder(
    onOpenEidos: () -> Unit,
    onOpenPreview: () -> Unit,
) {
    val colors = LocalOptimalXColors.current
    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(colors.surface)
            .padding(24.dp),
        verticalArrangement = Arrangement.Center,
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Text(
            text = "Update / edit",
            color = colors.textPrimary,
            fontFamily = SyneFamily,
            fontWeight = FontWeight.Bold,
            fontSize = 20.sp,
        )
        Spacer(Modifier.height(12.dp))
        Text(
            text = "Chat with Eidos to plan changes, use Edit for code (review diffs before they apply), " +
                "then tap Accept update when done to sync spec docs from code.",
            color = colors.textMid,
            fontFamily = DmSansFamily,
            fontSize = 15.sp,
            lineHeight = 22.sp,
        )
        Spacer(Modifier.height(20.dp))
        TextButton(onClick = onOpenPreview) {
            Text("Open Preview", color = colors.accent, fontFamily = DmSansFamily, fontWeight = FontWeight.SemiBold)
        }
        TextButton(onClick = onOpenEidos) {
            Text("Open Eidos", color = colors.accent, fontFamily = DmSansFamily, fontWeight = FontWeight.SemiBold)
        }
    }
}

@Composable
private fun IntakePlaceholder(onOpenEidos: () -> Unit) {
    val colors = LocalOptimalXColors.current
    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(colors.surface)
            .padding(24.dp),
        verticalArrangement = Arrangement.Center,
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Text(
            text = "Start in chat with Eidos",
            color = colors.textPrimary,
            fontFamily = SyneFamily,
            fontWeight = FontWeight.Bold,
            fontSize = 20.sp,
        )
        Spacer(Modifier.height(12.dp))
        Text(
            text = "Discuss what you want to build, why you want it, and how it should work. " +
                "When you are aligned, tap Generate specs in the top bar.",
            color = colors.textMid,
            fontFamily = DmSansFamily,
            fontSize = 15.sp,
            lineHeight = 22.sp,
        )
        Spacer(Modifier.height(20.dp))
        TextButton(onClick = onOpenEidos) {
            Text(
                text = "Open Eidos",
                color = colors.accent,
                fontFamily = DmSansFamily,
                fontWeight = FontWeight.SemiBold,
                fontSize = 16.sp,
            )
        }
    }
}

@Composable
private fun EmptyEditorPlaceholder() {
    val colors = LocalOptimalXColors.current
    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(colors.surface),
        contentAlignment = Alignment.Center,
    ) {
        Text(
            text = "Open a file from the sidebar",
            color = colors.textDim,
            fontFamily = DmSansFamily,
            fontSize = 15.sp,
        )
    }
}

@Composable
private fun NewFileDialog(
    onDismiss: () -> Unit,
    onCreate: (String) -> Unit,
) {
    val colors = LocalOptimalXColors.current
    var fileName by remember { mutableStateOf("") }

    AlertDialog(
        onDismissRequest = onDismiss,
        containerColor = colors.surface,
        title = {
            Text(
                text = "Add optional file",
                color = colors.textPrimary,
                fontFamily = SyneFamily,
                fontWeight = FontWeight.Bold,
            )
        },
        text = {
            Column {
                TextField(
                    value = fileName,
                    onValueChange = { fileName = it },
                    placeholder = {
                        Text(
                            "e.g. utils.js, assets.md",
                            color = colors.textDim,
                            fontFamily = DmSansFamily,
                        )
                    },
                    singleLine = true,
                    colors = TextFieldDefaults.colors(
                        focusedContainerColor = colors.surface2,
                        unfocusedContainerColor = colors.surface2,
                        focusedTextColor = colors.textPrimary,
                        unfocusedTextColor = colors.textPrimary,
                        cursorColor = colors.accent,
                        focusedIndicatorColor = colors.accent,
                        unfocusedIndicatorColor = colors.border,
                    ),
                    modifier = Modifier.fillMaxWidth(),
                )
            }
        },
        confirmButton = {
            TextButton(
                onClick = { if (fileName.isNotBlank()) onCreate(fileName.trim()) },
                enabled = fileName.isNotBlank(),
            ) {
                Text("Create", color = if (fileName.isNotBlank()) colors.accent else colors.textDim)
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) {
                Text("Cancel", color = colors.textMid)
            }
        },
    )
}
