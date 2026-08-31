package com.example.optimalx.ui.eidos

import android.app.Application
import android.net.Uri
import androidx.datastore.preferences.core.edit
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.DefaultLifecycleObserver
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.ProcessLifecycleOwner
import androidx.lifecycle.viewModelScope
import com.example.optimalx.OptimalXApplication
import com.example.optimalx.data.conversation.buildConversationTitleFromText
import com.example.optimalx.data.conversation.looksLikeAutoTimestampTitle
import com.example.optimalx.data.db.SystemFolderNames
import com.example.optimalx.data.litert.LitertLmDefaults
import com.example.optimalx.data.litert.applyLitertEngineWarmState
import com.example.optimalx.data.eidos.EidosActiveSendRegistry
import com.example.optimalx.data.eidos.EidosThinkingLevel
import com.example.optimalx.data.eidos.EidosChatSendWorker
import com.example.optimalx.data.eidos.EidosReplyNotificationTarget
import com.example.optimalx.data.eidos.prompt.EidosIdentityPrompt
import com.example.optimalx.data.eidos.prompt.toPromptEntrySurface
import com.example.optimalx.data.eidos.WorkshopEidosModeResolver
import com.example.optimalx.data.eidos.WorkshopIntakeSummary
import com.example.optimalx.data.eidos.ConversationOutboundHistory
import com.example.optimalx.data.eidos.model.ConfirmationHandler
import com.example.optimalx.data.eidos.model.EidosMessage
import com.example.optimalx.data.eidos.EidosNavigationCodec
import com.example.optimalx.data.eidos.EidosNavigationTarget
import com.example.optimalx.data.eidos.ChatVisionAttachment
import com.example.optimalx.data.eidos.ChatVisionAttachmentCodec
import com.example.optimalx.data.eidos.ChatVisionImageStore
import com.example.optimalx.data.eidos.model.EidosRole
import com.example.optimalx.data.eidos.model.EidosStreamListener
import com.example.optimalx.data.eidos.model.EidosStreamUpdate
import com.example.optimalx.data.eidos.model.persistableReasoningContent
import com.example.optimalx.data.model.ChatMessage
import com.example.optimalx.data.model.Conversation
import com.example.optimalx.data.eidos.PanelPlatformSpec
import com.example.optimalx.data.eidos.WorkshopDocAlignScope
import com.example.optimalx.data.eidos.WorkshopBuildKickoff
import com.example.optimalx.data.eidos.WorkshopEidosMode
import com.example.optimalx.data.eidos.WorkshopProjectPhase
import com.example.optimalx.data.eidos.WorkshopProjectSummaryAutomation
import com.example.optimalx.data.eidos.WorkshopUpdateSection
import com.example.optimalx.data.imagestudio.ImageStudioDraft
import com.example.optimalx.data.imagestudio.ImageStudioDraftParser
import com.example.optimalx.data.model.ConversationScopes
import com.example.optimalx.data.preferences.WorkshopProjectPreferences
import com.example.optimalx.ui.web.displayWebSearchTitle
import com.example.optimalx.ui.web.normalizeWebSearchKey
import com.example.optimalx.ui.workshop.WorkshopUpdateCompletion
import com.example.optimalx.data.preferences.ChatSessionPointers
import com.example.optimalx.data.preferences.ApiKeyNames
import com.example.optimalx.data.preferences.EncryptedSettingKeys
import com.example.optimalx.data.preferences.SettingsDefaults
import com.example.optimalx.data.preferences.SettingsKeys
import com.example.optimalx.data.preferences.getEncryptedPrefs
import com.example.optimalx.data.preferences.settingsDataStore
import com.example.optimalx.widget.WidgetPrefs
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.withContext
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter

private val conversationTitleFormatter =
    DateTimeFormatter.ofPattern("yyyy-MM-dd — h:mm a").withZone(ZoneId.systemDefault())

private val messageTimeFormatter =
    DateTimeFormatter.ofPattern("hh:mm a").withZone(ZoneId.systemDefault())

// ── Scope ─────────────────────────────────────────────────────────────────────

sealed class ConversationScope {
    object General : ConversationScope()
    data class ParentFolder(val id: Long) : ConversationScope()
    data class Subfolder(val id: Long) : ConversationScope()
    data class Workshop(val subfolderId: Long) : ConversationScope()
    data class QuickNotesRoot(val parentId: Long) : ConversationScope()
    data class QuickNotesDay(val subfolderId: Long) : ConversationScope()
    data class WebEditor(val subfolderId: Long, val searchKey: String? = null) : ConversationScope()
    object WebWidget : ConversationScope()
    object DumpEdit : ConversationScope()
    object PanelGallery : ConversationScope()
    data class PanelRunner(val subfolderId: Long) : ConversationScope()
    data class ImageStudio(val hub: Boolean, val saveSubfolderId: Long) : ConversationScope()
}

data class ImageStudioDraftHandoff(
    val saveSubfolderId: Long,
    val hub: Boolean,
    val draft: ImageStudioDraft,
)

enum class ConversationDirectory {
    RECENT,
    /** Conversations in the folder/subfolder the user opened chat from. */
    HERE,
    GENERAL,
    PARENT,
    SUBFOLDER,
}

// ── UI models ──────────────────────────────────────────────────────────────────

data class EidosUiMessage(
    val id: Long,           // ChatMessage.id (used for reread tracking)
    val role: EidosRole,
    val text: String,
    val timeLabel: String,
    val reasoningText: String? = null,
    val navigationTargets: List<EidosNavigationTarget> = emptyList(),
    val imageAttachment: ChatVisionAttachment? = null,
)

data class ConversationSummary(
    val id: Long,
    val title: String,
    val snippet: String,    // first message content, max 60 chars
    val isActive: Boolean,
)

data class ConversationDirectoryOption(
    val directory: ConversationDirectory,
    val label: String,
)

data class ConversationLocationTarget(
    val id: Long,
    val label: String,
)

data class ToolConfirmationRequest(
    val toolName: String,
    val argsJson: String,
    val deferred: CompletableDeferred<Boolean>,
)

/** Which UI owns this [EidosChatViewModel] instance (affects General restore + prefs writes). */
enum class EidosChatEntrySurface {
    MAIN_APP,
    WIDGET,
}

// ── ViewModel ─────────────────────────────────────────────────────────────────

@OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)
class EidosChatViewModel(
    app: Application,
    private val entrySurface: EidosChatEntrySurface = EidosChatEntrySurface.MAIN_APP,
) : AndroidViewModel(app) {

    private val appRef = app as OptimalXApplication
    private val db = appRef.database
    private val api = appRef.eidosApiClient
    private val semanticSync = appRef.semanticSyncService

    // ── Tool confirmation ─────────────────────────────────────────────────────
    private val _pendingConfirmation = MutableStateFlow<ToolConfirmationRequest?>(null)
    val pendingConfirmation: StateFlow<ToolConfirmationRequest?> = _pendingConfirmation.asStateFlow()

    private val confirmationHandler = object : ConfirmationHandler {
        override suspend fun confirm(toolName: String, argumentsJson: String): Boolean {
            if (!chatUiVisible) return true
            val deferred = CompletableDeferred<Boolean>()
            _pendingConfirmation.value = ToolConfirmationRequest(toolName, argumentsJson, deferred)
            return try {
                deferred.await()
            } finally {
                _pendingConfirmation.value = null
            }
        }
    }

    fun approveConfirmation() {
        _pendingConfirmation.value?.deferred?.complete(true)
    }

    fun denyConfirmation() {
        _pendingConfirmation.value?.deferred?.complete(false)
    }

    // ── Observable state ──────────────────────────────────────────────────────

    private val _messages = MutableStateFlow<List<EidosUiMessage>>(emptyList())
    val messages: StateFlow<List<EidosUiMessage>> = _messages.asStateFlow()

    private val _input = MutableStateFlow("")
    val input: StateFlow<String> = _input.asStateFlow()

    private val _pendingImage = MutableStateFlow<ChatVisionAttachment?>(null)
    val pendingImage: StateFlow<ChatVisionAttachment?> = _pendingImage.asStateFlow()

    private val _isSending = MutableStateFlow(false)
    val isSending: StateFlow<Boolean> = _isSending.asStateFlow()

    val activeProvider: StateFlow<String> = appRef.settingsDataStore.data
        .map { it[SettingsKeys.ACTIVE_PROVIDER] ?: SettingsDefaults.ACTIVE_PROVIDER }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), SettingsDefaults.ACTIVE_PROVIDER)

    private val _streamPreview = MutableStateFlow<EidosStreamUpdate?>(null)
    val streamPreview: StateFlow<EidosStreamUpdate?> = _streamPreview.asStateFlow()

    /** Kimi thinking models may take a long time before the first token — show activity in chat. */
    val showKimiThinkingIndicator: StateFlow<Boolean> = combine(
        _isSending,
        activeProvider,
        _streamPreview,
        _messages,
    ) { sending, provider, preview, msgs ->
        if (!sending || provider != "kimi") return@combine false
        val streaming = preview != null &&
            (preview.contentText.isNotBlank() || preview.reasoningText.isNotBlank())
        val awaitingReply = msgs.lastOrNull()?.role == EidosRole.USER
        streaming || awaitingReply
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), false)

    private val _toastMessage = MutableSharedFlow<String>(extraBufferCapacity = 4)
    val toastMessage: SharedFlow<String> = _toastMessage.asSharedFlow()

    fun postToast(message: String) {
        viewModelScope.launch {
            _toastMessage.emit(message)
        }
    }

    /** In-flight [sendMessage] or [editMessage] API exchange — cancel to stop HTTP and tool loops. */
    private var apiExchangeJob: Job? = null
    private var suppressStoppedReplyOnCancellation = false
    /** Whether the Eidos chat sheet (or embedded chat panel) is visible for tool confirmations. */
    private var chatUiVisible = false

    /** WebPanel Eidos overlay — held in the VM because [rememberSaveable] inside [HorizontalPager] does not survive rotation. */
    private val _webPanelEidosSheetOpen = MutableStateFlow(false)
    val webPanelEidosSheetOpen: StateFlow<Boolean> = _webPanelEidosSheetOpen.asStateFlow()

    init {
        ProcessLifecycleOwner.get().lifecycle.addObserver(object : DefaultLifecycleObserver {
            override fun onStart(owner: LifecycleOwner) {
                syncActiveConversationFromDatabase()
            }
        })

        // When any background send finishes, if it was for our active conversation,
        // pull the new reply from the DB. Survives ViewModel recreation.
        viewModelScope.launch {
            EidosActiveSendRegistry.completionEvents.collect { conversationId ->
                if (conversationId != activeConversationId) return@collect
                if (!EidosActiveSendRegistry.isActive(conversationId)) {
                    _isSending.value = false
                }
                syncActiveConversationFromDatabase()
            }
        }

        // Mirror in-flight registry status into _isSending so a freshly-recreated VM still
        // shows the spinner/Stop button while the original job continues in app scope.
        viewModelScope.launch {
            EidosActiveSendRegistry.activeConversationIds.collect { active ->
                val convId = activeConversationId ?: return@collect
                val isActive = convId in active
                if (isActive && !_isSending.value) _isSending.value = true
                if (!isActive && _isSending.value && apiExchangeJob == null) {
                    _isSending.value = false
                }
            }
        }

        viewModelScope.launch {
            while (true) {
                kotlinx.coroutines.delay(180_000L)
                if (!_isSending.value) continue
                val convId = activeConversationId ?: continue
                if (EidosActiveSendRegistry.isActive(convId)) continue
                if (apiExchangeJob?.isActive == true) continue
                _isSending.value = false
            }
        }
    }

    /** Called when the chat sheet opens/closes so sends can continue with auto-approved tools off-screen. */
    fun setChatUiVisible(visible: Boolean) {
        chatUiVisible = visible
    }

    fun setWebPanelEidosSheetOpen(open: Boolean) {
        if (_webPanelEidosSheetOpen.value == open) return
        _webPanelEidosSheetOpen.value = open
        setChatUiVisible(open)
        if (open) {
            viewModelScope.launch { api.preloadKimiFormulaTools() }
        }
    }

    fun cancelActiveSend() {
        val job = apiExchangeJob
        previousResponseId = null
        job?.cancel(CancellationException("User stopped"))
        activeConversationId?.let { EidosActiveSendRegistry.cancel(it) }
        api.resetConnections()
        finishSendExchange(activeConversationId, job)
    }

    val readAloud: StateFlow<Boolean> = app.settingsDataStore.data
        .map { it[SettingsKeys.READ_ALOUD] ?: SettingsDefaults.READ_ALOUD }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), SettingsDefaults.READ_ALOUD)

    val readAloudMicPassback: StateFlow<Boolean> = app.settingsDataStore.data
        .map { it[SettingsKeys.READ_ALOUD_MIC_PASSBACK] ?: SettingsDefaults.READ_ALOUD_MIC_PASSBACK }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), SettingsDefaults.READ_ALOUD_MIC_PASSBACK)

    val readAloudInfoDismissed: StateFlow<Boolean> = app.settingsDataStore.data
        .map { it[SettingsKeys.READ_ALOUD_INFO_DISMISSED] ?: SettingsDefaults.READ_ALOUD_INFO_DISMISSED }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), SettingsDefaults.READ_ALOUD_INFO_DISMISSED)

    /** Local Gemma tools vs chat-only. Ignored for cloud providers. */
    val localGemmaToolsEnabled: StateFlow<Boolean> = app.settingsDataStore.data
        .map {
            it[SettingsKeys.LOCAL_GEMMA_TOOLS_ENABLED] ?: SettingsDefaults.LOCAL_GEMMA_TOOLS_ENABLED
        }
        .stateIn(
            viewModelScope,
            SharingStarted.WhileSubscribed(5000),
            SettingsDefaults.LOCAL_GEMMA_TOOLS_ENABLED,
        )

    val micUseWhisperApi: StateFlow<Boolean> = app.settingsDataStore.data
        .map { it[SettingsKeys.MIC_USE_WHISPER_API] ?: SettingsDefaults.MIC_USE_WHISPER_API }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), SettingsDefaults.MIC_USE_WHISPER_API)

    val micUseLocalGemmaScribe: StateFlow<Boolean> = app.settingsDataStore.data
        .map { it[SettingsKeys.MIC_USE_LOCAL_GEMMA_SCRIBE] ?: SettingsDefaults.MIC_USE_LOCAL_GEMMA_SCRIBE }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), SettingsDefaults.MIC_USE_LOCAL_GEMMA_SCRIBE)

    val eidosThinkingLevel: StateFlow<EidosThinkingLevel> = app.settingsDataStore.data
        .map {
            EidosThinkingLevel.fromWire(
                it[SettingsKeys.EIDOS_THINKING_LEVEL] ?: SettingsDefaults.EIDOS_THINKING_LEVEL,
            )
        }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), EidosThinkingLevel.DEFAULT)

    private val _hasOpenAiApiKey = MutableStateFlow(readHasOpenAiApiKey())
    val hasOpenAiApiKey: StateFlow<Boolean> = _hasOpenAiApiKey.asStateFlow()

    private val _conversationSummaries = MutableStateFlow<List<ConversationSummary>>(emptyList())
    val conversationSummaries: StateFlow<List<ConversationSummary>> = _conversationSummaries.asStateFlow()

    private val _historyDirectoryOptions = MutableStateFlow(
        listOf(ConversationDirectoryOption(ConversationDirectory.RECENT, "Recent"))
    )
    val historyDirectoryOptions: StateFlow<List<ConversationDirectoryOption>> = _historyDirectoryOptions.asStateFlow()

    private val _selectedHistoryDirectory = MutableStateFlow(ConversationDirectory.RECENT)
    val selectedHistoryDirectory: StateFlow<ConversationDirectory> = _selectedHistoryDirectory.asStateFlow()

    private val _historyContextLabel = MutableStateFlow("Chats / Recent (Last 5)")
    val historyContextLabel: StateFlow<String> = _historyContextLabel.asStateFlow()

    private val _historyLocationTargets = MutableStateFlow<List<ConversationLocationTarget>>(emptyList())
    val historyLocationTargets: StateFlow<List<ConversationLocationTarget>> = _historyLocationTargets.asStateFlow()

    private val _historyParentTargets = MutableStateFlow<List<ConversationLocationTarget>>(emptyList())
    val historyParentTargets: StateFlow<List<ConversationLocationTarget>> = _historyParentTargets.asStateFlow()

    private val _selectedHistoryParentId = MutableStateFlow<Long?>(null)
    val selectedHistoryParentId: StateFlow<Long?> = _selectedHistoryParentId.asStateFlow()

    private val _selectedHistoryLocationId = MutableStateFlow<Long?>(null)
    val selectedHistoryLocationId: StateFlow<Long?> = _selectedHistoryLocationId.asStateFlow()

    private val _rereadMessageId = MutableStateFlow<Long?>(null)
    val rereadMessageId: StateFlow<Long?> = _rereadMessageId.asStateFlow()

    private val _hasActiveConversation = MutableStateFlow(false)
    val hasActiveConversation: StateFlow<Boolean> = _hasActiveConversation.asStateFlow()

    private val _chatScopeLabel = MutableStateFlow("General")
    val chatScopeLabel: StateFlow<String> = _chatScopeLabel.asStateFlow()

    private val _isImageStudioScope = MutableStateFlow(false)
    val isImageStudioScope: StateFlow<Boolean> = _isImageStudioScope.asStateFlow()

    private val _pendingImageStudioDraftHandoff = MutableStateFlow<ImageStudioDraftHandoff?>(null)
    val pendingImageStudioDraftHandoff: StateFlow<ImageStudioDraftHandoff?> =
        _pendingImageStudioDraftHandoff.asStateFlow()

    // ── Session state ─────────────────────────────────────────────────────────

    /** Page/surface the user opened chat from — drives Move Here, New Chat, and LLM context. */
    private var viewedScope: ConversationScope = ConversationScope.General
    /** Scope of the loaded conversation — header label and DB home until Move Here. */
    private var currentScope: ConversationScope = ConversationScope.General
    private var activeConversationId: Long? = null
    private var selectedParentDirectoryId: Long? = null
    private var quickNotesDayLabel: String? = null
    private var panelRunnerScopeLabel: String? = null
    private var imageStudioScopeLabel: String? = null
    private var imageStudioHubMode: Boolean = false
    private var imageStudioActivePreviewId: Long? = null
    private var imageStudioActivePreviewName: String? = null
    /** In-memory provider response chain ID — cleared on scope/conversation change. */
    private var previousResponseId: String? = null

    private var activeWebSearchKey: String? = null
    private var activeWebSearchDisplay: String? = null

    /**
     * Bumped whenever the user explicitly picks a new thread, starts New Chat, or changes scope.
     * In-flight [restoreLastConversation] / [loadConversationInternal] calls with a stale epoch are ignored
     * so pointer restore cannot overwrite a conversation opened from the directory list.
     */
    private var conversationLoadEpoch = 0

    private fun beginConversationLoad(): Int {
        conversationLoadEpoch++
        return conversationLoadEpoch
    }

    private fun isConversationLoadCurrent(loadEpoch: Int): Boolean = loadEpoch == conversationLoadEpoch

    /** Clear stale bubbles immediately while the picked thread loads from the database. */
    private fun prepareExplicitConversationSwitch(conversationId: Long) {
        activeConversationId = conversationId
        _hasActiveConversation.value = true
        previousResponseId = null
        _messages.value = emptyList()
        _streamPreview.value = null
    }

    private val _restrictQuickNotesChatToolbar = MutableStateFlow(false)
    val restrictQuickNotesChatToolbar: StateFlow<Boolean> = _restrictQuickNotesChatToolbar.asStateFlow()

    private val _restrictWebChatToolbar = MutableStateFlow(false)
    val restrictWebChatToolbar: StateFlow<Boolean> = _restrictWebChatToolbar.asStateFlow()

    /** Subfolder id for the Quick Notes inbox when [restrictQuickNotesChatToolbar] is true; otherwise null. */
    private val _quickNotesInboxSubfolderId = MutableStateFlow<Long?>(null)
    val quickNotesInboxSubfolderId: StateFlow<Long?> = _quickNotesInboxSubfolderId.asStateFlow()

    /** (subfolderId, description) from [EditorScreen] pager — included in system prompt for subfolder chat. */
    private val _subfolderEditorSurfaceHint = MutableStateFlow<Pair<Long, String>?>(null)

    private val _workshopOpenFileName = MutableStateFlow<String?>(null)
    private val _workshopOpenFileContent = MutableStateFlow<String?>(null)

    private val _workshopScopeSubfolderId = MutableStateFlow<Long?>(null)
    val workshopScopeSubfolderId: StateFlow<Long?> = _workshopScopeSubfolderId.asStateFlow()

    /** Subfolder id when chat scope can edit a note (editor, web, quick-notes day). */
    private val _noteScopeSubfolderId = MutableStateFlow<Long?>(null)
    val noteScopeSubfolderId: StateFlow<Long?> = _noteScopeSubfolderId.asStateFlow()

    private val _workshopProjectPhase = MutableStateFlow(WorkshopProjectPhase.INTAKE)
    val workshopProjectPhase: StateFlow<WorkshopProjectPhase> = _workshopProjectPhase.asStateFlow()

    private val _workshopUpdateSection = MutableStateFlow<WorkshopUpdateSection?>(null)
    val workshopUpdateSection: StateFlow<WorkshopUpdateSection?> = _workshopUpdateSection.asStateFlow()

    private val _pendingDocAlignScope = MutableStateFlow<WorkshopDocAlignScope?>(null)

    private val _workshopEidosModeOverride = MutableStateFlow<WorkshopEidosMode?>(null)

    val workshopEidosMode: StateFlow<WorkshopEidosMode> = combine(
        _workshopScopeSubfolderId,
        _workshopProjectPhase,
        _workshopUpdateSection,
        _workshopEidosModeOverride,
    ) { subfolderId, phase, updateSection, override ->
        if (subfolderId == null) WorkshopEidosMode.EDIT
        else override ?: phase.defaultEidosMode(updateSection)
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), WorkshopEidosMode.EDIT)

    /** Maps internal kickoff modes (BUILD_*) to the Edit chip for the mode selector UI. */
    val workshopEidosModeChipSelection: StateFlow<WorkshopEidosMode> = combine(
        workshopEidosMode,
        _workshopProjectPhase,
    ) { mode, phase ->
        when {
            phase == WorkshopProjectPhase.INTAKE -> WorkshopEidosMode.CHAT
            mode.isBuildFamily -> WorkshopEidosMode.EDIT
            else -> WorkshopEidosMode.normalizeToUserChip(mode)
        }
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), WorkshopEidosMode.CHAT)

    // ── DIFF_REVIEW pending changes ──────────────────────────────────────────
    // When the active scope is a workshop project, surface the currently open pending
    // change set so the chat UI can show a "Review N changes" banner under the latest
    // assistant message and the workshop editor can show a top-bar badge. State is in
    // Room — the values are derived from the same DAO the queue is written to.
    private val workshopOpenPendingSet: StateFlow<com.example.optimalx.data.model.PendingChangeSet?> =
        _workshopScopeSubfolderId
            .flatMapLatest { subId ->
                if (subId == null) {
                    kotlinx.coroutines.flow.flowOf(null)
                } else {
                    db.pendingChangeDao().observeOpenSetForScope(
                        scopeType = com.example.optimalx.data.revision.SCOPE_WORKSHOP_PROJECT,
                        scopeId = subId,
                    )
                }
            }
            .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), null)

    /** Open pending change set id for the active workshop scope, or null. */
    val workshopPendingChangeSetId: StateFlow<Long?> = workshopOpenPendingSet
        .map { it?.id }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), null)

    /** Number of pending-status items in the open set for the active workshop scope. */
    val workshopPendingChangeCount: StateFlow<Int> = workshopOpenPendingSet
        .flatMapLatest { set ->
            if (set == null) {
                kotlinx.coroutines.flow.flowOf(0)
            } else {
                db.pendingChangeDao().observeItems(set.id).map { items ->
                    items.count { it.status == com.example.optimalx.data.revision.PENDING_ITEM_STATUS_PENDING }
                }
            }
        }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), 0)

    private val noteOpenPendingSet: StateFlow<com.example.optimalx.data.model.PendingChangeSet?> =
        _noteScopeSubfolderId
            .flatMapLatest { subId ->
                if (subId == null) {
                    kotlinx.coroutines.flow.flowOf(null)
                } else {
                    db.pendingChangeDao().observeOpenSetForScope(
                        scopeType = com.example.optimalx.data.revision.SCOPE_SUBFOLDER,
                        scopeId = subId,
                    )
                }
            }
            .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), null)

    /** Number of pending note edits for the active note scope. */
    val notePendingChangeCount: StateFlow<Int> = noteOpenPendingSet
        .flatMapLatest { set ->
            if (set == null) {
                kotlinx.coroutines.flow.flowOf(0)
            } else {
                db.pendingChangeDao().observeItems(set.id).map { items ->
                    items.count { it.status == com.example.optimalx.data.revision.PENDING_ITEM_STATUS_PENDING }
                }
            }
        }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), 0)

    /**
     * Panel Workshop: open file name + full text for Eidos system prompt (cleared when leaving workshop scope).
     */
    fun reportWorkshopEditorFileState(fileName: String?, content: String?) {
        _workshopOpenFileName.value = fileName?.trim()?.takeIf { it.isNotEmpty() }
        _workshopOpenFileContent.value = content
    }

    /**
     * Set by [com.example.optimalx.ui.workshop.WorkshopEditorScreen] so workshop sends
     * flush the code editor to disk before tools run (Diff Review uses on-disk baseline).
     */
    var workshopFlushOpenFileBeforeSend: (suspend () -> Unit)? = null

    /**
     * Set by [com.example.optimalx.ui.editor.EditorScreen] so subfolder note sends
     * flush the open editor to Room before tools run.
     */
    var noteFlushBeforeSend: (suspend () -> Unit)? = null

    /**
     * Reports which part of the subfolder editor the user is viewing (note, file list, web, or an open file).
     * Cleared when leaving that editor or when chat scope no longer matches.
     */
    fun reportSubfolderEditorSurface(subfolderId: Long, surfaceDescription: String) {
        _subfolderEditorSurfaceHint.value = subfolderId to surfaceDescription
    }

    fun clearSubfolderEditorSurfaceReport(subfolderId: Long) {
        if (_subfolderEditorSurfaceHint.value?.first == subfolderId) {
            _subfolderEditorSurfaceHint.value = null
        }
    }

    /** Current URL in [WebPanel] when that composable is active — injected into the system prompt on each send. */
    private val _webPanelPageUrl = MutableStateFlow<String?>(null)

    /** Called from [WebPanel] when the loaded page changes; pass null when the panel leaves composition. */
    fun reportWebPanelPageUrl(url: String?) {
        _webPanelPageUrl.value = url?.trim()?.takeIf { it.isNotBlank() }
    }

    private fun clearSubfolderEditorSurfaceIfStale(scope: ConversationScope) {
        val stored = _subfolderEditorSurfaceHint.value ?: return
        val keep = when (scope) {
            is ConversationScope.Subfolder -> stored.first == scope.id
            is ConversationScope.Workshop -> stored.first == scope.subfolderId
            else -> false
        }
        if (!keep) _subfolderEditorSurfaceHint.value = null
    }

    // ── Scope management ──────────────────────────────────────────────────────

    /**
     * General chat (`scopeType = "general"`): root folder grid / **ParentFolderScreen**
     * when no folder is selected. Also widget default surface.
     * See `AppNavigation` — not used from SubfolderScreen (that uses parent scope).
     */
    fun setGeneralScope() {
        clearQuickNotesChatToolbarRestriction()
        clearWebChatToolbarRestriction()
        applyScope(ConversationScope.General)
    }

    /**
     * Parent chat (`scopeType = "parent"`): opened from **SubfolderScreen** —
     * the list of subfolders for one `parentFolderId` after user picked a folder
     * on ParentFolderScreen. NOT the top-level folder grid (that uses [setGeneralScope]).
     */
    fun setParentFolderScope(parentFolderId: Long) {
        clearWebChatToolbarRestriction()
        viewModelScope.launch {
            val isQuickNotes = db.parentFolderDao().getById(parentFolderId)?.name == SystemFolderNames.QUICK_NOTES
            _restrictQuickNotesChatToolbar.value = isQuickNotes
            if (!isQuickNotes) {
                _quickNotesInboxSubfolderId.value = null
            }
            applyScope(
                if (isQuickNotes) ConversationScope.QuickNotesRoot(parentFolderId)
                else ConversationScope.ParentFolder(parentFolderId),
            )
        }
    }

    /** Open Eidos from the Panel Workshop editor (project-scoped chat). */
    fun setWorkshopScope(subfolderId: Long) {
        clearQuickNotesChatToolbarRestriction()
        clearWebChatToolbarRestriction()
        _workshopScopeSubfolderId.value = subfolderId
        _workshopProjectPhase.value = WorkshopProjectPreferences.getProjectPhase(getApplication(), subfolderId)
        _workshopUpdateSection.value = WorkshopProjectPreferences.getUpdateSection(getApplication(), subfolderId)
        syncWorkshopEidosModeFromPrefs()
        applyScope(ConversationScope.Workshop(subfolderId))
    }

    fun refreshWorkshopProjectPhase() {
        val subfolderId = _workshopScopeSubfolderId.value ?: return
        val phase = WorkshopProjectPreferences.getProjectPhase(getApplication(), subfolderId)
        _workshopProjectPhase.value = phase
        if (phase == WorkshopProjectPhase.UPDATE) {
            WorkshopProjectPreferences.setUpdateSection(getApplication(), subfolderId, null)
            _workshopUpdateSection.value = null
        } else {
            _workshopUpdateSection.value = WorkshopProjectPreferences.getUpdateSection(getApplication(), subfolderId)
        }
        syncWorkshopEidosModeFromPrefs()
    }

    fun setWorkshopEidosMode(mode: WorkshopEidosMode) {
        if (mode !in WorkshopEidosMode.USER_CHIP_MODES) return
        val subfolderId = _workshopScopeSubfolderId.value ?: return
        _workshopEidosModeOverride.value = mode
        WorkshopProjectPreferences.setEidosModeOverride(getApplication(), subfolderId, mode)
    }

    private fun syncWorkshopEidosModeFromPrefs() {
        val subfolderId = _workshopScopeSubfolderId.value ?: return
        val phase = _workshopProjectPhase.value
        val updateSection = _workshopUpdateSection.value
        val app = getApplication<Application>()
        val stored = WorkshopProjectPreferences.getEidosModeOverride(app, subfolderId)
        val activeKickoff = WorkshopProjectPreferences.getBuildKickoff(app, subfolderId)
        val resolved = phase.resolveStoredEidosMode(
            stored = stored,
            updateSection = updateSection,
            activeBuildKickoff = activeKickoff,
        )
        _workshopEidosModeOverride.value = resolved
        if (stored != resolved) {
            WorkshopProjectPreferences.setEidosModeOverride(app, subfolderId, resolved)
        }
    }

    private fun resolveWorkshopEidosModeForSend(): WorkshopEidosMode {
        val mode = workshopEidosMode.value
        val phase = _workshopProjectPhase.value
        val subfolderId = _workshopScopeSubfolderId.value
        val app = getApplication<Application>()
        val activeKickoff = subfolderId?.let { WorkshopProjectPreferences.getBuildKickoff(app, it) }
        if (WorkshopEidosModeResolver.isBuildKickoffModeActive(mode, phase, activeKickoff)) {
            return mode
        }
        return WorkshopEidosModeResolver.coerceModeForPhase(
            mode = mode,
            phase = phase,
            docAlignScope = _pendingDocAlignScope.value,
            activeBuildKickoff = activeKickoff,
        )
    }

    suspend fun buildWorkshopIntakeSummary(workshopSubfolderId: Long): String {
        val stored = WorkshopProjectPreferences.getIntakeSummary(getApplication(), workshopSubfolderId)
        if (stored.isNotBlank()) return stored
        val conv = restoreWorkshopConversation(workshopSubfolderId) ?: return ""
        val messages = db.chatMessageDao().getAllByConversation(conv.id)
        return WorkshopIntakeSummary.fromChatMessages(messages)
    }

    /** After intake chat — write spec .md files only. */
    fun sendWorkshopGenerateSpecsKickoff(workshopSubfolderId: Long, intakeSummary: String) {
        setWorkshopScope(workshopSubfolderId)
        WorkshopProjectPreferences.setEidosModeOverride(getApplication(), workshopSubfolderId, WorkshopEidosMode.PLAN)
        _workshopEidosModeOverride.value = WorkshopEidosMode.PLAN
        val footer = PanelPlatformSpec.workshopGenerateSpecsKickoffFooter()
        val header = "Generate specs — use this intake summary (do not call workshop_read_file for README.md):\n\n"
        val maxIntake = (3900 - header.length - footer.length - 4).coerceAtLeast(800)
        val intake = intakeSummary.trim().take(maxIntake)
        val truncatedNote = if (intakeSummary.trim().length > maxIntake) {
            "\n…(intake truncated in this message; full summary is in your system context)"
        } else {
            ""
        }
        val text = buildString {
            append(header)
            append(intake)
            append(truncatedNote)
            append("\n\n")
            append(footer)
        }
        _input.value = text
        sendMessage(consumePendingImage = false)
    }

    /** After specs accepted — design build (runtime files only). */
    fun sendWorkshopBuildDesignKickoff(workshopSubfolderId: Long) {
        setWorkshopScope(workshopSubfolderId)
        WorkshopProjectPreferences.setBuildKickoff(
            getApplication(),
            workshopSubfolderId,
            WorkshopBuildKickoff.DESIGN,
        )
        WorkshopProjectPreferences.setEidosModeOverride(getApplication(), workshopSubfolderId, WorkshopEidosMode.BUILD_DESIGN)
        _workshopEidosModeOverride.value = WorkshopEidosMode.BUILD_DESIGN
        val footer = PanelPlatformSpec.workshopBuildDesignKickoffFooter()
        val text = buildString {
            append("Build design — full static design from accepted specs (do not read or write .md files):\n\n")
            append(footer)
        }
        _input.value = text
        sendMessage(consumePendingImage = false)
    }

    /** After design accepted — wire script.js / bridge.js behavior. */
    fun sendWorkshopBuildLogicKickoff(workshopSubfolderId: Long) {
        setWorkshopScope(workshopSubfolderId)
        WorkshopProjectPreferences.setBuildKickoff(
            getApplication(),
            workshopSubfolderId,
            WorkshopBuildKickoff.LOGIC,
        )
        WorkshopProjectPreferences.setEidosModeOverride(getApplication(), workshopSubfolderId, WorkshopEidosMode.BUILD_LOGIC)
        _workshopEidosModeOverride.value = WorkshopEidosMode.BUILD_LOGIC
        val footer = PanelPlatformSpec.workshopBuildLogicKickoffFooter()
        val text = buildString {
            append("Build logic — wire behavior from accepted design (do not read or write .md files):\n\n")
            append(footer)
        }
        _input.value = text
        sendMessage(consumePendingImage = false)
    }

    /**
     * Approval gate — refresh spec .md from current code (Plan mode), one-shot.
     * The current code + stale specs are inlined so Eidos rewrites specs without a read loop.
     */
    fun sendWorkshopAlignDocsFromCode(
        workshopSubfolderId: Long,
        scope: WorkshopDocAlignScope,
        staleSpecs: List<String>,
        inlinePayload: String,
    ) {
        setWorkshopScope(workshopSubfolderId)
        _pendingDocAlignScope.value = scope
        WorkshopProjectPreferences.setEidosModeOverride(getApplication(), workshopSubfolderId, WorkshopEidosMode.PLAN)
        _workshopEidosModeOverride.value = WorkshopEidosMode.PLAN
        _input.value = PanelPlatformSpec.workshopAlignDocsInlineMessage(
            scope = scope,
            staleSpecs = staleSpecs,
            inlinePayload = inlinePayload,
            updateSection = _workshopUpdateSection.value,
        )
        sendMessage(consumePendingImage = false)
    }

    /** Open Eidos from the editor (full chat chrome). */
    fun setSubfolderScope(subfolderId: Long) {
        clearQuickNotesChatToolbarRestriction()
        clearWebChatToolbarRestriction()
        applyScope(ConversationScope.Subfolder(subfolderId))
    }

    /** DumpEdit scratch buffer — isolated chat thread; buffer context injected per send. */
    fun setDumpEditScope() {
        clearQuickNotesChatToolbarRestriction()
        clearWebChatToolbarRestriction()
        applyScope(ConversationScope.DumpEdit)
    }

    /** Panel Gallery list — singleton runtime chat thread (not per-panel). */
    fun setPanelGalleryScope() {
        clearQuickNotesChatToolbarRestriction()
        clearWebChatToolbarRestriction()
        applyScope(ConversationScope.PanelGallery)
    }

    /** Panel Gallery runner — per workshop project; separate from [setWorkshopScope]. */
    fun setPanelRunnerScope(subfolderId: Long) {
        clearQuickNotesChatToolbarRestriction()
        clearWebChatToolbarRestriction()
        applyScope(ConversationScope.PanelRunner(subfolderId))
        viewModelScope.launch {
            panelRunnerScopeLabel = db.subfolderDao().getById(subfolderId)?.name
            updateChatScopeLabel()
        }
    }

    /** Image Studio hub or subfolder tab — isolated from Note tab threads. */
    fun setImageStudioScope(hub: Boolean, saveSubfolderId: Long) {
        clearQuickNotesChatToolbarRestriction()
        clearWebChatToolbarRestriction()
        imageStudioHubMode = hub
        applyScope(ConversationScope.ImageStudio(hub = hub, saveSubfolderId = saveSubfolderId))
        viewModelScope.launch {
            imageStudioScopeLabel = if (hub) {
                "All Images"
            } else {
                db.subfolderDao().getById(saveSubfolderId)?.name
            }
            updateChatScopeLabel()
        }
    }

    fun setImageStudioActivePreview(fileReferenceId: Long?, fileName: String?) {
        if (currentScope !is ConversationScope.ImageStudio) return
        imageStudioActivePreviewId = fileReferenceId
        imageStudioActivePreviewName = fileName?.trim()?.takeIf { it.isNotEmpty() }
    }

    fun requestImageStudioDraftHandoff(messageText: String) {
        val draft = ImageStudioDraftParser.parse(messageText) ?: return
        val scope = viewedScope as? ConversationScope.ImageStudio ?: return
        _pendingImageStudioDraftHandoff.value = ImageStudioDraftHandoff(
            saveSubfolderId = scope.saveSubfolderId,
            hub = scope.hub,
            draft = draft,
        )
    }

    fun consumeImageStudioDraftHandoff(): ImageStudioDraftHandoff? {
        val current = _pendingImageStudioDraftHandoff.value
        _pendingImageStudioDraftHandoff.value = null
        return current
    }

    /** Quick Notes inbox: same subfolder scope, but History / New chat / Move are hidden in the chat sheet. */
    fun setQuickNotesInboxScope(subfolderId: Long) {
        _restrictQuickNotesChatToolbar.value = true
        _quickNotesInboxSubfolderId.value = subfolderId
        applyScope(ConversationScope.QuickNotesDay(subfolderId))
        // Always re-load the latest subfolder thread from the DB. applyScope() no-ops when scope is unchanged
        // (e.g. editor → same-day inbox), which left in-memory messages stale and hid widget-added turns.
        viewModelScope.launch { reloadQuickNotesInboxSubfolderFromDatabase(subfolderId) }
    }

    /**
     * Reloads the newest conversation for this Quick Notes day folder from the database.
     * Call on lifecycle resume so widget mic turns appear without leaving the screen.
     */
    fun resyncQuickNotesInboxFromDatabase(subfolderId: Long) {
        viewModelScope.launch { reloadQuickNotesInboxSubfolderFromDatabase(subfolderId) }
    }

    private suspend fun reloadQuickNotesInboxSubfolderFromDatabase(subfolderId: Long) {
        if (viewedScope != ConversationScope.QuickNotesDay(subfolderId)) return
        val loadEpoch = beginConversationLoad()
        val conv = db.conversationDao().getRecentQuickNotesDay(subfolderId, 1).firstOrNull()
            ?: db.conversationDao().getRecentBySubfolder(subfolderId, 1).firstOrNull()
        if (conv != null) {
            val sameThread = activeConversationId == conv.id
            loadConversationInternal(conv.id, resetProviderChain = !sameThread, loadEpoch = loadEpoch)
        } else {
            activeConversationId = null
            _hasActiveConversation.value = false
            previousResponseId = null
            _messages.value = emptyList()
        }
        if (!isConversationLoadCurrent(loadEpoch)) return
        refreshSummaries()
    }

    fun clearQuickNotesChatToolbarRestriction() {
        _restrictQuickNotesChatToolbar.value = false
        _quickNotesInboxSubfolderId.value = null
    }

    private fun clearWebChatToolbarRestriction() {
        _restrictWebChatToolbar.value = false
    }

    /** Editor web tab — search-keyed threads per subfolder; no main-chat History/New chat/Move. */
    fun setWebEditorScope(subfolderId: Long) {
        clearQuickNotesChatToolbarRestriction()
        _restrictWebChatToolbar.value = true
        viewModelScope.launch { api.preloadKimiFormulaTools() }
        val preservedKey = (currentScope as? ConversationScope.WebEditor)
            ?.takeIf { it.subfolderId == subfolderId }
            ?.searchKey
        if (currentScope is ConversationScope.WebEditor &&
            (currentScope as ConversationScope.WebEditor).subfolderId != subfolderId
        ) {
            activeWebSearchKey = null
            activeWebSearchDisplay = null
        }
        viewedScope = ConversationScope.WebEditor(subfolderId, preservedKey)
        if (currentScope is ConversationScope.WebEditor &&
            (currentScope as ConversationScope.WebEditor).subfolderId == subfolderId &&
            (currentScope as ConversationScope.WebEditor).searchKey == preservedKey
        ) {
            updateChatScopeLabel()
            return
        }
        applyWebScope(ConversationScope.WebEditor(subfolderId, preservedKey))
    }

    /** Widget web panel — isolated from widget general chat. */
    fun setWebWidgetScope() {
        clearQuickNotesChatToolbarRestriction()
        _restrictWebChatToolbar.value = true
        viewModelScope.launch { api.preloadKimiFormulaTools() }
        val preservedKey = if (currentScope is ConversationScope.WebWidget) activeWebSearchKey else null
        viewedScope = ConversationScope.WebWidget
        if (currentScope is ConversationScope.WebWidget && activeWebSearchKey == preservedKey) {
            updateChatScopeLabel()
            return
        }
        applyWebScope(ConversationScope.WebWidget, preservedKey)
    }

    /**
     * Binds web-scoped chat to the current page when the user browses by URL (not search).
     * No-op if a search thread is already active — see [setWebSearchContext].
     */
    fun ensureWebPageChatContext(pageLabel: String) {
        if (!isWebScope(viewedScope) || !activeWebSearchKey.isNullOrBlank()) return
        val trimmed = pageLabel.trim()
        if (trimmed.isBlank()) return
        setWebSearchContext(trimmed)
    }

    fun setWebSearchContext(displayQuery: String) {
        val key = normalizeWebSearchKey(displayQuery) ?: return
        activeWebSearchKey = key
        activeWebSearchDisplay = displayWebSearchTitle(displayQuery)
        viewModelScope.launch {
            when (val s = currentScope) {
                is ConversationScope.WebEditor -> {
                    currentScope = ConversationScope.WebEditor(s.subfolderId, key)
                    viewedScope = currentScope
                    loadWebConversation(key, subfolderId = s.subfolderId, isWidget = false)
                }
                is ConversationScope.WebWidget -> {
                    loadWebConversation(key, subfolderId = null, isWidget = true)
                }
                else -> Unit
            }
            updateChatScopeLabel()
        }
    }

    fun clearWebSearchContext() {
        activeWebSearchKey = null
        activeWebSearchDisplay = null
        activeConversationId = null
        _hasActiveConversation.value = false
        previousResponseId = null
        _messages.value = emptyList()
    }

    fun deleteWebConversationForSearch(subfolderId: Long?, rawQuery: String) {
        val key = normalizeWebSearchKey(rawQuery) ?: return
        viewModelScope.launch {
            val conv = if (subfolderId != null) {
                db.conversationDao().getByWebEditorSearch(subfolderId, key)
            } else {
                db.conversationDao().getByWebWidgetSearch(key)
            } ?: return@launch
            db.chatMessageDao().deleteAllByConversation(conv.id)
            if (subfolderId != null) {
                db.conversationDao().deleteWebEditorSearch(subfolderId, key)
            } else {
                db.conversationDao().deleteWebWidgetSearch(key)
            }
            requestRetrievalSync("web_conversation_deleted:${conv.id}")
            if (activeConversationId == conv.id) {
                activeConversationId = null
                _hasActiveConversation.value = false
                previousResponseId = null
                _messages.value = emptyList()
            }
            if (activeWebSearchKey == key) {
                val scopeSnapshot = currentScope
                val matchesScope = when (scopeSnapshot) {
                    is ConversationScope.WebEditor -> scopeSnapshot.subfolderId == subfolderId
                    is ConversationScope.WebWidget -> subfolderId == null
                    else -> false
                }
                if (matchesScope) {
                    activeWebSearchKey = null
                    activeWebSearchDisplay = null
                }
            }
        }
    }

    private fun applyWebScope(scope: ConversationScope, searchKey: String? = null) {
        if (searchKey != null) {
            activeWebSearchKey = searchKey
        }
        _workshopOpenFileName.value = null
        _workshopOpenFileContent.value = null
        clearSubfolderEditorSurfaceIfStale(scope)
        currentScope = scope
        viewedScope = scope
        updateChatScopeLabel()
        activeConversationId = null
        previousResponseId = null
        _messages.value = emptyList()
        _hasActiveConversation.value = false
        viewModelScope.launch {
            val key = when (scope) {
                is ConversationScope.WebEditor -> scope.searchKey ?: activeWebSearchKey
                is ConversationScope.WebWidget -> activeWebSearchKey
                else -> null
            }
            if (key != null) {
                when (scope) {
                    is ConversationScope.WebEditor ->
                        loadWebConversation(key, subfolderId = scope.subfolderId, isWidget = false)
                    is ConversationScope.WebWidget ->
                        loadWebConversation(key, subfolderId = null, isWidget = true)
                    else -> Unit
                }
            }
            refreshSummaries()
        }
    }

    private suspend fun loadWebConversation(
        webSearchKey: String,
        subfolderId: Long?,
        isWidget: Boolean,
    ) {
        val conv = if (isWidget) {
            db.conversationDao().getByWebWidgetSearch(webSearchKey)
        } else {
            val sid = subfolderId ?: return
            db.conversationDao().getByWebEditorSearch(sid, webSearchKey)
        } ?: run {
            activeConversationId = null
            _hasActiveConversation.value = false
            _messages.value = emptyList()
            return
        }
        activeWebSearchKey = webSearchKey
        activeConversationId = conv.id
        _hasActiveConversation.value = true
        val msgs = db.chatMessageDao().getAllByConversation(conv.id)
        _messages.value = msgs.map { it.toUiMessage() }
    }

    private fun applyScope(scope: ConversationScope) {
        when (scope) {
            is ConversationScope.WebEditor -> {
                setWebEditorScope(scope.subfolderId)
                scope.searchKey?.let { key ->
                    activeWebSearchDisplay = activeWebSearchDisplay ?: key
                    viewModelScope.launch { setWebSearchContext(key) }
                }
                return
            }
            is ConversationScope.WebWidget -> {
                setWebWidgetScope()
                return
            }
            else -> clearWebChatToolbarRestriction()
        }
        val alreadyOnPage = viewedScope == scope
        viewedScope = scope
        if (alreadyOnPage) {
            viewModelScope.launch {
                refreshHistoryDirectoryOptions()
                refreshHistoryLocationTargets()
                refreshSummaries()
                // Fresh VM defaults currentScope to General, so the first setGeneralScope() used to return
                // here and skip restoreLastConversation() — prefs never applied after cold start.
                if (activeConversationId == null) {
                    restoreLastConversation(conversationLoadEpoch)
                }
            }
            return
        }
        clearSubfolderEditorSurfaceIfStale(scope)
        if (scope !is ConversationScope.Workshop) {
            _workshopOpenFileName.value = null
            _workshopOpenFileContent.value = null
            _workshopScopeSubfolderId.value = null
        }
        _noteScopeSubfolderId.value = when (scope) {
            is ConversationScope.Subfolder -> scope.id
            is ConversationScope.WebEditor -> scope.subfolderId
            is ConversationScope.QuickNotesDay -> scope.subfolderId
            else -> null
        }
        if (scope !is ConversationScope.PanelRunner) {
            panelRunnerScopeLabel = null
        }
        if (scope !is ConversationScope.ImageStudio) {
            imageStudioScopeLabel = null
            imageStudioHubMode = false
            imageStudioActivePreviewId = null
            imageStudioActivePreviewName = null
        }
        _isImageStudioScope.value = scope is ConversationScope.ImageStudio
        currentScope = scope
        if (scope !is ConversationScope.QuickNotesDay) {
            quickNotesDayLabel = null
        }
        updateChatScopeLabel()
        selectedParentDirectoryId = when (scope) {
            is ConversationScope.ParentFolder -> scope.id
            is ConversationScope.Subfolder, is ConversationScope.WebEditor -> null
            is ConversationScope.General -> null
            is ConversationScope.QuickNotesRoot -> scope.parentId
            is ConversationScope.QuickNotesDay, is ConversationScope.WebWidget -> null
            is ConversationScope.Workshop,
            is ConversationScope.DumpEdit,
            is ConversationScope.PanelGallery,
            is ConversationScope.PanelRunner,
            is ConversationScope.ImageStudio,
            -> null
        }
        _selectedHistoryDirectory.value = scopeToDirectory(scope)
        val restoreEpoch = beginConversationLoad()
        activeConversationId = null
        previousResponseId = null
        _messages.value = emptyList()
        _streamPreview.value = null
        _isSending.value = false
        viewModelScope.launch {
            if (scope is ConversationScope.Subfolder) {
                selectedParentDirectoryId = db.subfolderDao().getById(scope.id)?.parentFolderId
            } else if (scope is ConversationScope.Workshop) {
                selectedParentDirectoryId = db.subfolderDao().getById(scope.subfolderId)?.parentFolderId
            } else if (scope is ConversationScope.PanelRunner) {
                selectedParentDirectoryId = db.subfolderDao().getById(scope.subfolderId)?.parentFolderId
                _selectedHistoryLocationId.value = scope.subfolderId
            } else if (scope is ConversationScope.ImageStudio) {
                selectedParentDirectoryId = db.subfolderDao().getById(scope.saveSubfolderId)?.parentFolderId
                _selectedHistoryLocationId.value = scope.saveSubfolderId
            } else if (scope is ConversationScope.WebEditor) {
                selectedParentDirectoryId = db.subfolderDao().getById(scope.subfolderId)?.parentFolderId
            } else if (scope is ConversationScope.QuickNotesDay) {
                val subfolder = db.subfolderDao().getById(scope.subfolderId)
                selectedParentDirectoryId = subfolder?.parentFolderId
                quickNotesDayLabel = subfolder?.name
                updateChatScopeLabel()
            }
            refreshHistoryDirectoryOptions()
            refreshHistoryLocationTargets()
            restoreLastConversation(restoreEpoch)
            _isSending.value = activeConversationId?.let { EidosActiveSendRegistry.isActive(it) } == true
            refreshSummaries()
        }
    }

    /** True when [conversationId] is the thread currently shown in chat UI (any scope). */
    private fun isActiveConversation(conversationId: Long): Boolean =
        activeConversationId == conversationId

    /** Restore the last conversation for the current scope (stored pointers, not global recency). */
    private suspend fun restoreLastConversation(loadEpoch: Int) {
        if (!isConversationLoadCurrent(loadEpoch)) return
        val conversation = when (val s = currentScope) {
            is ConversationScope.General -> restoreGeneralScopeConversation()
            is ConversationScope.ParentFolder -> restoreParentFolderConversation(s.id)
            is ConversationScope.Subfolder -> restoreSubfolderConversation(s.id)
            is ConversationScope.Workshop -> restoreWorkshopConversation(s.subfolderId)
            is ConversationScope.QuickNotesRoot -> restoreQuickNotesRootConversation(s.parentId)
            is ConversationScope.QuickNotesDay -> restoreQuickNotesDayConversation(s.subfolderId)
            is ConversationScope.DumpEdit -> restoreDumpEditConversation()
            is ConversationScope.PanelGallery -> restorePanelGalleryConversation()
            is ConversationScope.PanelRunner -> restorePanelRunnerConversation(s.subfolderId)
            is ConversationScope.ImageStudio -> restoreImageStudioConversation(s.saveSubfolderId)
            is ConversationScope.WebEditor, is ConversationScope.WebWidget -> return
        } ?: return
        if (!isConversationLoadCurrent(loadEpoch)) return
        loadConversationInternal(conversation.id, loadEpoch = loadEpoch)
    }

    private suspend fun restoreGeneralScopeConversation(): Conversation? {
        return when (entrySurface) {
            EidosChatEntrySurface.MAIN_APP -> {
                ChatSessionPointers.getGeneralMain(appRef)?.let { stored ->
                    val conv = db.conversationDao().getById(stored)
                    if (conv != null && conv.scopeType == "general") return conv
                    ChatSessionPointers.setGeneralMain(appRef, null)
                }
                null
            }
            EidosChatEntrySurface.WIDGET -> {
                WidgetPrefs.getActiveConversationId(appRef)?.let { stored ->
                    val conv = db.conversationDao().getById(stored)
                    if (conv != null && conv.scopeType == "general") return conv
                    WidgetPrefs.clearActiveConversationId(appRef)
                }
                null
            }
        }
    }

    private suspend fun restoreParentFolderConversation(parentFolderId: Long): Conversation? {
        if (entrySurface != EidosChatEntrySurface.MAIN_APP) {
            return db.conversationDao().getRecentByParentFolder(parentFolderId, 1).firstOrNull()
        }
        ChatSessionPointers.getParentFolder(appRef, parentFolderId)?.let { stored ->
            val conv = db.conversationDao().getById(stored)
            if (conv != null && conv.scopeType == "parent" && conv.parentFolderId == parentFolderId) {
                return conv
            }
            ChatSessionPointers.clearParentFolder(appRef, parentFolderId)
        }
        return db.conversationDao().getRecentByParentFolder(parentFolderId, 1).firstOrNull()
    }

    private suspend fun restoreSubfolderConversation(subfolderId: Long): Conversation? {
        if (entrySurface != EidosChatEntrySurface.MAIN_APP) {
            return db.conversationDao().getRecentBySubfolder(subfolderId, 1).firstOrNull()
        }
        ChatSessionPointers.getSubfolder(appRef, subfolderId)?.let { stored ->
            val conv = db.conversationDao().getById(stored)
            if (conv != null && conv.scopeType == "subfolder" && conv.subfolderId == subfolderId) {
                return conv
            }
            ChatSessionPointers.clearSubfolder(appRef, subfolderId)
        }
        return db.conversationDao().getRecentBySubfolder(subfolderId, 1).firstOrNull()
    }

    private suspend fun restoreWorkshopConversation(subfolderId: Long): Conversation? {
        if (entrySurface != EidosChatEntrySurface.MAIN_APP) {
            return db.conversationDao().getRecentPanelWorkshop(subfolderId, 1).firstOrNull()
        }
        ChatSessionPointers.getPanelWorkshop(appRef, subfolderId)?.let { stored ->
            val conv = db.conversationDao().getById(stored)
            if (conv != null && conv.scopeType == ConversationScopes.PANEL_WORKSHOP && conv.subfolderId == subfolderId) {
                return conv
            }
            ChatSessionPointers.clearPanelWorkshop(appRef, subfolderId)
        }
        return db.conversationDao().getRecentPanelWorkshop(subfolderId, 1).firstOrNull()
    }

    private suspend fun restoreQuickNotesRootConversation(parentFolderId: Long): Conversation? {
        if (entrySurface != EidosChatEntrySurface.MAIN_APP) {
            return db.conversationDao().getRecentQuickNotesRoot(parentFolderId, 1).firstOrNull()
                ?: db.conversationDao().getRecentByParentFolder(parentFolderId, 1).firstOrNull()
        }
        ChatSessionPointers.getQuickNotesRoot(appRef, parentFolderId)?.let { stored ->
            val conv = db.conversationDao().getById(stored)
            if (conv != null && conv.scopeType == "quick_notes_root" && conv.parentFolderId == parentFolderId) {
                return conv
            }
            ChatSessionPointers.clearQuickNotesRoot(appRef, parentFolderId)
        }
        return db.conversationDao().getRecentQuickNotesRoot(parentFolderId, 1).firstOrNull()
            ?: db.conversationDao().getRecentByParentFolder(parentFolderId, 1).firstOrNull()
    }

    private suspend fun restoreQuickNotesDayConversation(subfolderId: Long): Conversation? {
        if (entrySurface != EidosChatEntrySurface.MAIN_APP) {
            return db.conversationDao().getRecentQuickNotesDay(subfolderId, 1).firstOrNull()
                ?: db.conversationDao().getRecentBySubfolder(subfolderId, 1).firstOrNull()
        }
        ChatSessionPointers.getQuickNotesDay(appRef, subfolderId)?.let { stored ->
            val conv = db.conversationDao().getById(stored)
            if (conv != null && conv.scopeType == "quick_notes_day" && conv.subfolderId == subfolderId) {
                return conv
            }
            ChatSessionPointers.clearQuickNotesDay(appRef, subfolderId)
        }
        return db.conversationDao().getRecentQuickNotesDay(subfolderId, 1).firstOrNull()
            ?: db.conversationDao().getRecentBySubfolder(subfolderId, 1).firstOrNull()
    }

    private suspend fun restoreDumpEditConversation(): Conversation? {
        if (entrySurface != EidosChatEntrySurface.MAIN_APP) {
            return db.conversationDao().getRecentDumpEdit(1).firstOrNull()
        }
        ChatSessionPointers.getDumpEdit(appRef)?.let { stored ->
            val conv = db.conversationDao().getById(stored)
            if (conv != null && conv.scopeType == ConversationScopes.DUMP_EDIT) {
                return conv
            }
            ChatSessionPointers.clearDumpEdit(appRef)
        }
        return db.conversationDao().getRecentDumpEdit(1).firstOrNull()
    }

    private suspend fun restorePanelGalleryConversation(): Conversation? {
        if (entrySurface != EidosChatEntrySurface.MAIN_APP) {
            return db.conversationDao().getRecentPanelGallery(1).firstOrNull()
        }
        ChatSessionPointers.getPanelGallery(appRef)?.let { stored ->
            val conv = db.conversationDao().getById(stored)
            if (conv != null && conv.scopeType == ConversationScopes.PANEL_GALLERY) {
                return conv
            }
            ChatSessionPointers.clearPanelGallery(appRef)
        }
        return db.conversationDao().getRecentPanelGallery(1).firstOrNull()
    }

    private suspend fun restorePanelRunnerConversation(subfolderId: Long): Conversation? {
        if (entrySurface != EidosChatEntrySurface.MAIN_APP) {
            return db.conversationDao().getRecentPanelRunner(subfolderId, 1).firstOrNull()
        }
        ChatSessionPointers.getPanelRunner(appRef, subfolderId)?.let { stored ->
            val conv = db.conversationDao().getById(stored)
            if (conv != null &&
                conv.scopeType == ConversationScopes.PANEL_RUNNER &&
                conv.subfolderId == subfolderId
            ) {
                return conv
            }
            ChatSessionPointers.clearPanelRunner(appRef, subfolderId)
        }
        return db.conversationDao().getRecentPanelRunner(subfolderId, 1).firstOrNull()
    }

    private suspend fun restoreImageStudioConversation(saveSubfolderId: Long): Conversation? {
        if (entrySurface != EidosChatEntrySurface.MAIN_APP) {
            return db.conversationDao().getRecentImageStudio(saveSubfolderId, 1).firstOrNull()
        }
        ChatSessionPointers.getImageStudio(appRef, saveSubfolderId)?.let { stored ->
            val conv = db.conversationDao().getById(stored)
            if (conv != null &&
                conv.scopeType == ConversationScopes.IMAGE_STUDIO &&
                conv.subfolderId == saveSubfolderId
            ) {
                return conv
            }
            ChatSessionPointers.clearImageStudio(appRef, saveSubfolderId)
        }
        return db.conversationDao().getRecentImageStudio(saveSubfolderId, 1).firstOrNull()
    }

    private fun persistConversationPointerIfNeeded(conv: Conversation) {
        when (entrySurface) {
            EidosChatEntrySurface.MAIN_APP -> {
                when (conv.scopeType) {
                    "general" -> ChatSessionPointers.setGeneralMain(appRef, conv.id)
                    "parent" -> {
                        val pid = conv.parentFolderId ?: return
                        ChatSessionPointers.setParentFolder(appRef, pid, conv.id)
                    }
                    "subfolder" -> {
                        val sid = conv.subfolderId ?: return
                        ChatSessionPointers.setSubfolder(appRef, sid, conv.id)
                    }
                    "quick_notes_root" -> {
                        val pid = conv.parentFolderId ?: return
                        ChatSessionPointers.setQuickNotesRoot(appRef, pid, conv.id)
                    }
                    "quick_notes_day" -> {
                        val sid = conv.subfolderId ?: return
                        ChatSessionPointers.setQuickNotesDay(appRef, sid, conv.id)
                    }
                    ConversationScopes.PANEL_WORKSHOP -> {
                        val sid = conv.subfolderId ?: return
                        ChatSessionPointers.setPanelWorkshop(appRef, sid, conv.id)
                    }
                    ConversationScopes.DUMP_EDIT -> {
                        ChatSessionPointers.setDumpEdit(appRef, conv.id)
                    }
                    ConversationScopes.PANEL_GALLERY -> {
                        ChatSessionPointers.setPanelGallery(appRef, conv.id)
                    }
                    ConversationScopes.PANEL_RUNNER -> {
                        val sid = conv.subfolderId ?: return
                        ChatSessionPointers.setPanelRunner(appRef, sid, conv.id)
                    }
                    ConversationScopes.IMAGE_STUDIO -> {
                        val sid = conv.subfolderId ?: return
                        ChatSessionPointers.setImageStudio(appRef, sid, conv.id)
                    }
                }
            }
            EidosChatEntrySurface.WIDGET -> {
                if (conv.scopeType == "general") {
                    WidgetPrefs.setActiveConversationId(appRef, conv.id)
                }
            }
        }
    }

    private fun clearStoredPointersForNewChat() {
        when (val vs = viewedScope) {
            is ConversationScope.General -> {
                when (entrySurface) {
                    EidosChatEntrySurface.MAIN_APP -> ChatSessionPointers.setGeneralMain(appRef, null)
                    EidosChatEntrySurface.WIDGET -> WidgetPrefs.clearActiveConversationId(appRef)
                }
            }
            is ConversationScope.ParentFolder -> {
                if (entrySurface == EidosChatEntrySurface.MAIN_APP) {
                    ChatSessionPointers.clearParentFolder(appRef, vs.id)
                }
            }
            is ConversationScope.Subfolder -> {
                if (entrySurface == EidosChatEntrySurface.MAIN_APP) {
                    ChatSessionPointers.clearSubfolder(appRef, vs.id)
                }
            }
            is ConversationScope.QuickNotesRoot -> {
                if (entrySurface == EidosChatEntrySurface.MAIN_APP) {
                    ChatSessionPointers.clearQuickNotesRoot(appRef, vs.parentId)
                }
            }
            is ConversationScope.QuickNotesDay -> {
                if (entrySurface == EidosChatEntrySurface.MAIN_APP) {
                    ChatSessionPointers.clearQuickNotesDay(appRef, vs.subfolderId)
                }
            }
            is ConversationScope.Workshop -> {
                if (entrySurface == EidosChatEntrySurface.MAIN_APP) {
                    ChatSessionPointers.clearPanelWorkshop(appRef, vs.subfolderId)
                }
            }
            is ConversationScope.DumpEdit -> {
                if (entrySurface == EidosChatEntrySurface.MAIN_APP) {
                    ChatSessionPointers.clearDumpEdit(appRef)
                }
            }
            is ConversationScope.PanelGallery -> {
                if (entrySurface == EidosChatEntrySurface.MAIN_APP) {
                    ChatSessionPointers.clearPanelGallery(appRef)
                }
            }
            is ConversationScope.PanelRunner -> {
                if (entrySurface == EidosChatEntrySurface.MAIN_APP) {
                    ChatSessionPointers.clearPanelRunner(appRef, vs.subfolderId)
                }
            }
            is ConversationScope.ImageStudio -> {
                if (entrySurface == EidosChatEntrySurface.MAIN_APP) {
                    ChatSessionPointers.clearImageStudio(appRef, vs.saveSubfolderId)
                }
            }
            is ConversationScope.WebEditor, is ConversationScope.WebWidget -> Unit
        }
    }

    // ── Conversation load / switch ────────────────────────────────────────────

    /** Load a conversation by ID — called from history browser, ConversationListScreen, or widget. */
    fun loadConversation(conversationId: Long) {
        val loadEpoch = beginConversationLoad()
        prepareExplicitConversationSwitch(conversationId)
        viewModelScope.launch {
            loadConversationInternal(conversationId, loadEpoch = loadEpoch)
            if (!isConversationLoadCurrent(loadEpoch)) return@launch
            refreshSummaries()
        }
    }

    /**
     * Open a thread from a scoped conversation list (parent / subfolder / general).
     * Sets [viewedScope] to the list location without clearing the picked conversation.
     */
    fun openConversationFromDirectory(conversationId: Long, scopeType: String, scopeId: Long) {
        val loadEpoch = beginConversationLoad()
        prepareExplicitConversationSwitch(conversationId)
        when (scopeType) {
            "subfolder" -> viewedScope = ConversationScope.Subfolder(scopeId)
            "parent" -> Unit // resolved in applyViewedScopeFromDirectory (Quick Notes vs parent)
            else -> {
                viewedScope = ConversationScope.General
                _selectedHistoryDirectory.value = ConversationDirectory.GENERAL
                selectedParentDirectoryId = null
            }
        }
        viewModelScope.launch {
            applyViewedScopeFromDirectory(scopeType, scopeId)
            if (!isConversationLoadCurrent(loadEpoch)) return@launch
            loadConversationInternal(conversationId, loadEpoch = loadEpoch)
            if (!isConversationLoadCurrent(loadEpoch)) return@launch
            refreshSummaries()
        }
    }

    private suspend fun applyViewedScopeFromDirectory(scopeType: String, scopeId: Long) {
        val pageScope = when (scopeType) {
            "parent" -> {
                val isQuickNotes = db.parentFolderDao().getById(scopeId)?.name == SystemFolderNames.QUICK_NOTES
                if (isQuickNotes) ConversationScope.QuickNotesRoot(scopeId)
                else ConversationScope.ParentFolder(scopeId)
            }
            "subfolder" -> ConversationScope.Subfolder(scopeId)
            else -> ConversationScope.General
        }
        viewedScope = pageScope
        _selectedHistoryDirectory.value = scopeToDirectory(pageScope)
        selectedParentDirectoryId = when (pageScope) {
            is ConversationScope.ParentFolder -> pageScope.id
            is ConversationScope.QuickNotesRoot -> pageScope.parentId
            is ConversationScope.Subfolder -> db.subfolderDao().getById(pageScope.id)?.parentFolderId
            else -> null
        }
        refreshHistoryDirectoryOptions()
        refreshHistoryLocationTargets()
    }

    private suspend fun loadConversationInternal(
        conversationId: Long,
        resetProviderChain: Boolean = true,
        loadEpoch: Int = conversationLoadEpoch,
    ) {
        if (!isConversationLoadCurrent(loadEpoch)) return
        val conversation = db.conversationDao().getById(conversationId) ?: return
        if (activeConversationId != conversationId) {
            _streamPreview.value = null
        }
        if (ConversationScopes.isWebScope(conversation.scopeType)) {
            _restrictWebChatToolbar.value = true
            _restrictQuickNotesChatToolbar.value = false
            activeWebSearchKey = conversation.webSearchKey
            activeWebSearchDisplay = conversation.title
        } else {
            _restrictWebChatToolbar.value = false
        }
        currentScope = when (val rawScope = conversation.toScope()) {
            is ConversationScope.ParentFolder -> {
                val isQuickNotes = db.parentFolderDao().getById(rawScope.id)?.name == SystemFolderNames.QUICK_NOTES
                if (isQuickNotes) ConversationScope.QuickNotesRoot(rawScope.id) else rawScope
            }
            is ConversationScope.Subfolder -> {
                val parentId = db.subfolderDao().getById(rawScope.id)?.parentFolderId
                val isQuickNotes = parentId?.let { id ->
                    db.parentFolderDao().getById(id)?.name == SystemFolderNames.QUICK_NOTES
                } == true
                if (isQuickNotes) ConversationScope.QuickNotesDay(rawScope.id) else rawScope
            }
            else -> rawScope
        }
        clearSubfolderEditorSurfaceIfStale(currentScope)
        _restrictQuickNotesChatToolbar.value =
            currentScope is ConversationScope.QuickNotesRoot || currentScope is ConversationScope.QuickNotesDay
        _quickNotesInboxSubfolderId.value = (currentScope as? ConversationScope.QuickNotesDay)?.subfolderId
        if (currentScope !is ConversationScope.QuickNotesDay) {
            quickNotesDayLabel = null
        }
        updateChatScopeLabel()
        if (currentScope is ConversationScope.ParentFolder) {
            selectedParentDirectoryId = (currentScope as ConversationScope.ParentFolder).id
        } else if (currentScope is ConversationScope.Subfolder) {
            val subfolderId = (currentScope as ConversationScope.Subfolder).id
            val subfolder = db.subfolderDao().getById(subfolderId)
            selectedParentDirectoryId = subfolder?.parentFolderId
        } else if (currentScope is ConversationScope.Workshop) {
            val subfolderId = (currentScope as ConversationScope.Workshop).subfolderId
            val subfolder = db.subfolderDao().getById(subfolderId)
            selectedParentDirectoryId = subfolder?.parentFolderId
        } else if (currentScope is ConversationScope.QuickNotesRoot) {
            selectedParentDirectoryId = (currentScope as ConversationScope.QuickNotesRoot).parentId
        } else if (currentScope is ConversationScope.QuickNotesDay) {
            val subfolderId = (currentScope as ConversationScope.QuickNotesDay).subfolderId
            val subfolder = db.subfolderDao().getById(subfolderId)
            selectedParentDirectoryId = subfolder?.parentFolderId
            quickNotesDayLabel = subfolder?.name
            updateChatScopeLabel()
        } else if (currentScope is ConversationScope.PanelRunner) {
            val subfolderId = (currentScope as ConversationScope.PanelRunner).subfolderId
            val subfolder = db.subfolderDao().getById(subfolderId)
            selectedParentDirectoryId = subfolder?.parentFolderId
            panelRunnerScopeLabel = subfolder?.name
            _selectedHistoryLocationId.value = subfolderId
            updateChatScopeLabel()
        }
        if (!isConversationLoadCurrent(loadEpoch)) return
        refreshHistoryDirectoryOptions()
        refreshHistoryLocationTargets()
        activeConversationId = conversationId
        _hasActiveConversation.value = true
        if (resetProviderChain) {
            previousResponseId = null
        }
        val msgs = db.chatMessageDao().getAllByConversation(conversationId)
        if (!isConversationLoadCurrent(loadEpoch)) return
        _messages.value = msgs.map { it.toUiMessage() }
        if (!ConversationScopes.isWebScope(conversation.scopeType)) {
            persistConversationPointerIfNeeded(conversation)
        }
    }

    // ── New chat / switch ─────────────────────────────────────────────────────

    fun newChat() {
        beginConversationLoad()
        currentScope = viewedScope
        clearSubfolderEditorSurfaceIfStale(currentScope)
        updateChatScopeLabel()
        clearStoredPointersForNewChat()
        activeConversationId = null
        _hasActiveConversation.value = false
        previousResponseId = null
        _selectedHistoryDirectory.value = scopeToDirectory(viewedScope)
        _messages.value = emptyList()
        _streamPreview.value = null
        viewModelScope.launch {
            refreshHistoryLocationTargets()
            refreshSummaries()
        }
    }

    fun switchConversation(id: Long) {
        val loadEpoch = beginConversationLoad()
        prepareExplicitConversationSwitch(id)
        viewModelScope.launch {
            loadConversationInternal(id, loadEpoch = loadEpoch)
            if (!isConversationLoadCurrent(loadEpoch)) return@launch
            refreshSummaries()
        }
    }

    fun renameConversation(id: Long, newTitle: String) {
        val trimmed = newTitle.trim()
        if (trimmed.isBlank()) return
        viewModelScope.launch {
            val conversation = db.conversationDao().getById(id) ?: return@launch
            db.conversationDao().update(
                conversation.copy(
                    title = trimmed,
                    updatedAt = System.currentTimeMillis(),
                )
            )
            requestRetrievalSync("rename_conversation:$id")
            refreshSummaries()
        }
    }

    fun setHistoryDirectory(directory: ConversationDirectory) {
        if (_selectedHistoryDirectory.value == directory) return
        _selectedHistoryDirectory.value = directory
        viewModelScope.launch {
            refreshHistoryLocationTargets()
            refreshSummaries()
        }
    }

    fun setHistoryLocationTarget(id: Long) {
        if (_selectedHistoryLocationId.value == id) return
        _selectedHistoryLocationId.value = id
        viewModelScope.launch { refreshSummaries() }
    }

    fun setHistoryParentTarget(id: Long) {
        if (_selectedHistoryParentId.value == id) return
        _selectedHistoryParentId.value = id
        selectedParentDirectoryId = id
        viewModelScope.launch {
            if (_selectedHistoryDirectory.value == ConversationDirectory.PARENT) {
                _selectedHistoryLocationId.value = id
            } else if (_selectedHistoryDirectory.value == ConversationDirectory.SUBFOLDER) {
                refreshHistoryLocationTargets()
            }
            refreshSummaries()
        }
    }

    fun openSubfolderDirectory() {
        _selectedHistoryDirectory.value = ConversationDirectory.SUBFOLDER
        viewModelScope.launch {
            refreshHistoryLocationTargets()
            refreshSummaries()
        }
    }

    fun openParentDirectoryChats() {
        _selectedHistoryDirectory.value = ConversationDirectory.PARENT
        viewModelScope.launch {
            refreshHistoryLocationTargets()
            refreshSummaries()
        }
    }

    fun openConversationBrowser() {
        _selectedHistoryDirectory.value = defaultHistoryDirectory()
        viewModelScope.launch {
            refreshHistoryDirectoryOptions()
            refreshHistoryLocationTargets()
            refreshSummaries()
        }
    }

    fun moveConversationToViewedDirectory(conversationId: Long) {
        viewModelScope.launch {
            val conversation = db.conversationDao().getById(conversationId) ?: return@launch
            val now = System.currentTimeMillis()
            val moved = when (_selectedHistoryDirectory.value) {
                ConversationDirectory.RECENT,
                ConversationDirectory.HERE,
                -> return@launch
                ConversationDirectory.GENERAL -> conversation.copy(
                    scopeType = "general",
                    parentFolderId = null,
                    subfolderId = null,
                    updatedAt = now,
                )
                ConversationDirectory.PARENT -> {
                    val parentId = _selectedHistoryLocationId.value ?: return@launch
                    conversation.copy(
                        scopeType = "parent",
                        parentFolderId = parentId,
                        subfolderId = null,
                        updatedAt = now,
                    )
                }
                ConversationDirectory.SUBFOLDER -> {
                    val subfolderId = _selectedHistoryLocationId.value ?: return@launch
                    conversation.copy(
                        scopeType = "subfolder",
                        parentFolderId = null,
                        subfolderId = subfolderId,
                        updatedAt = now,
                    )
                }
            }
            db.conversationDao().update(moved)
            requestRetrievalSync("move_conversation_viewed_directory:$conversationId")
            if (activeConversationId == moved.id) {
                loadConversationInternal(moved.id, resetProviderChain = true)
            }
            refreshHistoryDirectoryOptions()
            refreshHistoryLocationTargets()
            refreshSummaries()
        }
    }

    fun moveActiveConversationToCurrentScope() {
        val id = activeConversationId ?: return
        viewModelScope.launch {
            val conversation = db.conversationDao().getById(id) ?: return@launch
            val now = System.currentTimeMillis()
            val moved = when (val s = viewedScope) {
                is ConversationScope.General -> conversation.copy(
                    scopeType = "general",
                    parentFolderId = null,
                    subfolderId = null,
                    updatedAt = now,
                )
                is ConversationScope.ParentFolder -> conversation.copy(
                    scopeType = "parent",
                    parentFolderId = s.id,
                    subfolderId = null,
                    updatedAt = now,
                )
                is ConversationScope.Subfolder -> conversation.copy(
                    scopeType = "subfolder",
                    parentFolderId = null,
                    subfolderId = s.id,
                    updatedAt = now,
                )
                is ConversationScope.QuickNotesRoot -> conversation.copy(
                    scopeType = "quick_notes_root",
                    parentFolderId = s.parentId,
                    subfolderId = null,
                    updatedAt = now,
                )
                is ConversationScope.QuickNotesDay -> conversation.copy(
                    scopeType = "quick_notes_day",
                    parentFolderId = null,
                    subfolderId = s.subfolderId,
                    updatedAt = now,
                )
                is ConversationScope.Workshop -> conversation.copy(
                    scopeType = ConversationScopes.PANEL_WORKSHOP,
                    parentFolderId = null,
                    subfolderId = s.subfolderId,
                    updatedAt = now,
                )
                is ConversationScope.DumpEdit -> conversation.copy(
                    scopeType = ConversationScopes.DUMP_EDIT,
                    parentFolderId = null,
                    subfolderId = null,
                    updatedAt = now,
                )
                is ConversationScope.PanelGallery -> conversation.copy(
                    scopeType = ConversationScopes.PANEL_GALLERY,
                    parentFolderId = null,
                    subfolderId = null,
                    updatedAt = now,
                )
                is ConversationScope.PanelRunner -> conversation.copy(
                    scopeType = ConversationScopes.PANEL_RUNNER,
                    parentFolderId = null,
                    subfolderId = s.subfolderId,
                    updatedAt = now,
                )
                is ConversationScope.ImageStudio -> conversation.copy(
                    scopeType = ConversationScopes.IMAGE_STUDIO,
                    parentFolderId = null,
                    subfolderId = s.saveSubfolderId,
                    updatedAt = now,
                )
                is ConversationScope.WebEditor, is ConversationScope.WebWidget -> return@launch
            }
            db.conversationDao().update(moved)
            requestRetrievalSync("move_active_conversation_current_scope:$id")
            loadConversationInternal(moved.id, resetProviderChain = true)
            refreshSummaries()
        }
    }

    // ── Summaries for history browser ─────────────────────────────────────────

    private suspend fun refreshSummaries() {
        val conversations = when (val pageScope = viewedScope) {
            is ConversationScope.PanelGallery ->
                db.conversationDao().getRecentPanelGallery(5)
            is ConversationScope.PanelRunner ->
                db.conversationDao().getRecentPanelRunner(pageScope.subfolderId, 5)
            is ConversationScope.ImageStudio ->
                db.conversationDao().getRecentImageStudio(pageScope.saveSubfolderId, 5)
            else -> when (_selectedHistoryDirectory.value) {
                ConversationDirectory.RECENT -> db.conversationDao().getRecentMainChat(5)
                ConversationDirectory.HERE -> conversationsForHereScope(viewedScope)
                ConversationDirectory.GENERAL -> db.conversationDao().getRecentGeneral(200)
                ConversationDirectory.PARENT -> {
                    val parentId = _selectedHistoryLocationId.value
                    if (parentId == null) emptyList() else db.conversationDao().getRecentByParentFolder(parentId, 5)
                }
                ConversationDirectory.SUBFOLDER -> {
                    val subfolderId = _selectedHistoryLocationId.value
                    if (subfolderId == null) {
                        emptyList()
                    } else {
                        db.conversationDao().getRecentBySubfolder(subfolderId, 5)
                    }
                }
            }
        }
        _conversationSummaries.value = conversations.map { conv ->
            val snippet = db.chatMessageDao().getFirstMessage(conv.id)?.content?.take(60) ?: ""
            ConversationSummary(
                id = conv.id,
                title = conv.title,
                snippet = snippet,
                isActive = conv.id == activeConversationId,
            )
        }
        _historyContextLabel.value = when (viewedScope) {
            is ConversationScope.PanelGallery -> "Chats / Panel Gallery"
            is ConversationScope.PanelRunner -> {
                val name = panelRunnerScopeLabel ?: "Panel"
                "Chats / $name"
            }
            is ConversationScope.ImageStudio -> {
                val scope = viewedScope as ConversationScope.ImageStudio
                val label = if (scope.hub) "All Images" else imageStudioScopeLabel ?: "Image Studio"
                "Chats / Image Studio · $label"
            }
            else -> when (_selectedHistoryDirectory.value) {
            ConversationDirectory.RECENT -> "Chats / Recent (Last 5)"
            ConversationDirectory.HERE -> historyHereContextLabel(viewedScope)
            ConversationDirectory.GENERAL -> "Chats / General"
            ConversationDirectory.PARENT -> {
                val label = _historyParentTargets.value.firstOrNull { it.id == _selectedHistoryParentId.value }?.label
                if (label != null) "Chats / Parent: $label" else "Chats / Parent"
            }
            ConversationDirectory.SUBFOLDER -> {
                val parentLabel = _historyParentTargets.value.firstOrNull { it.id == _selectedHistoryParentId.value }?.label
                val label = _historyLocationTargets.value.firstOrNull { it.id == _selectedHistoryLocationId.value }?.label
                when {
                    parentLabel != null && label != null -> "Chats / Subfolder: $parentLabel / $label"
                    label != null -> "Chats / Subfolder: $label"
                    else -> "Chats / Subfolder"
                }
            }
            }
        }
    }

    // ── Send message ──────────────────────────────────────────────────────────

    fun attachImageFromUri(uri: Uri) {
        viewModelScope.launch {
            val result = withContext(Dispatchers.IO) {
                ChatVisionImageStore.persistFromUri(getApplication(), uri)
            }
            when (result) {
                is ChatVisionImageStore.Result.Ok -> {
                    val previous = _pendingImage.value
                    _pendingImage.value = result.attachment
                    if (previous != null && previous.storedName != result.attachment.storedName) {
                        withContext(Dispatchers.IO) {
                            ChatVisionImageStore.deleteStored(getApplication(), previous.storedName)
                        }
                    }
                }
                is ChatVisionImageStore.Result.Err -> postToast(result.message)
            }
        }
    }

    fun clearPendingImage() {
        val previous = _pendingImage.value ?: return
        _pendingImage.value = null
        viewModelScope.launch(Dispatchers.IO) {
            ChatVisionImageStore.deleteStored(getApplication(), previous.storedName)
        }
    }

    fun sendMessage(consumePendingImage: Boolean = true) {
        val pending = if (consumePendingImage) _pendingImage.value else null
        val text = _input.value.trim().ifBlank {
            if (pending != null) ChatVisionAttachmentCodec.EMPTY_PROMPT else ""
        }
        if (text.isBlank() || _isSending.value || apiExchangeJob?.isActive == true) return
        if (isWebScope(viewedScope) && activeWebSearchKey.isNullOrBlank()) return

        _isSending.value = true
        val job = appRef.eidosSendScope.launch {
            suppressStoppedReplyOnCancellation = false
            var conversation: Conversation? = null
            val sendJob = coroutineContext[Job]
            try {
                val now = System.currentTimeMillis()
                conversation = ensureConversation(now, text)
                var conv = conversation!!
                coroutineContext[Job]?.let { EidosActiveSendRegistry.register(conv.id, it) }
                if (!ConversationScopes.isWebScope(conv.scopeType)) {
                    conv = maybeRetitleConversation(conv, text, now)
                    conversation = conv
                }
                val attachmentJson = ChatVisionAttachmentCodec.serializeAttachment(pending)
                val userMsgId = db.chatMessageDao().insert(
                    ChatMessage(
                        conversationId = conv.id,
                        role = "user",
                        content = text,
                        imageAttachmentJson = attachmentJson,
                        createdAt = now,
                    ),
                )
                appendUiMessageIfAbsent(
                    EidosUiMessage(
                        id = userMsgId,
                        role = EidosRole.USER,
                        text = text,
                        timeLabel = messageTimeFormatter.format(Instant.ofEpochMilli(now)),
                        imageAttachment = pending,
                    ),
                )
                _input.value = ""
                if (consumePendingImage) _pendingImage.value = null
                callApiAndInsertReply(conv, text, pending, userMsgId)
                refreshSummaries()
            } catch (ce: CancellationException) {
                if (!suppressStoppedReplyOnCancellation) {
                    conversation?.let { appendStoppedReplyIfNeeded(it) }
                }
                throw ce
            } catch (t: Throwable) {
                conversation?.let { appendErrorMessageIfNeeded(it.id) }
            } finally {
                finishSendExchange(conversation?.id, sendJob)
                suppressStoppedReplyOnCancellation = false
            }
        }
        apiExchangeJob = job
    }

    // ── Shared API call + reply insert ────────────────────────────────────────

    private suspend fun callApiAndInsertReply(
        conversation: Conversation,
        userText: String,
        attachedImage: ChatVisionAttachment? = null,
        activeUserMessageId: Long? = null,
    ) {
        val apiScope = viewedScope
        val attachedImagePaths = buildList {
            addAll(
                ChatVisionImageStore.filePathsForAttachment(
                    getApplication(),
                    attachedImage,
                ),
            )
            if (attachedImage == null && apiScope is ConversationScope.ImageStudio) {
                val previewId = imageStudioActivePreviewId
                if (previewId != null) {
                    val ref = db.fileReferenceDao().getById(previewId)
                    if (ref != null && java.io.File(ref.filePath).isFile) {
                        add(ref.filePath)
                    }
                }
            }
        }
        if (attachedImage != null && attachedImagePaths.isEmpty()) {
            insertVisionMissingBytesReply(conversation)
            return
        }
        val useLocalGemma = activeProvider.value == LitertLmDefaults.PROVIDER_ID
        if (apiScope is ConversationScope.Workshop) {
            workshopFlushOpenFileBeforeSend?.invoke()
        } else if (
            apiScope is ConversationScope.Subfolder ||
            apiScope is ConversationScope.QuickNotesDay ||
            apiScope is ConversationScope.WebEditor
        ) {
            noteFlushBeforeSend?.invoke()
        }
        val convForHistory = db.conversationDao().getById(conversation.id) ?: conversation
        val history = if (useLocalGemma) {
            ConversationOutboundHistory.buildForLocalGemma(
                chatMessageDao = db.chatMessageDao(),
                conversation = convForHistory,
                activeUserText = userText,
                excludeMessageId = activeUserMessageId,
            )
        } else {
            ConversationOutboundHistory.build(
                chatMessageDao = db.chatMessageDao(),
                conversation = convForHistory,
                activeUserText = userText,
                excludeMessageId = activeUserMessageId,
            )
        }
        val subfolderIdForApi = when (val s = apiScope) {
            is ConversationScope.Subfolder -> s.id
            is ConversationScope.Workshop -> s.subfolderId
            is ConversationScope.PanelRunner -> s.subfolderId
            is ConversationScope.ImageStudio -> s.saveSubfolderId
            is ConversationScope.QuickNotesDay -> s.subfolderId
            is ConversationScope.WebEditor -> s.subfolderId
            else -> null
        }
        val editorSurfaceHint = when (val s = apiScope) {
            is ConversationScope.Subfolder -> {
                val p = _subfolderEditorSurfaceHint.value
                if (p != null && p.first == s.id) p.second else null
            }
            is ConversationScope.Workshop -> {
                val p = _subfolderEditorSurfaceHint.value
                if (p != null && p.first == s.subfolderId) p.second else null
            }
            else -> null
        }
        val parentFolderIdForApi = when (val s = apiScope) {
            is ConversationScope.ParentFolder -> s.id
            is ConversationScope.QuickNotesRoot -> s.parentId
            is ConversationScope.Subfolder -> db.subfolderDao().getById(s.id)?.parentFolderId
            is ConversationScope.Workshop -> db.subfolderDao().getById(s.subfolderId)?.parentFolderId
            is ConversationScope.PanelRunner -> db.subfolderDao().getById(s.subfolderId)?.parentFolderId
            is ConversationScope.ImageStudio -> db.subfolderDao().getById(s.saveSubfolderId)?.parentFolderId
            is ConversationScope.QuickNotesDay -> db.subfolderDao().getById(s.subfolderId)?.parentFolderId
            is ConversationScope.WebEditor -> db.subfolderDao().getById(s.subfolderId)?.parentFolderId
            is ConversationScope.General,
            is ConversationScope.WebWidget,
            is ConversationScope.DumpEdit,
            is ConversationScope.PanelGallery,
            -> null
        }
        val imageStudioHubForApi = (apiScope as? ConversationScope.ImageStudio)?.hub
        val imageStudioSaveSubfolderIdForApi =
            (apiScope as? ConversationScope.ImageStudio)?.saveSubfolderId
        val scopeTypeForApi = scopeTypeForApi(apiScope)
        val boundConversationId = conversation.id
        if (isActiveConversation(boundConversationId)) {
            _streamPreview.value = null
        }
        val streamListener = EidosStreamListener { update ->
            if (isActiveConversation(boundConversationId)) {
                _streamPreview.value = update
            }
        }
        val docAlignScope = if (apiScope is ConversationScope.Workshop) {
            _pendingDocAlignScope.value
        } else {
            null
        }
        var workshopSendSucceeded = false
        try {
            val response = try {
                api.send(
                    userMessage = userText,
                    conversationId = conversation.id,
                    currentSubfolderId = subfolderIdForApi,
                    currentParentFolderId = parentFolderIdForApi,
                    currentScopeType = scopeTypeForApi,
                    conversationHistory = history,

                    previousResponseId = previousResponseId,
                    confirmationHandler = confirmationHandler,
                    subfolderEditorSurfaceHint = editorSurfaceHint,
                    webPanelPageUrl = _webPanelPageUrl.value,
                    workshopOpenFileName = _workshopOpenFileName.value,
                    workshopOpenFileContent = _workshopOpenFileContent.value,
                    workshopEidosMode = if (apiScope is ConversationScope.Workshop) {
                        resolveWorkshopEidosModeForSend()
                    } else {
                        null
                    },
                    workshopProjectPhase = if (apiScope is ConversationScope.Workshop) {
                        _workshopProjectPhase.value
                    } else {
                        null
                    },
                    workshopDocAlignScope = docAlignScope,
                    workshopUpdateSection = if (apiScope is ConversationScope.Workshop) {
                        _workshopUpdateSection.value
                    } else {
                        null
                    },
                    baseSystemPrompt = EidosIdentityPrompt.TEXT,
                    entrySurface = entrySurface.toPromptEntrySurface(),
                    streamListener = streamListener,
                    attachedImagePaths = attachedImagePaths,
                    imageStudioHub = imageStudioHubForApi,
                    imageStudioSaveSubfolderId = imageStudioSaveSubfolderIdForApi,
                    imageStudioActivePreviewFileName = imageStudioActivePreviewName,
                )
            } finally {
                _pendingDocAlignScope.value = null
                if (isActiveConversation(boundConversationId)) {
                    _streamPreview.value = null
                }
            }
            if (response.transportFailure) {
                api.resetConnections()
                viewModelScope.launch {
                    _toastMessage.emit("Connection issue. Check signal and try again.")
                }
            }
            val replyText = EidosNavigationCodec.appendNavigationMarkdownLinks(
                replyText = response.textResponse.ifBlank {
                    "I ran the request but did not receive a text response."
                },
                targets = response.navigationTargets,
            )
            val reasoningContent = response.persistableReasoningContent()
            val navigationJson = EidosNavigationCodec.serializeTargets(response.navigationTargets)
            val replyMsgId = db.chatMessageDao().insert(
                ChatMessage(
                    conversationId = conversation.id,
                    role = "eidos",
                    content = replyText,
                    assistantReasoningContent = reasoningContent,
                    navigationTargetsJson = navigationJson,
                ),
            )
            if (isActiveConversation(boundConversationId)) {
                appendUiMessageIfAbsent(
                    EidosUiMessage(
                        id = replyMsgId,
                        role = EidosRole.ASSISTANT,
                        text = replyText,
                        timeLabel = messageTimeFormatter.format(
                            Instant.ofEpochMilli(System.currentTimeMillis()),
                        ),
                        reasoningText = reasoningContent,
                        navigationTargets = response.navigationTargets,
                    ),
                )
                previousResponseId = response.providerResponseId ?: previousResponseId
            }
            db.conversationDao().update(conversation.copy(updatedAt = System.currentTimeMillis()))
            if (!response.transportFailure) {
                requestRetrievalSync("conversation_reply_written:${conversation.id}")
                maybeNotifyBackgroundReply(conversation, replyText)
                workshopSendSucceeded = true
            }
        } finally {
            if (apiScope is ConversationScope.Workshop) {
                WorkshopUpdateCompletion.onWorkshopSendFinished(
                    context = appRef,
                    db = db,
                    subfolderId = apiScope.subfolderId,
                    docAlignScope = docAlignScope,
                    sendSucceeded = workshopSendSucceeded,
                )
                refreshWorkshopProjectPhase()
                if (workshopSendSucceeded &&
                    WorkshopProjectPreferences.getBuildKickoff(appRef, apiScope.subfolderId) != null
                ) {
                    val subId = apiScope.subfolderId
                    WorkshopProjectPreferences.clearBuildKickoff(appRef, subId)
                    WorkshopProjectPreferences.setEidosModeOverride(appRef, subId, WorkshopEidosMode.EDIT)
                    _workshopEidosModeOverride.value = WorkshopEidosMode.EDIT
                }
                if (workshopSendSucceeded) {
                    appRef.eidosSendScope.launch {
                        WorkshopProjectSummaryAutomation.onWorkshopSendSucceeded(
                            context = appRef,
                            db = db,
                            subfolderId = apiScope.subfolderId,
                            docAlignScope = docAlignScope,
                        )
                    }
                } else {
                    WorkshopProjectSummaryAutomation.onWorkshopSendFailedAfterSpecGenerateKickoff(
                        context = appRef,
                        subfolderId = apiScope.subfolderId,
                    )
                }
            }
        }
    }

    private fun maybeNotifyBackgroundReply(conversation: Conversation, replyText: String) {
        val appInForeground = ProcessLifecycleOwner.get().lifecycle.currentState
            .isAtLeast(Lifecycle.State.STARTED)
        if (appInForeground && chatUiVisible) return
        EidosChatSendWorker.notifyReplyReady(
            context = appRef,
            conversationId = conversation.id,
            title = conversation.title.ifBlank { "Eidos" },
            text = replyText,
            launchTarget = replyNotificationLaunchTarget(conversation),
        )
    }

    private fun replyNotificationLaunchTarget(conversation: Conversation): EidosReplyNotificationTarget {
        if (entrySurface != EidosChatEntrySurface.WIDGET) {
            return EidosReplyNotificationTarget.MAIN_APP
        }
        if (ConversationScopes.isWebScope(conversation.scopeType)) {
            return EidosReplyNotificationTarget.MAIN_APP
        }
        return EidosReplyNotificationTarget.WIDGET_CHAT
    }

    private fun syncActiveConversationFromDatabase() {
        val conversationId = activeConversationId ?: return
        if (EidosActiveSendRegistry.isActive(conversationId)) return
        val loadEpoch = conversationLoadEpoch
        viewModelScope.launch {
            if (EidosActiveSendRegistry.isActive(conversationId)) return@launch
            loadConversationInternal(conversationId, resetProviderChain = false, loadEpoch = loadEpoch)
            if (!isConversationLoadCurrent(loadEpoch)) return@launch
            refreshSummaries()
        }
    }

    /** Avoid duplicate bubbles when a DB reload races with optimistic send inserts. */
    private fun appendUiMessageIfAbsent(message: EidosUiMessage) {
        if (message.id > 0 && _messages.value.any { it.id == message.id }) return
        _messages.value = _messages.value + message
    }

    /** Replace the edited/retried user bubble and drop later turns immediately. */
    private fun replaceUserMessageAndDropFollowing(messageId: Long, newText: String) {
        val current = _messages.value
        val idx = current.indexOfFirst { it.id == messageId }
        if (idx < 0) return
        _messages.value = current.take(idx + 1).mapIndexed { index, message ->
            if (index == idx) message.copy(text = newText) else message
        }
        _streamPreview.value = null
    }

    private fun finishSendExchange(conversationId: Long?, sendJob: Job?) {
        if (conversationId != null && sendJob != null) {
            EidosActiveSendRegistry.unregisterIfOwned(conversationId, sendJob)
        }
        if (sendJob != null && apiExchangeJob === sendJob) {
            apiExchangeJob = null
        }
        if (conversationId == null || !EidosActiveSendRegistry.isActive(conversationId)) {
            if (activeConversationId == conversationId || conversationId == null) {
                _isSending.value = false
            }
        }
    }

    private suspend fun appendErrorMessageIfNeeded(conversationId: Long) {
        if (!isActiveConversation(conversationId)) return
        val lastDbRole = db.chatMessageDao().getAllByConversation(conversationId).lastOrNull()?.role
        if (lastDbRole == "eidos") return
        appendErrorMessage(conversationId)
    }

    private fun appendErrorMessage(conversationId: Long) {
        if (!isActiveConversation(conversationId)) return
        _messages.value = _messages.value + EidosUiMessage(
            id = -1L,
            role = EidosRole.ASSISTANT,
            text = "I hit an error while sending that. Please try again.",
            timeLabel = messageTimeFormatter.format(Instant.ofEpochMilli(System.currentTimeMillis())),
        )
    }

    /** After user stops an in-flight request: short assistant line when the last bubble is still the user message. */
    private suspend fun appendStoppedReplyIfNeeded(conversation: Conversation) {
        if (!isActiveConversation(conversation.id)) return
        val last = _messages.value.lastOrNull() ?: return
        if (last.role != EidosRole.USER) return
        val now = System.currentTimeMillis()
        val text = "Request stopped."
        val replyMsgId = db.chatMessageDao().insert(
            ChatMessage(conversationId = conversation.id, role = "eidos", content = text, createdAt = now),
        )
        appendUiMessageIfAbsent(
            EidosUiMessage(
                id = replyMsgId,
                role = EidosRole.ASSISTANT,
                text = text,
                timeLabel = messageTimeFormatter.format(Instant.ofEpochMilli(now)),
            ),
        )
        db.conversationDao().update(conversation.copy(updatedAt = now))
        requestRetrievalSync("conversation_stopped_reply:${conversation.id}")
    }

    private suspend fun insertVisionMissingBytesReply(conversation: Conversation) {
        val now = System.currentTimeMillis()
        val text = ChatVisionAttachmentCodec.MISSING_BYTES_REPLY
        val replyMsgId = db.chatMessageDao().insert(
            ChatMessage(
                conversationId = conversation.id,
                role = "eidos",
                content = text,
                createdAt = now,
            ),
        )
        appendUiMessageIfAbsent(
            EidosUiMessage(
                id = replyMsgId,
                role = EidosRole.ASSISTANT,
                text = text,
                timeLabel = messageTimeFormatter.format(Instant.ofEpochMilli(now)),
            ),
        )
        db.conversationDao().update(conversation.copy(updatedAt = now))
    }

    // ── Ensure conversation (lazy creation on first send) ─────────────────────

    private suspend fun ensureConversation(timestamp: Long, firstUserText: String): Conversation {
        activeConversationId?.let { id ->
            db.conversationDao().getById(id)?.let { return it }
        }
        when (val s = viewedScope) {
            is ConversationScope.WebEditor -> {
                val key = s.searchKey ?: activeWebSearchKey
                    ?: error("Web chat requires an active search before sending")
                db.conversationDao().getByWebEditorSearch(s.subfolderId, key)?.let { existing ->
                    activeConversationId = existing.id
                    _hasActiveConversation.value = true
                    return existing
                }
                val title = activeWebSearchDisplay
                    ?: displayWebSearchTitle(firstUserText)
                val newConv = Conversation(
                    scopeType = ConversationScopes.WEB_EDITOR,
                    subfolderId = s.subfolderId,
                    webSearchKey = key,
                    title = title,
                    createdAt = timestamp,
                    updatedAt = timestamp,
                )
                val id = db.conversationDao().insert(newConv)
                activeConversationId = id
                _hasActiveConversation.value = true
                return newConv.copy(id = id)
            }
            is ConversationScope.WebWidget -> {
                val key = activeWebSearchKey
                    ?: error("Web chat requires an active search before sending")
                db.conversationDao().getByWebWidgetSearch(key)?.let { existing ->
                    activeConversationId = existing.id
                    _hasActiveConversation.value = true
                    return existing
                }
                val title = activeWebSearchDisplay
                    ?: displayWebSearchTitle(firstUserText)
                val newConv = Conversation(
                    scopeType = ConversationScopes.WEB_WIDGET,
                    webSearchKey = key,
                    title = title,
                    createdAt = timestamp,
                    updatedAt = timestamp,
                )
                val id = db.conversationDao().insert(newConv)
                activeConversationId = id
                _hasActiveConversation.value = true
                return newConv.copy(id = id)
            }
            else -> Unit
        }
        val title = buildConversationTitleFromText(firstUserText)
            .ifBlank { conversationTitleFormatter.format(Instant.ofEpochMilli(timestamp)) }
        val newConv = when (val s = viewedScope) {
            is ConversationScope.General ->
                Conversation(scopeType = "general", title = title, createdAt = timestamp, updatedAt = timestamp)
            is ConversationScope.ParentFolder ->
                Conversation(scopeType = "parent", parentFolderId = s.id, title = title, createdAt = timestamp, updatedAt = timestamp)
            is ConversationScope.Subfolder ->
                Conversation(scopeType = "subfolder", subfolderId = s.id, title = title, createdAt = timestamp, updatedAt = timestamp)
            is ConversationScope.Workshop ->
                Conversation(scopeType = ConversationScopes.PANEL_WORKSHOP, subfolderId = s.subfolderId, title = title, createdAt = timestamp, updatedAt = timestamp)
            is ConversationScope.QuickNotesRoot ->
                Conversation(scopeType = "quick_notes_root", parentFolderId = s.parentId, title = title, createdAt = timestamp, updatedAt = timestamp)
            is ConversationScope.QuickNotesDay ->
                Conversation(scopeType = "quick_notes_day", subfolderId = s.subfolderId, title = title, createdAt = timestamp, updatedAt = timestamp)
            is ConversationScope.DumpEdit ->
                Conversation(scopeType = ConversationScopes.DUMP_EDIT, title = title, createdAt = timestamp, updatedAt = timestamp)
            is ConversationScope.PanelGallery ->
                Conversation(scopeType = ConversationScopes.PANEL_GALLERY, title = title, createdAt = timestamp, updatedAt = timestamp)
            is ConversationScope.PanelRunner ->
                Conversation(
                    scopeType = ConversationScopes.PANEL_RUNNER,
                    subfolderId = s.subfolderId,
                    title = title,
                    createdAt = timestamp,
                    updatedAt = timestamp,
                )
            is ConversationScope.ImageStudio ->
                Conversation(
                    scopeType = ConversationScopes.IMAGE_STUDIO,
                    subfolderId = s.saveSubfolderId,
                    title = title,
                    createdAt = timestamp,
                    updatedAt = timestamp,
                )
            is ConversationScope.WebEditor, is ConversationScope.WebWidget ->
                error("unreachable")
        }
        val id = db.conversationDao().insert(newConv)
        requestRetrievalSync("conversation_created:$id")
        activeConversationId = id
        _hasActiveConversation.value = true
        val inserted = newConv.copy(id = id)
        persistConversationPointerIfNeeded(inserted)
        return inserted
    }

    private suspend fun maybeRetitleConversation(
        conversation: Conversation,
        seedText: String,
        now: Long = System.currentTimeMillis(),
    ): Conversation {
        if (!looksLikeAutoTimestampTitle(conversation.title)) return conversation
        val generatedTitle = buildConversationTitleFromText(seedText)
        if (generatedTitle.isBlank()) return conversation
        val updatedConversation = conversation.copy(title = generatedTitle, updatedAt = now)
        db.conversationDao().update(updatedConversation)
        requestRetrievalSync("conversation_auto_retitle:${conversation.id}")
        return updatedConversation
    }

    // ── Input / settings ──────────────────────────────────────────────────────

    fun setInput(value: String) { _input.value = value }

    fun setReadAloud(enabled: Boolean) {
        viewModelScope.launch {
            appRef.settingsDataStore.edit { it[SettingsKeys.READ_ALOUD] = enabled }
        }
    }

    fun setReadAloudMicPassback(enabled: Boolean) {
        viewModelScope.launch {
            appRef.settingsDataStore.edit { it[SettingsKeys.READ_ALOUD_MIC_PASSBACK] = enabled }
        }
    }

    fun setReadAloudInfoDismissed(dismissed: Boolean) {
        viewModelScope.launch {
            appRef.settingsDataStore.edit { it[SettingsKeys.READ_ALOUD_INFO_DISMISSED] = dismissed }
        }
    }

    fun setLocalGemmaToolsEnabled(enabled: Boolean) {
        viewModelScope.launch {
            appRef.settingsDataStore.edit { it[SettingsKeys.LOCAL_GEMMA_TOOLS_ENABLED] = enabled }
        }
    }

    fun refreshOpenAiKeyPresence() {
        _hasOpenAiApiKey.value = readHasOpenAiApiKey()
    }

    fun setMicUseWhisperApi(enabled: Boolean) {
        if (enabled && !readHasOpenAiApiKey()) return
        viewModelScope.launch {
            appRef.settingsDataStore.edit {
                it[SettingsKeys.MIC_USE_WHISPER_API] = enabled
                if (enabled) it[SettingsKeys.MIC_USE_LOCAL_GEMMA_SCRIBE] = false
            }
            applyLitertEngineWarmState(appRef)
        }
    }

    fun setMicUseLocalGemmaScribe(enabled: Boolean) {
        viewModelScope.launch {
            appRef.settingsDataStore.edit {
                it[SettingsKeys.MIC_USE_LOCAL_GEMMA_SCRIBE] = enabled
                if (enabled) it[SettingsKeys.MIC_USE_WHISPER_API] = false
            }
            applyLitertEngineWarmState(appRef)
        }
    }

    fun setEidosThinkingLevel(level: EidosThinkingLevel) {
        viewModelScope.launch {
            appRef.settingsDataStore.edit { it[SettingsKeys.EIDOS_THINKING_LEVEL] = level.wire }
        }
    }

    fun setActiveProvider(value: String) {
        viewModelScope.launch {
            getEncryptedPrefs(appRef).edit()
                .putString(EncryptedSettingKeys.ACTIVE_PROVIDER, value)
                .apply()
            appRef.settingsDataStore.edit { it[SettingsKeys.ACTIVE_PROVIDER] = value }
            applyLitertEngineWarmState(appRef)
        }
    }

    fun setRereadMessageId(id: Long?) { _rereadMessageId.value = id }

    private fun readHasOpenAiApiKey(): Boolean =
        !getEncryptedPrefs(appRef).getString(ApiKeyNames.OPENAI, null).isNullOrBlank()

    /** Resend a user message without opening the edit dialog. */
    fun retryMessage(messageId: Long, text: String = "") {
        if (_isSending.value || apiExchangeJob?.isActive == true) {
            postToast("Wait for the current reply to finish before retrying.")
            return
        }
        val trimmed = text.trim().ifBlank {
            _messages.value.firstOrNull { it.id == messageId }?.text?.trim().orEmpty()
        }
        if (trimmed.isBlank() || messageId <= 0L) return
        api.resetConnections()
        editMessage(messageId, trimmed)
    }

    /**
     * Edit a previously sent user message. All messages after it are hard-deleted,
     * the edited content is saved, and the conversation is ready for the next send.
     */
    fun editMessage(messageId: Long, newText: String) {
        val trimmed = newText.trim()
        if (trimmed.isBlank() || _isSending.value || apiExchangeJob?.isActive == true) return
        replaceUserMessageAndDropFollowing(messageId, trimmed)
        _isSending.value = true
        val job = appRef.eidosSendScope.launch {
            suppressStoppedReplyOnCancellation = false
            var conversation: Conversation? = null
            val sendJob = coroutineContext[Job]
            try {
                val msg = db.chatMessageDao().getById(messageId) ?: return@launch
                coroutineContext[Job]?.let { EidosActiveSendRegistry.register(msg.conversationId, it) }
                val isEditingFirstMessage =
                    db.chatMessageDao().getFirstMessage(msg.conversationId)?.id == messageId
                val attached = ChatVisionAttachmentCodec.parseAttachmentJson(msg.imageAttachmentJson)
                db.chatMessageDao().updateContent(messageId, trimmed)
                db.chatMessageDao().deleteAfterConversationOrder(
                    conversationId = msg.conversationId,
                    createdAt = msg.createdAt,
                    messageId = messageId,
                )
                requestRetrievalSync("conversation_truncated_for_edit:${msg.conversationId}")
                activeConversationId = msg.conversationId
                previousResponseId = null
                loadConversationInternal(msg.conversationId)
                var conv = db.conversationDao().getById(msg.conversationId) ?: return@launch
                conversation = conv
                if (isEditingFirstMessage) {
                    conv = maybeRetitleConversation(conv, trimmed)
                    conversation = conv
                }
                callApiAndInsertReply(conv, trimmed, attached, messageId)
                refreshSummaries()
            } catch (ce: CancellationException) {
                if (!suppressStoppedReplyOnCancellation) {
                    conversation?.let { appendStoppedReplyIfNeeded(it) }
                }
                throw ce
            } catch (t: Throwable) {
                conversation?.let { appendErrorMessageIfNeeded(it.id) }
            } finally {
                finishSendExchange(conversation?.id, sendJob)
                suppressStoppedReplyOnCancellation = false
            }
        }
        apiExchangeJob = job
    }

    /** Called when the bottom sheet is dismissed — conversation persists; in-flight sends keep running. */
    fun endSession() { /* no-op: conversation stays active until newChat() */ }

    // ── Helpers ───────────────────────────────────────────────────────────────

    private fun ChatMessage.toUiMessage() = EidosUiMessage(
        id = id,
        role = if (role == "user") EidosRole.USER else EidosRole.ASSISTANT,
        text = content,
        timeLabel = messageTimeFormatter.format(Instant.ofEpochMilli(createdAt)),
        reasoningText = assistantReasoningContent?.takeIf { it.isNotBlank() },
        navigationTargets = EidosNavigationCodec.parseTargetsJson(navigationTargetsJson),
        imageAttachment = ChatVisionAttachmentCodec.parseAttachmentJson(imageAttachmentJson),
    )

    private suspend fun refreshHistoryDirectoryOptions() {
        val options = buildList {
            add(ConversationDirectoryOption(ConversationDirectory.RECENT, "Recent"))
            historyHereChipLabel(viewedScope)?.let { label ->
                add(ConversationDirectoryOption(ConversationDirectory.HERE, label))
            }
            add(ConversationDirectoryOption(ConversationDirectory.GENERAL, "General"))
            add(ConversationDirectoryOption(ConversationDirectory.PARENT, "Parent"))
        }
        _historyDirectoryOptions.value = options
        if (options.none { it.directory == _selectedHistoryDirectory.value }) {
            _selectedHistoryDirectory.value = defaultHistoryDirectory()
            if (options.none { it.directory == _selectedHistoryDirectory.value }) {
                _selectedHistoryDirectory.value = ConversationDirectory.RECENT
            }
        }
    }

    private suspend fun refreshHistoryLocationTargets() {
        when (_selectedHistoryDirectory.value) {
            ConversationDirectory.HERE -> {
                _historyParentTargets.value = emptyList()
                _selectedHistoryParentId.value = null
                _historyLocationTargets.value = emptyList()
                _selectedHistoryLocationId.value = null
            }
            ConversationDirectory.RECENT -> {
                _historyParentTargets.value = emptyList()
                _selectedHistoryParentId.value = null
                _historyLocationTargets.value = emptyList()
                _selectedHistoryLocationId.value = null
            }
            ConversationDirectory.GENERAL -> {
                _historyParentTargets.value = emptyList()
                _selectedHistoryParentId.value = null
                _historyLocationTargets.value = emptyList()
                _selectedHistoryLocationId.value = null
            }
            ConversationDirectory.PARENT -> {
                val parents = db.parentFolderDao().getActiveUserFolders().first()
                val targets = parents.map { parent ->
                    ConversationLocationTarget(id = parent.id, label = parent.name)
                }
                _historyParentTargets.value = targets
                if (_selectedHistoryParentId.value !in targets.map { it.id }) {
                    _selectedHistoryParentId.value = targets.firstOrNull()?.id
                }
                _selectedHistoryLocationId.value = _selectedHistoryParentId.value
                selectedParentDirectoryId = _selectedHistoryParentId.value
                _historyLocationTargets.value = emptyList()
            }
            ConversationDirectory.SUBFOLDER -> {
                val fallbackParentId = db.parentFolderDao()
                    .getActiveUserFolders()
                    .first()
                    .firstOrNull()
                    ?.id
                val parents = db.parentFolderDao().getActiveUserFolders().first()
                val parentTargets = parents.map { parent ->
                    ConversationLocationTarget(id = parent.id, label = parent.name)
                }
                _historyParentTargets.value = parentTargets
                val parentId = _selectedHistoryParentId.value ?: selectedParentDirectoryId ?: when (val s = viewedScope) {
                    is ConversationScope.ParentFolder -> s.id
                    is ConversationScope.Subfolder -> db.subfolderDao().getById(s.id)?.parentFolderId
                    is ConversationScope.Workshop -> db.subfolderDao().getById(s.subfolderId)?.parentFolderId
                    is ConversationScope.General -> fallbackParentId
                    is ConversationScope.QuickNotesRoot -> s.parentId
                    is ConversationScope.QuickNotesDay -> db.subfolderDao().getById(s.subfolderId)?.parentFolderId
                    is ConversationScope.WebEditor -> db.subfolderDao().getById(s.subfolderId)?.parentFolderId
                    is ConversationScope.WebWidget,
                    is ConversationScope.DumpEdit,
                    is ConversationScope.PanelGallery,
                    -> fallbackParentId
                    is ConversationScope.PanelRunner -> db.subfolderDao().getById(s.subfolderId)?.parentFolderId
                    is ConversationScope.ImageStudio -> db.subfolderDao().getById(s.saveSubfolderId)?.parentFolderId
                } ?: fallbackParentId
                _selectedHistoryParentId.value = parentId
                selectedParentDirectoryId = parentId
                val subfolders = if (parentId == null) {
                    emptyList()
                } else {
                    db.subfolderDao()
                        .getAllByParentOnce(parentId)
                        .filter { it.deletedAt == null && !it.isSystemSubfolder }
                }
                val targets = subfolders.map { subfolder ->
                    ConversationLocationTarget(id = subfolder.id, label = subfolder.name)
                }
                _historyLocationTargets.value = targets
                if (_selectedHistoryLocationId.value !in targets.map { it.id }) {
                    _selectedHistoryLocationId.value = targets.firstOrNull()?.id
                }
            }
        }
    }

    private fun defaultHistoryDirectory(): ConversationDirectory {
        if (supportsHereDirectory(viewedScope)) return ConversationDirectory.HERE
        return ConversationDirectory.RECENT
    }

    private fun scopeToDirectory(scope: ConversationScope): ConversationDirectory {
        if (supportsHereDirectory(scope)) return ConversationDirectory.HERE
        return when (scope) {
            is ConversationScope.General -> ConversationDirectory.GENERAL
            is ConversationScope.DumpEdit -> ConversationDirectory.GENERAL
            is ConversationScope.PanelGallery -> ConversationDirectory.GENERAL
            is ConversationScope.WebEditor, is ConversationScope.WebWidget -> ConversationDirectory.RECENT
            else -> ConversationDirectory.RECENT
        }
    }

    private fun supportsHereDirectory(scope: ConversationScope): Boolean = when (scope) {
        is ConversationScope.General,
        is ConversationScope.DumpEdit,
        is ConversationScope.PanelGallery,
        is ConversationScope.WebEditor,
        is ConversationScope.WebWidget,
        -> false
        else -> true
    }

    private suspend fun historyHereChipLabel(scope: ConversationScope): String? {
        if (!supportsHereDirectory(scope)) return null
        return when (scope) {
            is ConversationScope.ParentFolder ->
                db.parentFolderDao().getById(scope.id)?.name ?: "Here"
            is ConversationScope.Subfolder ->
                db.subfolderDao().getById(scope.id)?.name ?: "Here"
            is ConversationScope.QuickNotesRoot -> "Quick Notes"
            is ConversationScope.QuickNotesDay ->
                quickNotesDayLabel
                    ?: db.subfolderDao().getById(scope.subfolderId)?.name
                    ?: "Quick Notes"
            is ConversationScope.Workshop ->
                db.subfolderDao().getById(scope.subfolderId)?.name ?: "Workshop"
            is ConversationScope.PanelRunner ->
                panelRunnerScopeLabel
                    ?: db.subfolderDao().getById(scope.subfolderId)?.name
                    ?: "Panel"
            is ConversationScope.ImageStudio ->
                if (scope.hub) "All Images" else imageStudioScopeLabel ?: "Image Studio"
            else -> "Here"
        }
    }

    private suspend fun historyHereContextLabel(scope: ConversationScope): String {
        val label = historyHereChipLabel(scope) ?: "Here"
        return when (scope) {
            is ConversationScope.Subfolder -> {
                val parentName = db.subfolderDao().getById(scope.id)?.parentFolderId?.let { parentId ->
                    db.parentFolderDao().getById(parentId)?.name
                }
                if (parentName != null) "Chats / $parentName / $label" else "Chats / $label"
            }
            is ConversationScope.ImageStudio -> "Chats / Image Studio · $label"
            else -> "Chats / $label"
        }
    }

    private suspend fun conversationsForHereScope(
        scope: ConversationScope,
        limit: Int = 5,
    ): List<Conversation> = when (scope) {
        is ConversationScope.ParentFolder ->
            db.conversationDao().getRecentByParentFolder(scope.id, limit)
        is ConversationScope.Subfolder ->
            db.conversationDao().getRecentBySubfolder(scope.id, limit)
        is ConversationScope.QuickNotesRoot ->
            db.conversationDao().getRecentQuickNotesRoot(scope.parentId, limit)
        is ConversationScope.QuickNotesDay ->
            db.conversationDao().getRecentQuickNotesDay(scope.subfolderId, limit)
        is ConversationScope.Workshop ->
            db.conversationDao().getRecentPanelWorkshop(scope.subfolderId, limit)
        is ConversationScope.PanelRunner ->
            db.conversationDao().getRecentPanelRunner(scope.subfolderId, limit)
        is ConversationScope.ImageStudio ->
            db.conversationDao().getRecentImageStudio(scope.saveSubfolderId, limit)
        else -> emptyList()
    }

    private fun updateChatScopeLabel() {
        _chatScopeLabel.value = when (currentScope) {
            is ConversationScope.General -> "General"
            is ConversationScope.ParentFolder -> "Parent"
            is ConversationScope.Subfolder -> "Subfolder"
            is ConversationScope.Workshop -> "Panel Workshop"
            is ConversationScope.DumpEdit -> "DumpEdit"
            is ConversationScope.PanelGallery -> "Panel Gallery"
            is ConversationScope.PanelRunner -> {
                val name = panelRunnerScopeLabel
                    ?: (currentScope as? ConversationScope.PanelRunner)?.subfolderId?.toString()
                if (name.isNullOrBlank()) "Panel" else "Panel: $name"
            }
            is ConversationScope.ImageStudio -> {
                val scope = currentScope as? ConversationScope.ImageStudio
                val suffix = if (scope?.hub == true) {
                    "All Images"
                } else {
                    imageStudioScopeLabel ?: scope?.saveSubfolderId?.toString()
                }
                if (suffix.isNullOrBlank()) "Image Studio" else "Image Studio · $suffix"
            }
            is ConversationScope.QuickNotesRoot -> "Quick Notes"
            is ConversationScope.QuickNotesDay -> {
                val dayLabel = quickNotesDayLabel ?: (currentScope as? ConversationScope.QuickNotesDay)?.subfolderId?.toString()
                if (dayLabel.isNullOrBlank()) "Quick Notes / Day" else "Quick Notes / $dayLabel"
            }
            is ConversationScope.WebEditor -> {
                val query = activeWebSearchDisplay ?: activeWebSearchKey
                if (query.isNullOrBlank()) "Web" else "Web / $query"
            }
            is ConversationScope.WebWidget -> {
                val query = activeWebSearchDisplay ?: activeWebSearchKey
                if (query.isNullOrBlank()) "Widget Web" else "Widget Web / $query"
            }
        }
    }

    private fun Conversation.toScope(): ConversationScope {
        return when (scopeType) {
            ConversationScopes.PARENT -> ConversationScope.ParentFolder(parentFolderId ?: 0L)
            ConversationScopes.SUBFOLDER -> ConversationScope.Subfolder(subfolderId ?: 0L)
            ConversationScopes.PANEL_WORKSHOP -> ConversationScope.Workshop(subfolderId ?: 0L)
            ConversationScopes.QUICK_NOTES_ROOT -> ConversationScope.QuickNotesRoot(parentFolderId ?: 0L)
            ConversationScopes.QUICK_NOTES_DAY -> ConversationScope.QuickNotesDay(subfolderId ?: 0L)
            ConversationScopes.WEB_EDITOR -> ConversationScope.WebEditor(
                subfolderId = subfolderId ?: 0L,
                searchKey = webSearchKey,
            )
            ConversationScopes.WEB_WIDGET -> ConversationScope.WebWidget
            ConversationScopes.DUMP_EDIT -> ConversationScope.DumpEdit
            ConversationScopes.PANEL_GALLERY -> ConversationScope.PanelGallery
            ConversationScopes.PANEL_RUNNER -> ConversationScope.PanelRunner(subfolderId ?: 0L)
            ConversationScopes.IMAGE_STUDIO -> ConversationScope.ImageStudio(
                hub = imageStudioHubMode,
                saveSubfolderId = subfolderId ?: 0L,
            )
            else -> ConversationScope.General
        }
    }

    private fun isWebScope(scope: ConversationScope): Boolean =
        scope is ConversationScope.WebEditor || scope is ConversationScope.WebWidget

    private fun scopeTypeForApi(scope: ConversationScope): String = when (scope) {
        is ConversationScope.General -> ConversationScopes.GENERAL
        is ConversationScope.ParentFolder -> ConversationScopes.PARENT
        is ConversationScope.Subfolder -> ConversationScopes.SUBFOLDER
        is ConversationScope.QuickNotesRoot -> ConversationScopes.QUICK_NOTES_ROOT
        is ConversationScope.QuickNotesDay -> ConversationScopes.QUICK_NOTES_DAY
        is ConversationScope.WebEditor -> ConversationScopes.WEB_EDITOR
        is ConversationScope.WebWidget -> ConversationScopes.WEB_WIDGET
        is ConversationScope.Workshop -> ConversationScopes.PANEL_WORKSHOP
        is ConversationScope.DumpEdit -> ConversationScopes.DUMP_EDIT
        is ConversationScope.PanelGallery -> ConversationScopes.PANEL_GALLERY
        is ConversationScope.PanelRunner -> ConversationScopes.PANEL_RUNNER
        is ConversationScope.ImageStudio -> ConversationScopes.IMAGE_STUDIO
    }




    private fun requestRetrievalSync(reason: String) {
        semanticSync.requestSync(reason)
    }
}
