package com.example.optimalx.ui.eidos

import android.Manifest
import android.app.Application
import android.widget.Toast
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.VolumeUp
import androidx.compose.material.icons.filled.ContentCopy
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.ExpandLess
import androidx.compose.material.icons.filled.ExpandMore
import androidx.compose.material.icons.filled.KeyboardArrowDown
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.AssistChip
import androidx.compose.material3.AssistChipDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TextField
import androidx.compose.material3.TextFieldDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.platform.LocalLifecycleOwner
import androidx.compose.ui.text.AnnotatedString
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.content.ContextCompat
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import com.example.optimalx.data.imagestudio.ImageStudioDraftParser
import com.example.optimalx.data.eidos.WorkshopEidosMode
import com.example.optimalx.data.eidos.WorkshopProjectPhase
import com.example.optimalx.data.eidos.WorkshopUpdateSection
import com.example.optimalx.data.eidos.EidosNavigationTarget
import com.example.optimalx.data.revision.SCOPE_SUBFOLDER
import com.example.optimalx.data.revision.SCOPE_WORKSHOP_PROJECT
import com.example.optimalx.ui.components.ChatComposerBar
import com.example.optimalx.ui.components.ClearVoiceRecordingDialog
import com.example.optimalx.data.eidos.model.EidosRole
import com.example.optimalx.ui.eidos.components.ChatVisionMessageImage
import com.example.optimalx.ui.eidos.components.EidosNavigationChips
import com.example.optimalx.ui.components.ChatMessageBubbleFooter
import com.example.optimalx.ui.components.ChatMessageRetryButton
import com.example.optimalx.ui.components.ChatTopBar
import com.example.optimalx.ui.components.MarkdownRichText
import com.example.optimalx.ui.components.SelectablePlainText
import com.example.optimalx.ui.workshop.WorkshopEidosModeSelector
import com.example.optimalx.ui.theme.DmMonoFamily
import com.example.optimalx.ui.theme.DmSansFamily
import com.example.optimalx.ui.theme.LocalOptimalXColors
import com.example.optimalx.OptimalXApplication
import com.example.optimalx.ui.editor.components.NoteReadAloudBar
import com.example.optimalx.voice.ReadAloudSession
import com.example.optimalx.voice.VoiceController
import com.example.optimalx.voice.VoiceSessionState

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun EidosChatScreen(
    viewModel: EidosChatViewModel,
    onBack: () -> Unit,
    onOpenDiffReview: (subfolderId: Long, scopeType: String) -> Unit = { _, _ -> },
    onNavigateFromChat: ((EidosNavigationTarget) -> Unit)? = null,
    onChatLinkClick: ((String) -> Boolean)? = null,
) {
    val colors = LocalOptimalXColors.current
    val context = LocalContext.current
    val focusManager = LocalFocusManager.current

    DisposableEffect(Unit) {
        viewModel.setChatUiVisible(true)
        onDispose {
            viewModel.setChatUiVisible(false)
            viewModel.endSession()
        }
    }

    LaunchedEffect(viewModel) {
        viewModel.toastMessage.collect { message ->
            Toast.makeText(context, message, Toast.LENGTH_SHORT).show()
        }
    }

    val messages by viewModel.messages.collectAsState()
    val isImageStudioScope by viewModel.isImageStudioScope.collectAsState()
    val input by viewModel.input.collectAsState()
    val pendingImage by viewModel.pendingImage.collectAsState()
    val isSending by viewModel.isSending.collectAsState()
    val showKimiThinking by viewModel.showKimiThinkingIndicator.collectAsState()
    val streamPreview by viewModel.streamPreview.collectAsState()
    val readAloud by viewModel.readAloud.collectAsState()
    val readAloudMicPassback by viewModel.readAloudMicPassback.collectAsState()
    val readAloudInfoDismissed by viewModel.readAloudInfoDismissed.collectAsState()
    val localGemmaToolsEnabled by viewModel.localGemmaToolsEnabled.collectAsState()
    val micUseWhisperApi by viewModel.micUseWhisperApi.collectAsState()
    val micUseLocalGemmaScribe by viewModel.micUseLocalGemmaScribe.collectAsState()
    val hasOpenAiApiKey by viewModel.hasOpenAiApiKey.collectAsState()
    val activeProvider by viewModel.activeProvider.collectAsState()
    val eidosThinkingLevel by viewModel.eidosThinkingLevel.collectAsState()
    val rereadMessageId by viewModel.rereadMessageId.collectAsState()
    val conversationSummaries by viewModel.conversationSummaries.collectAsState()
    val historyDirectoryOptions by viewModel.historyDirectoryOptions.collectAsState()
    val selectedHistoryDirectory by viewModel.selectedHistoryDirectory.collectAsState()
    val historyContextLabel by viewModel.historyContextLabel.collectAsState()
    val historyLocationTargets by viewModel.historyLocationTargets.collectAsState()
    val historyParentTargets by viewModel.historyParentTargets.collectAsState()
    val selectedHistoryParentId by viewModel.selectedHistoryParentId.collectAsState()
    val selectedHistoryLocationId by viewModel.selectedHistoryLocationId.collectAsState()
    val hasActiveConversation by viewModel.hasActiveConversation.collectAsState()
    val chatScopeLabel by viewModel.chatScopeLabel.collectAsState()
    val pendingConfirmation by viewModel.pendingConfirmation.collectAsState()
    val restrictQuickNotesToolbar by viewModel.restrictQuickNotesChatToolbar.collectAsState()
    val restrictWebToolbar by viewModel.restrictWebChatToolbar.collectAsState()
    val restrictChatToolbar = restrictQuickNotesToolbar || restrictWebToolbar
    val quickNotesInboxSubfolderId by viewModel.quickNotesInboxSubfolderId.collectAsState()
    val workshopScopeSubfolderId by viewModel.workshopScopeSubfolderId.collectAsState()
    val noteScopeSubfolderId by viewModel.noteScopeSubfolderId.collectAsState()
    val workshopEidosModeChipSelection by viewModel.workshopEidosModeChipSelection.collectAsState()
    val workshopProjectPhase by viewModel.workshopProjectPhase.collectAsState()
    val workshopUpdateSection by viewModel.workshopUpdateSection.collectAsState()
    val workshopPendingChangeCount by viewModel.workshopPendingChangeCount.collectAsState()
    val notePendingChangeCount by viewModel.notePendingChangeCount.collectAsState()
    val workshopSelectorModes = remember(workshopProjectPhase, workshopUpdateSection) {
        workshopProjectPhase.selectorModes(workshopUpdateSection)
    }

    val clipboardManager = LocalClipboardManager.current

    // Widget (and other writers) update the DB while this sheet can stay open — keep UI in sync.
    LaunchedEffect(restrictQuickNotesToolbar, quickNotesInboxSubfolderId) {
        val subId = quickNotesInboxSubfolderId ?: return@LaunchedEffect
        if (!restrictQuickNotesToolbar) return@LaunchedEffect
        while (true) {
            viewModel.resyncQuickNotesInboxFromDatabase(subId)
            delay(2500L)
        }
    }

    // Edit dialog state
    var editingMessage by remember { mutableStateOf<EidosUiMessage?>(null) }
    var editDraft by remember { mutableStateOf("") }
    var showHistorySheet by remember { mutableStateOf(false) }
    var showNewChatConfirm by remember { mutableStateOf(false) }
    var showMoveHereConfirm by remember { mutableStateOf(false) }
    var renamingConversationId by remember { mutableStateOf<Long?>(null) }
    var renameDraft by remember { mutableStateOf("") }
    var expandedReasoningIds by remember { mutableStateOf(setOf<Long>()) }

    val readAloudSession: ReadAloudSession =
        (context.applicationContext as OptimalXApplication).readAloudSession
    val readAloudBarVisible by readAloudSession.barVisible.collectAsState()
    val readAloudIsPlaying by readAloudSession.isPlaying.collectAsState()

    val voiceController: VoiceController = viewModel(
        factory = viewModelFactory {
            initializer {
                val app = this[androidx.lifecycle.ViewModelProvider.AndroidViewModelFactory.APPLICATION_KEY]!!
                VoiceController(app as Application)
            }
        }
    )
    val sessionState by voiceController.sessionState.collectAsState()
    val liveTranscript by voiceController.liveTranscript.collectAsState()
    val usesWhisperCapture by voiceController.usesWhisperCapture.collectAsState()

    LaunchedEffect(micUseWhisperApi, micUseLocalGemmaScribe) {
        viewModel.refreshOpenAiKeyPresence()
        voiceController.applyMicEnginePreferenceFromSettings()
    }

    val lifecycleOwner = LocalLifecycleOwner.current
    DisposableEffect(lifecycleOwner, voiceController) {
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_STOP) {
                voiceController.stopSession()
            }
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose {
            lifecycleOwner.lifecycle.removeObserver(observer)
            voiceController.stopSession()
        }
    }

    val isListening = sessionState == VoiceSessionState.LISTENING
    val isTranscribing = sessionState == VoiceSessionState.TRANSCRIBING
    val isPaused = sessionState == VoiceSessionState.PAUSED
    val isSpeaking = sessionState == VoiceSessionState.SPEAKING || readAloudIsPlaying
    val isCapturingVoice = if (usesWhisperCapture) isListening || isTranscribing else isListening

    // TTS only for genuinely new assistant messages after load (not history opened from DB).
    var lastSeenMessageCount by remember { mutableIntStateOf(0) }
    var initialMessagesLoadDone by remember { mutableStateOf(false) }
    LaunchedEffect(messages.size) {
        if (!initialMessagesLoadDone) {
            lastSeenMessageCount = messages.size
            if (messages.isNotEmpty()) initialMessagesLoadDone = true
        } else if (messages.size > lastSeenMessageCount) {
            val last = messages.lastOrNull()
            if (last != null && last.role == EidosRole.ASSISTANT && readAloud) {
                voiceController.stopSession()
                readAloudSession.startFromChat(last.text) {
                    if (readAloudMicPassback) {
                        voiceController.startListening(existingText = "") { text ->
                            viewModel.setInput(text)
                        }
                    }
                }
            }
            lastSeenMessageCount = messages.size
        } else {
            lastSeenMessageCount = messages.size
        }
    }

    var showClearRecordingDialog by remember { mutableStateOf(false) }

    fun commitAndSend() {
        voiceController.commitVoiceThen(mergeBaseText = input) { viewModel.sendMessage() }
    }

    val permissionLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.RequestPermission(),
        onResult = { granted ->
            if (granted) {
                voiceController.startListening(existingText = input) { text -> viewModel.setInput(text) }
            }
        },
    )

    val listState = rememberLazyListState()
    val scope = rememberCoroutineScope()

    fun navigateBack() {
        voiceController.stopSession()
        onBack()
    }

    val isNearBottom by remember {
        derivedStateOf {
            val layout = listState.layoutInfo
            val lastVisible = layout.visibleItemsInfo.lastOrNull()
            if (lastVisible == null || layout.totalItemsCount == 0) {
                true
            } else {
                // Treat one-item gap as "at bottom" so incoming messages still auto-follow.
                lastVisible.index >= layout.totalItemsCount - 2
            }
        }
    }

    var scrollAnchorFirstMessageId by remember { mutableStateOf<Long?>(null) }
    var scrollAnchorMessageCount by remember { mutableIntStateOf(-1) }

    val streamPreviewLength = streamPreview?.contentText?.length ?: 0
    val streamReasoningLength = streamPreview?.reasoningText?.length ?: 0
    val lastMessageTextLength = messages.lastOrNull()?.text?.length ?: 0

    LaunchedEffect(
        messages.size,
        messages.firstOrNull()?.id,
        lastMessageTextLength,
        listState.layoutInfo.totalItemsCount,
        showKimiThinking,
        isSending,
        streamPreviewLength,
        streamReasoningLength,
    ) {
        if (messages.isEmpty() && !showKimiThinking) return@LaunchedEffect
        val firstId = messages.firstOrNull()?.id ?: return@LaunchedEffect
        val conversationChanged =
            scrollAnchorFirstMessageId != null && scrollAnchorFirstMessageId != firstId
        val pendingInitialScroll = scrollAnchorFirstMessageId == null
        val newMessagesAppended = messages.size > scrollAnchorMessageCount
        val streamGrowing = showKimiThinking && isSending &&
            (streamPreviewLength > 0 || streamReasoningLength > 0)
        val shouldScroll = conversationChanged ||
            pendingInitialScroll ||
            (newMessagesAppended && isNearBottom) ||
            (showKimiThinking && isSending && isNearBottom) ||
            (streamGrowing && isNearBottom)
        if (!shouldScroll) return@LaunchedEffect
        val lastIndex = messages.lastIndex + if (showKimiThinking && isSending) 1 else 0
        if (listState.layoutInfo.totalItemsCount <= lastIndex) return@LaunchedEffect
        listState.scrollToItem(lastIndex)
        scrollAnchorFirstMessageId = firstId
        scrollAnchorMessageCount = messages.size
    }

    Scaffold(
        containerColor = colors.sheetBackground,
        topBar = {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .statusBarsPadding(),
            ) {
                ChatTopBar(
                    onBack = { navigateBack() },
                    scopeLabel = chatScopeLabel,
                    workshopPhaseLabel = if (workshopScopeSubfolderId != null)
                        workshopProjectPhase.phaseLabel(workshopUpdateSection) else null,
                    hasActiveConversation = hasActiveConversation,
                    isSending = isSending,
                    onHistoryClick = {
                        viewModel.openConversationBrowser()
                        showHistorySheet = true
                    },
                    onNewChatClick = { showNewChatConfirm = true },
                    onMoveClick = { showMoveHereConfirm = true },
                    onStopClick = { viewModel.cancelActiveSend() },
                    localGemmaToolsEnabled = localGemmaToolsEnabled,
                    onLocalGemmaToolsEnabledChange = viewModel::setLocalGemmaToolsEnabled,
                    readAloud = readAloud,
                    readAloudMicPassback = readAloudMicPassback,
                    onReadAloudChange = viewModel::setReadAloud,
                    onMicPassbackChange = viewModel::setReadAloudMicPassback,
                    readAloudInfoDismissed = readAloudInfoDismissed,
                    onReadAloudInfoDismissedChange = viewModel::setReadAloudInfoDismissed,
                    micUseWhisperApi = micUseWhisperApi,
                    hasOpenAiApiKey = hasOpenAiApiKey,
                    onMicUseWhisperApiChange = viewModel::setMicUseWhisperApi,
                    activeProvider = activeProvider,
                    onActiveProviderChange = viewModel::setActiveProvider,
                    thinkingLevel = eidosThinkingLevel,
                    onThinkingLevelChange = viewModel::setEidosThinkingLevel,
                    onOpenChatSettings = viewModel::refreshOpenAiKeyPresence,
                    restrictToolbar = restrictChatToolbar,
                )
                if (workshopScopeSubfolderId != null) {
                    WorkshopEidosModeSelector(
                        activeMode = workshopEidosModeChipSelection,
                        onModeSelected = viewModel::setWorkshopEidosMode,
                        visibleModes = workshopSelectorModes,
                        modifier = Modifier.padding(top = 8.dp),
                    )
                }
            }
        },
    ) { padding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .navigationBarsPadding()
                .padding(horizontal = 14.dp, vertical = 12.dp),
        ) {
            Box(
                modifier = Modifier
                    .weight(1f)
                    .fillMaxWidth(),
            ) {
                // Dismiss keyboard when the user scrolls the transcript — avoid a parent
                // clickable over the list (it can swallow navigation chip taps).
                val isScrolling = listState.isScrollInProgress
                LaunchedEffect(isScrolling) {
                    if (isScrolling) focusManager.clearFocus()
                }
                LazyColumn(
                    state = listState,
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(top = 8.dp, bottom = 10.dp),
                    verticalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    items(messages, key = { it.id }) { message ->
                        val isUser = message.role == EidosRole.USER
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = if (isUser) Arrangement.End else Arrangement.Start,
                        ) {
                            val bubbleShape = RoundedCornerShape(14.dp)
                            val bubbleModifier = if (isUser) {
                                Modifier
                                    .clip(bubbleShape)
                                    .background(colors.messageBubbleUser)
                            } else {
                                Modifier
                                    .border(1.dp, colors.messageBubbleEidosBorder, bubbleShape)
                                    .clip(bubbleShape)
                                    .background(
                                        brush = Brush.verticalGradient(
                                            listOf(colors.accentDim, colors.surface2),
                                        ),
                                    )
                            }
                            Column(
                                modifier = bubbleModifier
                                    .fillMaxWidth(0.88f)
                                    .padding(horizontal = 11.dp, vertical = 9.dp),
                            ) {
                                val reasoningText = message.reasoningText
                                if (!isUser && !reasoningText.isNullOrBlank()) {
                                    val reasoningExpanded = message.id in expandedReasoningIds
                                    Row(
                                        modifier = Modifier
                                            .clip(RoundedCornerShape(8.dp))
                                            .clickable {
                                                expandedReasoningIds = if (reasoningExpanded) {
                                                    expandedReasoningIds - message.id
                                                } else {
                                                    expandedReasoningIds + message.id
                                                }
                                            }
                                            .padding(vertical = 2.dp),
                                        verticalAlignment = Alignment.CenterVertically,
                                    ) {
                                        Text(
                                            text = "Reasoning",
                                            color = colors.textMid,
                                            fontFamily = DmSansFamily,
                                            fontSize = 12.sp,
                                            fontWeight = FontWeight.Medium,
                                        )
                                        Icon(
                                            imageVector = if (reasoningExpanded) {
                                                Icons.Default.ExpandLess
                                            } else {
                                                Icons.Default.ExpandMore
                                            },
                                            contentDescription = if (reasoningExpanded) {
                                                "Hide reasoning"
                                            } else {
                                                "Show reasoning"
                                            },
                                            tint = colors.textDim,
                                            modifier = Modifier
                                                .padding(start = 2.dp)
                                                .size(18.dp),
                                        )
                                    }
                                    if (reasoningExpanded) {
                                        SelectablePlainText(
                                            text = reasoningText,
                                            color = colors.textDim,
                                            fontSizeSp = 12f,
                                            lineHeightRatio = 16f / 12f,
                                            modifier = Modifier.padding(top = 4.dp, bottom = 6.dp),
                                        )
                                    }
                                }
                                message.imageAttachment?.let { attachment ->
                                    ChatVisionMessageImage(attachment = attachment)
                                }
                                MarkdownRichText(
                                    text = message.text,
                                    style = TextStyle(
                                        color = colors.textPrimary,
                                        fontFamily = DmSansFamily,
                                        fontSize = 14.sp,
                                        lineHeight = 19.sp,
                                    ),
                                    selectable = true,
                                    onLinkClick = onChatLinkClick,
                                )
                                if (!isUser && message.navigationTargets.isNotEmpty()) {
                                    EidosNavigationChips(
                                        targets = message.navigationTargets,
                                        onNavigate = { target ->
                                            if (onNavigateFromChat != null) {
                                                onNavigateFromChat(target)
                                            } else {
                                                viewModel.postToast(
                                                    "Open navigation from the main Eidos chat screen.",
                                                )
                                            }
                                        },
                                    )
                                }
                                if (
                                    !isUser &&
                                    isImageStudioScope &&
                                    ImageStudioDraftParser.hasImageStudioDraft(message.text)
                                ) {
                                    AssistChip(
                                        onClick = {
                                            viewModel.requestImageStudioDraftHandoff(message.text)
                                            onBack()
                                        },
                                        label = {
                                            Text(
                                                text = "Use in Image Studio",
                                                fontFamily = DmSansFamily,
                                                fontSize = 12.sp,
                                            )
                                        },
                                        colors = AssistChipDefaults.assistChipColors(
                                            containerColor = colors.surface2,
                                            labelColor = colors.accent,
                                        ),
                                        modifier = Modifier.padding(top = 8.dp),
                                    )
                                }
                                ChatMessageBubbleFooter(timeLabel = message.timeLabel) {
                                    if (!isUser) {
                                        val isRereadActive =
                                            message.id == rereadMessageId && readAloudBarVisible
                                        IconButton(
                                            onClick = {
                                                if (isRereadActive) {
                                                    readAloudSession.stop()
                                                    viewModel.setRereadMessageId(null)
                                                } else {
                                                    voiceController.stopSession()
                                                    viewModel.setRereadMessageId(message.id)
                                                    readAloudSession.startFromChat(message.text) {
                                                        viewModel.setRereadMessageId(null)
                                                    }
                                                }
                                            },
                                            modifier = Modifier.size(28.dp),
                                        ) {
                                            Icon(
                                                imageVector = Icons.AutoMirrored.Filled.VolumeUp,
                                                contentDescription = if (isRereadActive) "Stop" else "Read aloud",
                                                tint = if (isRereadActive) colors.accent else colors.textDim,
                                                modifier = Modifier.size(18.dp),
                                            )
                                        }
                                    }
                                    IconButton(
                                        onClick = {
                                            clipboardManager.setText(AnnotatedString(message.text))
                                        },
                                        modifier = Modifier.size(28.dp),
                                    ) {
                                        Icon(
                                            imageVector = Icons.Default.ContentCopy,
                                            contentDescription = "Copy",
                                            tint = colors.textDim,
                                            modifier = Modifier.size(18.dp),
                                        )
                                    }
                                    if (isUser) {
                                        ChatMessageRetryButton(
                                            onClick = { viewModel.retryMessage(message.id, message.text) },
                                        )
                                        IconButton(
                                            onClick = {
                                                editingMessage = message
                                                editDraft = message.text
                                            },
                                            modifier = Modifier.size(28.dp),
                                        ) {
                                            Icon(
                                                imageVector = Icons.Default.Edit,
                                                contentDescription = "Edit message",
                                                tint = colors.textDim,
                                                modifier = Modifier.size(18.dp),
                                            )
                                        }
                                    }
                                }
                            }
                        }
                    }

                    val pendingReviewSubId = workshopScopeSubfolderId
                    if (pendingReviewSubId != null && workshopPendingChangeCount > 0 && !isSending) {
                        item(key = "diff_review_banner") {
                            DiffReviewBanner(
                                count = workshopPendingChangeCount,
                                onClick = {
                                    onOpenDiffReview(pendingReviewSubId, SCOPE_WORKSHOP_PROJECT)
                                },
                            )
                        }
                    }

                    val noteReviewSubId = noteScopeSubfolderId
                    if (noteReviewSubId != null && notePendingChangeCount > 0 && !isSending) {
                        item(key = "note_diff_review_banner") {
                            DiffReviewBanner(
                                count = notePendingChangeCount,
                                onClick = {
                                    onOpenDiffReview(noteReviewSubId, SCOPE_SUBFOLDER)
                                },
                            )
                        }
                    }

                    if (showKimiThinking && isSending) {
                        item(key = "kimi_thinking") {
                            KimiStreamPreviewBubble(preview = streamPreview)
                        }
                    }
                }

                if (!isNearBottom && messages.isNotEmpty()) {
                    IconButton(
                        onClick = {
                            scope.launch {
                                val lastIndex = messages.lastIndex + if (showKimiThinking && isSending) 1 else 0
                                listState.animateScrollToItem(lastIndex)
                            }
                        },
                        modifier = Modifier
                            .align(Alignment.BottomEnd)
                            .padding(bottom = 4.dp, end = 4.dp)
                            .size(32.dp)
                            .clip(CircleShape)
                            .background(colors.surface2)
                            .border(1.dp, colors.border, CircleShape),
                    ) {
                        Icon(
                            imageVector = Icons.Default.KeyboardArrowDown,
                            contentDescription = "Scroll to bottom",
                            tint = colors.accent,
                            modifier = Modifier.size(20.dp),
                        )
                    }
                }
            }

            if (readAloudBarVisible) {
                NoteReadAloudBar(
                    isPlaying = readAloudIsPlaying,
                    onPlayPause = readAloudSession::togglePlayback,
                    onRewind10 = readAloudSession::rewind10Seconds,
                    onForward10 = readAloudSession::forward10Seconds,
                )
            }

            ChatComposerBar(
                input = input,
                onInputChange = viewModel::setInput,
                onSend = { commitAndSend() },
                onMicClick = {
                    if (readAloudBarVisible && readAloudIsPlaying) {
                        readAloudSession.stop()
                        return@ChatComposerBar
                    }
                    when (sessionState) {
                        VoiceSessionState.LISTENING,
                        VoiceSessionState.PAUSED,
                        -> voiceController.handleComposerMicTap(input)
                        VoiceSessionState.SPEAKING,
                        -> {
                            if (readAloudBarVisible) {
                                readAloudSession.stop()
                            } else {
                                voiceController.stopSession()
                            }
                        }
                        VoiceSessionState.IDLE -> {
                            val permState = ContextCompat.checkSelfPermission(
                                context, Manifest.permission.RECORD_AUDIO
                            )
                            if (permState == android.content.pm.PackageManager.PERMISSION_GRANTED) {
                                voiceController.startListening(existingText = input) { text ->
                                    viewModel.setInput(text)
                                }
                            } else {
                                permissionLauncher.launch(Manifest.permission.RECORD_AUDIO)
                            }
                        }
                        else -> Unit
                    }
                },
                onDiscardRecording = {
                    if (usesWhisperCapture) showClearRecordingDialog = true
                },
                onStopClick = { viewModel.cancelActiveSend() },
                sessionState = sessionState,
                isSending = isSending,
                isCapturingVoice = isCapturingVoice,
                isPaused = isPaused,
                isSpeaking = isSpeaking,
                liveTranscript = liveTranscript,
                usesWhisperBufferedCapture = usesWhisperCapture,
                onTextFieldFocused = {
                    if (isListening) voiceController.finalizeListeningForEdit(input)
                },
                pendingImage = pendingImage,
                onAttachImageUri = viewModel::attachImageFromUri,
                onClearPendingImage = viewModel::clearPendingImage,
                onAttachError = viewModel::postToast,
                modifier = Modifier.imePadding(),
            )
        }
    }

    if (showClearRecordingDialog) {
        ClearVoiceRecordingDialog(
            onConfirm = {
                showClearRecordingDialog = false
                voiceController.discardRecording()
            },
            onDismiss = { showClearRecordingDialog = false },
        )
    }

    if (showHistorySheet) {
        ConversationHistorySheet(
            summaries = conversationSummaries,
            directories = historyDirectoryOptions,
            selectedDirectory = selectedHistoryDirectory,
            onDirectorySelected = viewModel::setHistoryDirectory,
            parentTargets = historyParentTargets,
            selectedParentId = selectedHistoryParentId,
            onParentSelected = viewModel::setHistoryParentTarget,
            onOpenSubfolders = viewModel::openSubfolderDirectory,
            onOpenParentChats = viewModel::openParentDirectoryChats,
            locationTargets = historyLocationTargets,
            selectedLocationId = selectedHistoryLocationId,
            onLocationSelected = viewModel::setHistoryLocationTarget,
            contextLabel = historyContextLabel,
            onSelect = { id -> viewModel.switchConversation(id) },
            onRename = { id, currentTitle ->
                renamingConversationId = id
                renameDraft = currentTitle
            },
            onDismiss = { showHistorySheet = false },
            isRequestInFlight = isSending,
            onStopRequest = { viewModel.cancelActiveSend() },
        )
    }

    // Edit message dialog
    editingMessage?.let { msg ->
        AlertDialog(
            onDismissRequest = { editingMessage = null },
            title = {
                Text("Edit message", fontFamily = DmSansFamily, color = colors.textPrimary)
            },
            text = {
                TextField(
                    value = editDraft,
                    onValueChange = { editDraft = it },
                    colors = TextFieldDefaults.colors(
                        focusedContainerColor = colors.surface2,
                        unfocusedContainerColor = colors.surface2,
                        focusedIndicatorColor = colors.accent,
                        unfocusedIndicatorColor = colors.border,
                        cursorColor = colors.accent,
                        focusedTextColor = colors.textPrimary,
                        unfocusedTextColor = colors.textPrimary,
                    ),
                )
            },
            confirmButton = {
                TextButton(onClick = {
                    viewModel.editMessage(msg.id, editDraft)
                    editingMessage = null
                }) {
                    Text("Save", color = colors.accent, fontFamily = DmSansFamily)
                }
            },
            dismissButton = {
                TextButton(onClick = { editingMessage = null }) {
                    Text("Cancel", color = colors.textMid, fontFamily = DmSansFamily)
                }
            },
            containerColor = colors.sheetBackground,
        )
    }

    if (showNewChatConfirm) {
        AlertDialog(
            onDismissRequest = { showNewChatConfirm = false },
            title = {
                Text("Start new chat?", fontFamily = DmSansFamily, color = colors.textPrimary)
            },
            text = {
                Text(
                    "This will clear the current chat view and start a new conversation.",
                    color = colors.textMid,
                    fontFamily = DmSansFamily,
                )
            },
            confirmButton = {
                TextButton(onClick = {
                    showNewChatConfirm = false
                    viewModel.newChat()
                }) {
                    Text("Yes", color = colors.accent, fontFamily = DmSansFamily)
                }
            },
            dismissButton = {
                TextButton(onClick = { showNewChatConfirm = false }) {
                    Text("No", color = colors.textMid, fontFamily = DmSansFamily)
                }
            },
            containerColor = colors.sheetBackground,
        )
    }

    if (showMoveHereConfirm) {
        AlertDialog(
            onDismissRequest = { showMoveHereConfirm = false },
            title = {
                Text("Move conversation here?", fontFamily = DmSansFamily, color = colors.textPrimary)
            },
            text = {
                Text(
                    "This will change the conversation directory to this current location.",
                    color = colors.textMid,
                    fontFamily = DmSansFamily,
                )
            },
            confirmButton = {
                TextButton(onClick = {
                    showMoveHereConfirm = false
                    viewModel.moveActiveConversationToCurrentScope()
                }) {
                    Text("Yes", color = colors.accent, fontFamily = DmSansFamily)
                }
            },
            dismissButton = {
                TextButton(onClick = { showMoveHereConfirm = false }) {
                    Text("No", color = colors.textMid, fontFamily = DmSansFamily)
                }
            },
            containerColor = colors.sheetBackground,
        )
    }

    renamingConversationId?.let { conversationId ->
        AlertDialog(
            onDismissRequest = { renamingConversationId = null },
            title = {
                Text("Rename chat", fontFamily = DmSansFamily, color = colors.textPrimary)
            },
            text = {
                TextField(
                    value = renameDraft,
                    onValueChange = { renameDraft = it },
                    colors = TextFieldDefaults.colors(
                        focusedContainerColor = colors.surface2,
                        unfocusedContainerColor = colors.surface2,
                        focusedIndicatorColor = colors.accent,
                        unfocusedIndicatorColor = colors.border,
                        cursorColor = colors.accent,
                        focusedTextColor = colors.textPrimary,
                        unfocusedTextColor = colors.textPrimary,
                    ),
                )
            },
            confirmButton = {
                TextButton(onClick = {
                    viewModel.renameConversation(conversationId, renameDraft)
                    renamingConversationId = null
                }) {
                    Text("Save", color = colors.accent, fontFamily = DmSansFamily)
                }
            },
            dismissButton = {
                TextButton(onClick = { renamingConversationId = null }) {
                    Text("Cancel", color = colors.textMid, fontFamily = DmSansFamily)
                }
            },
            containerColor = colors.sheetBackground,
        )
    }

    pendingConfirmation?.let { req ->
        val friendlyName = req.toolName.replace('_', ' ').replaceFirstChar { it.uppercase() }
        AlertDialog(
            onDismissRequest = { viewModel.denyConfirmation() },
            title = {
                Text("Allow action?", fontFamily = DmSansFamily, color = colors.textPrimary)
            },
            text = {
                androidx.compose.foundation.layout.Column(
                    verticalArrangement = androidx.compose.foundation.layout.Arrangement.spacedBy(8.dp),
                ) {
                    Text(
                        "Eidos wants to run: $friendlyName",
                        color = colors.textPrimary,
                        fontFamily = DmSansFamily,
                    )
                    if (req.argsJson.length <= 300) {
                        Text(
                            req.argsJson,
                            color = colors.textDim,
                            fontFamily = com.example.optimalx.ui.theme.DmMonoFamily,
                            fontSize = 12.sp,
                        )
                    }
                }
            },
            confirmButton = {
                TextButton(onClick = { viewModel.approveConfirmation() }) {
                    Text("Allow", color = colors.accent, fontFamily = DmSansFamily)
                }
            },
            dismissButton = {
                TextButton(onClick = { viewModel.denyConfirmation() }) {
                    Text("Deny", color = colors.textMid, fontFamily = DmSansFamily)
                }
            },
            containerColor = colors.sheetBackground,
        )
    }
}

@Composable
private fun DiffReviewBanner(
    count: Int,
    onClick: () -> Unit,
) {
    val colors = LocalOptimalXColors.current
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(10.dp))
            .border(1.dp, colors.accentBorder, RoundedCornerShape(10.dp))
            .background(colors.accentDim)
            .clickable(onClick = onClick)
            .padding(horizontal = 12.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = if (count == 1) "Review 1 proposed change" else "Review $count proposed changes",
                color = colors.textPrimary,
                fontFamily = DmSansFamily,
                fontWeight = FontWeight.Medium,
                fontSize = 14.sp,
            )
            Text(
                text = "Tap to open the diff review screen",
                color = colors.textMid,
                fontFamily = DmSansFamily,
                fontSize = 12.sp,
            )
        }
        Text(
            text = "Review",
            color = colors.accent,
            fontFamily = DmSansFamily,
            fontWeight = FontWeight.SemiBold,
            fontSize = 13.sp,
        )
    }
}
