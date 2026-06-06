package com.example.optimalx.widget

import android.Manifest
import android.app.Application
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
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
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TextField
import androidx.compose.material3.TextFieldDefaults
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.platform.LocalLifecycleOwner
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.ui.platform.UriHandler
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.content.ContextCompat
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import com.example.optimalx.OptimalXApplication
import com.example.optimalx.data.eidos.model.EidosRole
import com.example.optimalx.ui.components.ChatComposerBar
import com.example.optimalx.ui.components.ClearVoiceRecordingDialog
import com.example.optimalx.ui.components.ChatMessageBubbleFooter
import com.example.optimalx.ui.components.ChatTopBar
import com.example.optimalx.ui.components.MarkdownRichText
import com.example.optimalx.ui.components.SelectablePlainText
import com.example.optimalx.ui.eidos.EidosChatEntrySurface
import com.example.optimalx.ui.eidos.EidosChatViewModel
import com.example.optimalx.ui.theme.DmMonoFamily
import com.example.optimalx.ui.theme.DmSansFamily
import com.example.optimalx.ui.theme.LocalOptimalXColors
import com.example.optimalx.ui.theme.OptimalXTheme
import com.example.optimalx.voice.VoiceController
import com.example.optimalx.voice.VoiceSessionState
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.launch

class WidgetChatActivity : ComponentActivity() {

    companion object {
        const val EXTRA_AUTO_START_MIC = "auto_start_mic"
    }

    private var onPermissionResult: ((Boolean) -> Unit)? = null
    private val permissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { granted -> onPermissionResult?.invoke(granted) }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        // Prefer an explicit conversation ID from the intent, fall back to the widget's active one.
        val continuedConversationId = intent.getLongExtra(WidgetVoiceService.EXTRA_CONVERSATION_ID, -1L)
            .takeIf { it > 0L }
            ?: WidgetPrefs.getActiveConversationId(this)

        setContent {
            val systemUriHandler = LocalUriHandler.current
            val inAppUriHandler = remember(systemUriHandler) {
                object : UriHandler {
                    override fun openUri(uri: String) {
                        val normalized = uri.trim()
                        val lower = normalized.lowercase()
                        if (lower.startsWith("http://") || lower.startsWith("https://")) {
                            startActivity(
                                Intent(this@WidgetChatActivity, WidgetWebActivity::class.java).apply {
                                    addFlags(
                                        Intent.FLAG_ACTIVITY_NEW_TASK or
                                            Intent.FLAG_ACTIVITY_CLEAR_TOP or
                                            Intent.FLAG_ACTIVITY_SINGLE_TOP,
                                    )
                                    putExtra(WidgetWebActivity.EXTRA_INITIAL_QUERY, normalized)
                                },
                            )
                        } else {
                            systemUriHandler.openUri(uri)
                        }
                    }
                }
            }

            CompositionLocalProvider(LocalUriHandler provides inAppUriHandler) {
            OptimalXTheme {
                val colors = LocalOptimalXColors.current

                val chatViewModel: EidosChatViewModel = viewModel(
                    factory = viewModelFactory {
                        initializer {
                            val app = this[androidx.lifecycle.ViewModelProvider.AndroidViewModelFactory.APPLICATION_KEY]!!
                            EidosChatViewModel(app as Application, EidosChatEntrySurface.WIDGET)
                        }
                    }
                )

                val voiceController: VoiceController = viewModel(
                    factory = viewModelFactory {
                        initializer {
                            val app = this[androidx.lifecycle.ViewModelProvider.AndroidViewModelFactory.APPLICATION_KEY]!!
                            VoiceController(app as Application)
                        }
                    }
                )

                val chatLifecycleOwner = LocalLifecycleOwner.current
                DisposableEffect(chatLifecycleOwner, voiceController) {
                    val observer = LifecycleEventObserver { _, event ->
                        if (event == Lifecycle.Event.ON_STOP) {
                            voiceController.stopSession()
                        }
                    }
                    chatLifecycleOwner.lifecycle.addObserver(observer)
                    onDispose {
                        chatLifecycleOwner.lifecycle.removeObserver(observer)
                        voiceController.stopSession()
                    }
                }

                LaunchedEffect(continuedConversationId) {
                    if (continuedConversationId != null) {
                        chatViewModel.loadConversation(continuedConversationId)
                    } else {
                        chatViewModel.setGeneralScope()
                    }
                }

                DisposableEffect(chatViewModel) {
                    chatViewModel.setChatUiVisible(true)
                    onDispose { chatViewModel.setChatUiVisible(false) }
                }

                val appForBus = LocalContext.current.applicationContext as OptimalXApplication
                LaunchedEffect(appForBus, chatViewModel) {
                    appForBus.widgetChatSessionResetEvents.collect {
                        chatViewModel.newChat()
                    }
                }

                // Auto-start mic when launched from widget Mic button
                val autoStartMic = intent.getBooleanExtra(EXTRA_AUTO_START_MIC, false)
                LaunchedEffect(Unit) {
                    if (autoStartMic) {
                        val granted = checkSelfPermission(Manifest.permission.RECORD_AUDIO) ==
                                PackageManager.PERMISSION_GRANTED
                        if (granted) {
                            voiceController.startListening(existingText = "") { text ->
                                chatViewModel.setInput(text)
                            }
                        } else {
                            onPermissionResult = { ok ->
                                if (ok) voiceController.startListening(existingText = "") { text ->
                                    chatViewModel.setInput(text)
                                }
                            }
                            permissionLauncher.launch(Manifest.permission.RECORD_AUDIO)
                        }
                    }
                }

                val messages by chatViewModel.messages.collectAsState()
                val input by chatViewModel.input.collectAsState()
                val isSending by chatViewModel.isSending.collectAsState()
                val readAloud by chatViewModel.readAloud.collectAsState()
                val readAloudMicPassback by chatViewModel.readAloudMicPassback.collectAsState()
                val readAloudInfoDismissed by chatViewModel.readAloudInfoDismissed.collectAsState()
                val micUseWhisperApi by chatViewModel.micUseWhisperApi.collectAsState()
                val hasOpenAiApiKey by chatViewModel.hasOpenAiApiKey.collectAsState()
                val conversationMemoryLabel by chatViewModel.conversationMemoryLabel.collectAsState()
                val rereadMessageId by chatViewModel.rereadMessageId.collectAsState()
                val conversationSummaries by chatViewModel.conversationSummaries.collectAsState()
                val historyDirectoryOptions by chatViewModel.historyDirectoryOptions.collectAsState()
                val selectedHistoryDirectory by chatViewModel.selectedHistoryDirectory.collectAsState()
                val historyContextLabel by chatViewModel.historyContextLabel.collectAsState()
                val historyLocationTargets by chatViewModel.historyLocationTargets.collectAsState()
                val historyParentTargets by chatViewModel.historyParentTargets.collectAsState()
                val selectedHistoryParentId by chatViewModel.selectedHistoryParentId.collectAsState()
                val selectedHistoryLocationId by chatViewModel.selectedHistoryLocationId.collectAsState()
                val hasActiveConversation by chatViewModel.hasActiveConversation.collectAsState()
                val chatScopeLabel by chatViewModel.chatScopeLabel.collectAsState()
                val pendingConfirmation by chatViewModel.pendingConfirmation.collectAsState()

                val clipboardManager = LocalClipboardManager.current
                val focusManager = LocalFocusManager.current

                var editingMessage by remember { mutableStateOf<com.example.optimalx.ui.eidos.EidosUiMessage?>(null) }
                var editDraft by remember { mutableStateOf("") }
                var showHistorySheet by remember { mutableStateOf(false) }
                var showMoveHereConfirm by remember { mutableStateOf(false) }
                var renamingConversationId by remember { mutableStateOf<Long?>(null) }
                var renameDraft by remember { mutableStateOf("") }
                var expandedReasoningIds by remember { mutableStateOf(setOf<Long>()) }
                var showClearRecordingDialog by remember { mutableStateOf(false) }

                val sessionState by voiceController.sessionState.collectAsState()
                val liveTranscript by voiceController.liveTranscript.collectAsState()
                val usesWhisperCapture by voiceController.usesWhisperCapture.collectAsState()
                val isRestarting by voiceController.isRestarting.collectAsState()

                val isListening = sessionState == VoiceSessionState.LISTENING
                val isTranscribing = sessionState == VoiceSessionState.TRANSCRIBING
                val isPaused = sessionState == VoiceSessionState.PAUSED
                val isSpeaking = sessionState == VoiceSessionState.SPEAKING
                val isCapturingVoice = if (usesWhisperCapture) isListening || isTranscribing else isListening

                LaunchedEffect(micUseWhisperApi) {
                    chatViewModel.refreshOpenAiKeyPresence()
                    voiceController.applyMicEnginePreferenceFromSettings()
                }

                // TTS for new assistant messages (suppressed for messages loaded on open).
                var lastSeenMessageCount by remember { mutableIntStateOf(0) }
                var initialLoadDone by remember { mutableStateOf(false) }
                LaunchedEffect(messages.size) {
                    if (!initialLoadDone) {
                        lastSeenMessageCount = messages.size
                        if (messages.isNotEmpty()) initialLoadDone = true
                    } else if (messages.size > lastSeenMessageCount) {
                        val last = messages.lastOrNull()
                        if (last != null && last.role == EidosRole.ASSISTANT && readAloud) {
                            voiceController.speakResponse(
                                text = last.text,
                                thenListen = readAloudMicPassback,
                                onListenResult = { text -> chatViewModel.setInput(text) },
                            )
                        }
                        lastSeenMessageCount = messages.size
                    } else {
                        lastSeenMessageCount = messages.size
                    }
                }

                val listState = rememberLazyListState()
                val scope = rememberCoroutineScope()
                val isNearBottom by remember {
                    derivedStateOf {
                        val layout = listState.layoutInfo
                        val lastVisible = layout.visibleItemsInfo.lastOrNull()
                        if (lastVisible == null || layout.totalItemsCount == 0) {
                            true
                        } else {
                            lastVisible.index >= layout.totalItemsCount - 2
                        }
                    }
                }
                var scrollAnchorFirstMessageId by remember { mutableStateOf<Long?>(null) }
                var scrollAnchorMessageCount by remember { mutableIntStateOf(-1) }
                val lastMessageTextLength = messages.lastOrNull()?.text?.length ?: 0
                LaunchedEffect(
                    messages.size,
                    messages.firstOrNull()?.id,
                    lastMessageTextLength,
                    listState.layoutInfo.totalItemsCount,
                ) {
                    if (messages.isEmpty()) return@LaunchedEffect
                    val firstId = messages.firstOrNull()?.id ?: return@LaunchedEffect
                    val conversationChanged =
                        scrollAnchorFirstMessageId != null && scrollAnchorFirstMessageId != firstId
                    val pendingInitialScroll = scrollAnchorFirstMessageId == null
                    val newMessagesAppended = messages.size > scrollAnchorMessageCount
                    val shouldScroll = conversationChanged ||
                        pendingInitialScroll ||
                        (newMessagesAppended && isNearBottom) ||
                        (lastMessageTextLength > 0 && isNearBottom)
                    if (!shouldScroll) return@LaunchedEffect
                    if (listState.layoutInfo.totalItemsCount < messages.size) return@LaunchedEffect
                    listState.scrollToItem(messages.lastIndex)
                    scrollAnchorFirstMessageId = firstId
                    scrollAnchorMessageCount = messages.size
                }

                Column(
                    modifier = Modifier
                        .fillMaxSize()
                        .background(colors.background)
                        .statusBarsPadding()
                        .navigationBarsPadding()
                        .imePadding(),
                ) {
                    ChatTopBar(
                        onBack = {
                            voiceController.stopSession()
                            finish()
                        },
                        scopeLabel = chatScopeLabel,
                        workshopPhaseLabel = null,
                        memoryDepthLabel = conversationMemoryLabel,
                        onMemoryDepthClick = { chatViewModel.cycleConversationMemoryDepth() },
                        hasActiveConversation = hasActiveConversation,
                        isSending = isSending,
                        onHistoryClick = {
                            chatViewModel.openConversationBrowser()
                            showHistorySheet = true
                        },
                        onNewChatClick = { chatViewModel.newChat() },
                        onMoveClick = { showMoveHereConfirm = true },
                        onStopClick = { chatViewModel.cancelActiveSend() },
                        readAloud = readAloud,
                        readAloudMicPassback = readAloudMicPassback,
                        onReadAloudChange = chatViewModel::setReadAloud,
                        onMicPassbackChange = chatViewModel::setReadAloudMicPassback,
                        readAloudInfoDismissed = readAloudInfoDismissed,
                        onReadAloudInfoDismissedChange = chatViewModel::setReadAloudInfoDismissed,
                        micUseWhisperApi = micUseWhisperApi,
                        hasOpenAiApiKey = hasOpenAiApiKey,
                        onMicUseWhisperApiChange = chatViewModel::setMicUseWhisperApi,
                        onOpenChatSettings = chatViewModel::refreshOpenAiKeyPresence,
                    )

                    // Message list
                    Box(
                        modifier = Modifier
                            .weight(1f)
                            .fillMaxWidth()
                            .clickable(
                                indication = null,
                                interactionSource = remember { MutableInteractionSource() },
                            ) { focusManager.clearFocus() },
                    ) {
                        LazyColumn(
                            state = listState,
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(horizontal = 14.dp, vertical = 8.dp),
                            verticalArrangement = Arrangement.spacedBy(8.dp),
                        ) {
                            items(messages, key = { it.id }) { message ->
                            val isUser = message.role == EidosRole.USER
                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                horizontalArrangement = if (isUser) Arrangement.End else Arrangement.Start,
                            ) {
                                val bubbleShape = RoundedCornerShape(14.dp)
                                val bubbleMod = if (isUser) {
                                    Modifier
                                        .clip(bubbleShape)
                                        .background(colors.messageBubbleUser)
                                } else {
                                    Modifier
                                        .border(1.dp, colors.messageBubbleEidosBorder, bubbleShape)
                                        .clip(bubbleShape)
                                        .background(
                                            Brush.verticalGradient(
                                                listOf(colors.accentDim, colors.surface2)
                                            )
                                        )
                                }
                                Column(
                                    modifier = bubbleMod
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
                                    MarkdownRichText(
                                        text = message.text,
                                        style = TextStyle(
                                            color = colors.textPrimary,
                                            fontFamily = DmSansFamily,
                                            fontSize = 14.sp,
                                            lineHeight = 19.sp,
                                        ),
                                        selectable = true,
                                    )
                                    ChatMessageBubbleFooter(timeLabel = message.timeLabel) {
                                        if (!isUser) {
                                            val isPlaying = message.id == rereadMessageId
                                            IconButton(
                                                onClick = {
                                                    if (isPlaying) {
                                                        voiceController.stopSession()
                                                        chatViewModel.setRereadMessageId(null)
                                                    } else {
                                                        voiceController.stopSession()
                                                        chatViewModel.setRereadMessageId(message.id)
                                                        voiceController.speakResponse(
                                                            text = message.text,
                                                            thenListen = false,
                                                            onDone = { chatViewModel.setRereadMessageId(null) },
                                                        )
                                                    }
                                                },
                                                modifier = Modifier.size(28.dp),
                                            ) {
                                                Icon(
                                                    imageVector = Icons.AutoMirrored.Filled.VolumeUp,
                                                    contentDescription = if (isPlaying) "Stop" else "Read aloud",
                                                    tint = if (isPlaying) colors.accent else colors.textDim,
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
                        }

                        if (!isNearBottom && messages.isNotEmpty()) {
                            IconButton(
                                onClick = { scope.launch { listState.animateScrollToItem(messages.lastIndex) } },
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

                    ChatComposerBar(
                        input = input,
                        onInputChange = chatViewModel::setInput,
                        onSend = {
                            voiceController.commitVoiceThen(mergeBaseText = input) {
                                chatViewModel.sendMessage()
                            }
                        },
                        onMicClick = {
                            when (sessionState) {
                                VoiceSessionState.LISTENING,
                                VoiceSessionState.PAUSED,
                                -> voiceController.handleComposerMicTap(input)
                                VoiceSessionState.SPEAKING -> voiceController.stopSession()
                                VoiceSessionState.IDLE -> {
                                    val granted = ContextCompat.checkSelfPermission(
                                        this@WidgetChatActivity,
                                        Manifest.permission.RECORD_AUDIO,
                                    ) == PackageManager.PERMISSION_GRANTED
                                    if (granted) {
                                        voiceController.startListening(existingText = input) { text ->
                                            chatViewModel.setInput(text)
                                        }
                                    } else {
                                        onPermissionResult = { g ->
                                            if (g) voiceController.startListening(existingText = input) { text ->
                                                chatViewModel.setInput(text)
                                            }
                                        }
                                        permissionLauncher.launch(Manifest.permission.RECORD_AUDIO)
                                    }
                                }
                                else -> Unit
                            }
                        },
                        onDiscardRecording = {
                            if (usesWhisperCapture) showClearRecordingDialog = true
                        },
                        onStopClick = { chatViewModel.cancelActiveSend() },
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
                    )
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
                    com.example.optimalx.ui.eidos.ConversationHistorySheet(
                        summaries = conversationSummaries,
                        directories = historyDirectoryOptions,
                        selectedDirectory = selectedHistoryDirectory,
                        onDirectorySelected = chatViewModel::setHistoryDirectory,
                        parentTargets = historyParentTargets,
                        selectedParentId = selectedHistoryParentId,
                        onParentSelected = chatViewModel::setHistoryParentTarget,
                        onOpenSubfolders = chatViewModel::openSubfolderDirectory,
                        onOpenParentChats = chatViewModel::openParentDirectoryChats,
                        locationTargets = historyLocationTargets,
                        selectedLocationId = selectedHistoryLocationId,
                        onLocationSelected = chatViewModel::setHistoryLocationTarget,
                        contextLabel = historyContextLabel,
                        onSelect = { id ->
                            chatViewModel.switchConversation(id)
                            WidgetPrefs.setActiveConversationId(this@WidgetChatActivity, id)
                        },
                        onRename = { id, currentTitle ->
                            renamingConversationId = id
                            renameDraft = currentTitle
                        },
                        onDismiss = { showHistorySheet = false },
                        isRequestInFlight = isSending,
                        onStopRequest = { chatViewModel.cancelActiveSend() },
                    )
                }

                // Edit message dialog
                editingMessage?.let { msg ->
                    val colors2 = colors
                    AlertDialog(
                        onDismissRequest = { editingMessage = null },
                        title = {
                            Text("Edit message", fontFamily = DmSansFamily, color = colors2.textPrimary)
                        },
                        text = {
                            TextField(
                                value = editDraft,
                                onValueChange = { editDraft = it },
                                colors = TextFieldDefaults.colors(
                                    focusedContainerColor = colors2.surface2,
                                    unfocusedContainerColor = colors2.surface2,
                                    focusedIndicatorColor = colors2.accent,
                                    unfocusedIndicatorColor = colors2.border,
                                    cursorColor = colors2.accent,
                                    focusedTextColor = colors2.textPrimary,
                                    unfocusedTextColor = colors2.textPrimary,
                                ),
                            )
                        },
                        confirmButton = {
                            TextButton(onClick = {
                                chatViewModel.editMessage(msg.id, editDraft)
                                editingMessage = null
                            }) {
                                Text("Save", color = colors2.accent, fontFamily = DmSansFamily)
                            }
                        },
                        dismissButton = {
                            TextButton(onClick = { editingMessage = null }) {
                                Text("Cancel", color = colors2.textMid, fontFamily = DmSansFamily)
                            }
                        },
                        containerColor = colors2.background,
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
                                chatViewModel.moveActiveConversationToCurrentScope()
                            }) {
                                Text("Yes", color = colors.accent, fontFamily = DmSansFamily)
                            }
                        },
                        dismissButton = {
                            TextButton(onClick = { showMoveHereConfirm = false }) {
                                Text("No", color = colors.textMid, fontFamily = DmSansFamily)
                            }
                        },
                        containerColor = colors.background,
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
                                chatViewModel.renameConversation(conversationId, renameDraft)
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
                        containerColor = colors.background,
                    )
                }

                pendingConfirmation?.let { req ->
                    val friendlyName = req.toolName.replace('_', ' ').replaceFirstChar { it.uppercase() }
                    AlertDialog(
                        onDismissRequest = { chatViewModel.denyConfirmation() },
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
                                        fontFamily = DmMonoFamily,
                                        fontSize = 12.sp,
                                    )
                                }
                            }
                        },
                        confirmButton = {
                            TextButton(onClick = { chatViewModel.approveConfirmation() }) {
                                Text("Allow", color = colors.accent, fontFamily = DmSansFamily)
                            }
                        },
                        dismissButton = {
                            TextButton(onClick = { chatViewModel.denyConfirmation() }) {
                                Text("Deny", color = colors.textMid, fontFamily = DmSansFamily)
                            }
                        },
                        containerColor = colors.background,
                    )
                }
            }
            }
        }
    }
}
