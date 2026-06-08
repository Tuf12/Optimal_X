package com.example.optimalx.ui.eidos

import android.app.Application
import android.util.Log
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
import com.example.optimalx.data.eidos.EidosActiveSendRegistry
import com.example.optimalx.data.eidos.EidosChatSendWorker
import com.example.optimalx.data.eidos.EidosContextLimits
import com.example.optimalx.data.eidos.ChatMessageHistoryLoader
import com.example.optimalx.data.eidos.ChatMessagePersistLimits

import com.example.optimalx.data.eidos.WorkshopEidosModeResolver
import com.example.optimalx.data.eidos.WorkshopIntakeSummary
import com.example.optimalx.data.eidos.toEidosApiHistoryExcludingLatestUser
import com.example.optimalx.data.eidos.model.ConfirmationHandler
import com.example.optimalx.data.eidos.model.EidosMessage
import com.example.optimalx.data.eidos.model.EidosRole
import com.example.optimalx.data.eidos.model.EidosStreamListener
import com.example.optimalx.data.eidos.model.EidosStreamUpdate
import com.example.optimalx.data.eidos.model.persistableReasoningContent
import com.example.optimalx.data.model.ChatMessage
import com.example.optimalx.data.model.Conversation
import com.example.optimalx.data.eidos.PanelPlatformSpec
import com.example.optimalx.data.eidos.WorkshopDocAlignScope
import com.example.optimalx.data.eidos.ImplementationPlanGate
import com.example.optimalx.data.eidos.WorkshopAutoContinue
import com.example.optimalx.data.eidos.WorkshopBuildKickoff
import com.example.optimalx.data.eidos.WorkshopChunkedRunState
import com.example.optimalx.data.eidos.WorkshopContinueTicket
import com.example.optimalx.data.eidos.WorkshopExecutionProfile
import com.example.optimalx.data.eidos.WorkshopHandoffParser
import com.example.optimalx.data.eidos.WorkshopToolRoundPause
import com.example.optimalx.data.eidos.model.EidosResponse
import com.example.optimalx.data.revision.SCOPE_WORKSHOP_PROJECT
import com.example.optimalx.data.revision.WorkshopReviewPolicy
import com.example.optimalx.data.eidos.WorkshopEidosMode
import com.example.optimalx.data.eidos.WorkshopProjectPhase
import com.example.optimalx.data.eidos.WorkshopUpdateSection
import com.example.optimalx.data.model.ConversationScopes
import com.example.optimalx.data.preferences.WorkshopProjectPreferences
import com.example.optimalx.ui.web.displayWebSearchTitle
import com.example.optimalx.ui.web.normalizeWebSearchKey
import com.example.optimalx.ui.workshop.WorkshopUpdateCompletion
import com.example.optimalx.data.preferences.ChatSessionPointers
import com.example.optimalx.data.preferences.ApiKeyNames
import com.example.optimalx.data.preferences.SettingsDefaults
import com.example.optimalx.data.preferences.SettingsKeys
import com.example.optimalx.data.preferences.getEncryptedPrefs
import com.example.optimalx.data.preferences.settingsDataStore
import com.example.optimalx.widget.WidgetPrefs
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
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
}

enum class ConversationDirectory {
    RECENT,
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
    val isSyntheticHandoff: Boolean = false,
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
    private val appIndexSync = appRef.appIndexSyncService
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

    private val _isSending = MutableStateFlow(false)
    val isSending: StateFlow<Boolean> = _isSending.asStateFlow()

    private val activeEidosProvider: StateFlow<String> = appRef.settingsDataStore.data
        .map { it[SettingsKeys.ACTIVE_PROVIDER] ?: SettingsDefaults.ACTIVE_PROVIDER }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), SettingsDefaults.ACTIVE_PROVIDER)

    /** Kimi thinking models may take a long time before the first token — show activity in chat. */
    val showKimiThinkingIndicator: StateFlow<Boolean> = combine(_isSending, activeEidosProvider) { sending, provider ->
        sending && provider == "kimi"
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), false)

    private val _workshopAutoContinueActive = MutableStateFlow(false)
    val workshopAutoContinueActive: StateFlow<Boolean> = _workshopAutoContinueActive.asStateFlow()

    private var workshopChunkedRun: WorkshopChunkedRunState? = null

    private val _streamPreview = MutableStateFlow<EidosStreamUpdate?>(null)
    val streamPreview: StateFlow<EidosStreamUpdate?> = _streamPreview.asStateFlow()

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
                if (conversationId == activeConversationId) {
                    syncActiveConversationFromDatabase()
                }
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
        clearWorkshopChunkedRun()
        clearActiveWorkshopBuildKickoff()
        apiExchangeJob?.cancel(CancellationException("User stopped"))
        activeConversationId?.let { EidosActiveSendRegistry.cancel(it) }
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

    val micUseWhisperApi: StateFlow<Boolean> = app.settingsDataStore.data
        .map { it[SettingsKeys.MIC_USE_WHISPER_API] ?: SettingsDefaults.MIC_USE_WHISPER_API }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), SettingsDefaults.MIC_USE_WHISPER_API)

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

    /** Null on `Conversation` = inherit Settings default. */
    private val _activeConversationMemoryDepth = MutableStateFlow<String?>(null)

    private val settingsMemoryDepth = app.settingsDataStore.data
        .map { prefs ->
            prefs[SettingsKeys.CONVERSATION_MEMORY_DEPTH] ?: SettingsDefaults.CONVERSATION_MEMORY_DEPTH
        }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), SettingsDefaults.CONVERSATION_MEMORY_DEPTH)

    val conversationMemoryLabel: StateFlow<String> = combine(
        _activeConversationMemoryDepth,
        settingsMemoryDepth,
    ) { stored, settingsDefault ->
        EidosContextLimits.displayLabel(stored, settingsDefault)
    }.stateIn(
        viewModelScope,
        SharingStarted.WhileSubscribed(5000),
        EidosContextLimits.displayLabel(null, SettingsDefaults.CONVERSATION_MEMORY_DEPTH),
    )

    // ── Session state ─────────────────────────────────────────────────────────

    /** Page/surface the user opened chat from — drives Move Here, New Chat, and LLM context. */
    private var viewedScope: ConversationScope = ConversationScope.General
    /** Scope of the loaded conversation — header label and DB home until Move Here. */
    private var currentScope: ConversationScope = ConversationScope.General
    private var activeConversationId: Long? = null
    private var selectedParentDirectoryId: Long? = null
    private var quickNotesDayLabel: String? = null
    private var panelRunnerScopeLabel: String? = null
    /** In-memory provider response chain ID — cleared on scope/conversation change. */
    private var previousResponseId: String? = null

    private var activeWebSearchKey: String? = null
    private var activeWebSearchDisplay: String? = null

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
            mode.isBuildFamily || mode.isPlanBuildKickoff -> WorkshopEidosMode.EDIT
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

    private val _implementationPlanGateTick = MutableStateFlow(0)

    /** Show **Accept plan** after Diff Review when IMPLEMENTATION_PLAN.md is ready but not yet accepted. */
    val workshopShowAcceptPlanBanner: StateFlow<Boolean> = combine(
        _workshopScopeSubfolderId,
        _workshopProjectPhase,
        workshopPendingChangeCount,
        _implementationPlanGateTick,
    ) { subId, phase, pendingCount, _ ->
        if (subId == null || phase != WorkshopProjectPhase.UPDATE || pendingCount > 0) {
            return@combine false
        }
        ImplementationPlanGate.needsAcceptance(getApplication(), subId)
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), false)

    /** Show **Build plan** banner in workshop chat (UPDATE + accepted plan + no pending diffs). */
    val workshopShowBuildPlanBanner: StateFlow<Boolean> = combine(
        _workshopScopeSubfolderId,
        _workshopProjectPhase,
        workshopPendingChangeCount,
        _implementationPlanGateTick,
    ) { subId, phase, pendingCount, _ ->
        if (subId == null || phase != WorkshopProjectPhase.UPDATE || pendingCount > 0) {
            return@combine false
        }
        val app = getApplication<Application>()
        val content = ImplementationPlanGate.readContent(app, subId)
        val accepted = WorkshopProjectPreferences.isImplementationPlanAccepted(app, subId)
        val hash = WorkshopProjectPreferences.getImplementationPlanAcceptedContentHash(app, subId)
        ImplementationPlanGate.acceptanceMatchesContent(accepted, hash, content)
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), false)

    /** Accept [IMPLEMENTATION_PLAN.md] from chat (same gate as workshop top bar **Accept plan**). */
    fun acceptImplementationPlan(workshopSubfolderId: Long) {
        val app = getApplication<Application>()
        val content = ImplementationPlanGate.readContent(app, workshopSubfolderId) ?: return
        if (!ImplementationPlanGate.isSubstantive(content)) return
        WorkshopProjectPreferences.setImplementationPlanAccepted(
            app,
            workshopSubfolderId,
            accepted = true,
            contentHash = ImplementationPlanGate.contentHash(content),
        )
        refreshImplementationPlanGate()
    }

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
        refreshImplementationPlanGate()
    }

    fun refreshImplementationPlanGate() {
        _implementationPlanGateTick.value += 1
    }

    fun setWorkshopEidosMode(mode: WorkshopEidosMode) {
        if (mode !in WorkshopEidosMode.USER_CHIP_MODES) return
        val subfolderId = _workshopScopeSubfolderId.value ?: return
        clearActiveWorkshopBuildKickoff(subfolderId)
        _workshopEidosModeOverride.value = mode
        WorkshopProjectPreferences.setEidosModeOverride(getApplication(), subfolderId, mode)
    }

    /**
     * Clears a stale one-shot build kickoff so Chat / Plan / Edit chips work again.
     * BUILD_PLAN in particular locks [resolveWorkshopEidosModeForSend] until kickoff is cleared.
     */
    private fun clearActiveWorkshopBuildKickoff(subfolderId: Long? = _workshopScopeSubfolderId.value) {
        val id = subfolderId ?: return
        WorkshopProjectPreferences.clearBuildKickoff(appRef, id)
        val stored = WorkshopProjectPreferences.getEidosModeOverride(appRef, id)
        if (stored != null && (stored.isBuildFamily || !stored.visibleInSelector)) {
            WorkshopProjectPreferences.setEidosModeOverride(appRef, id, WorkshopEidosMode.EDIT)
            if (_workshopScopeSubfolderId.value == id) {
                _workshopEidosModeOverride.value = WorkshopEidosMode.EDIT
            }
        }
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
        WorkshopEidosModeResolver.modeForDocAlign(_pendingDocAlignScope.value)?.let { return it }
        val phase = _workshopProjectPhase.value
        val subfolderId = _workshopScopeSubfolderId.value
        val app = getApplication<Application>()
        val activeKickoff = subfolderId?.let { WorkshopProjectPreferences.getBuildKickoff(app, it) }
        WorkshopEidosModeResolver.modeForActiveBuildKickoff(phase, activeKickoff)?.let { return it }
        val mode = workshopEidosMode.value
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
        val messages = ChatMessageHistoryLoader.forUi(db.chatMessageDao(), conv.id)
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
        sendMessage()
    }

    /** After specs accepted — layout shell (runtime files only). */
    fun sendWorkshopBuildDesignKickoff(workshopSubfolderId: Long) {
        val app = getApplication<Application>()
        WorkshopProjectPreferences.setBuildKickoff(
            app,
            workshopSubfolderId,
            WorkshopBuildKickoff.DESIGN,
            commit = true,
        )
        WorkshopProjectPreferences.setEidosModeOverride(
            app,
            workshopSubfolderId,
            WorkshopEidosMode.BUILD_DESIGN,
            commit = true,
        )
        setWorkshopScope(workshopSubfolderId)
        _workshopEidosModeOverride.value = WorkshopEidosMode.BUILD_DESIGN
        val footer = PanelPlatformSpec.workshopBuildDesignKickoffFooter()
        val text = buildString {
            append("Build design — layout shell from accepted specs (do not read or write .md files):\n\n")
            append(footer)
        }
        _input.value = text
        sendMessage()
    }

    /** UPDATE — execute next phase from accepted IMPLEMENTATION_PLAN.md (runtime only). */
    fun sendWorkshopBuildFromPlanKickoff(workshopSubfolderId: Long) {
        val app = getApplication<Application>()
        WorkshopProjectPreferences.setBuildKickoff(
            app,
            workshopSubfolderId,
            WorkshopBuildKickoff.PLAN,
            commit = true,
        )
        WorkshopProjectPreferences.setEidosModeOverride(
            app,
            workshopSubfolderId,
            WorkshopEidosMode.BUILD_PLAN,
            commit = true,
        )
        setWorkshopScope(workshopSubfolderId)
        _workshopEidosModeOverride.value = WorkshopEidosMode.BUILD_PLAN
        val footer = PanelPlatformSpec.workshopBuildFromPlanKickoffFooter()
        val text = buildString {
            append("Build plan — run the accepted implementation plan (all phases, Auto-Continue):\n\n")
            append(footer)
        }
        _input.value = text
        sendMessage()
    }

    /** After design accepted — wire script.js / bridge.js behavior. */
    fun sendWorkshopBuildLogicKickoff(workshopSubfolderId: Long) {
        val app = getApplication<Application>()
        WorkshopProjectPreferences.setBuildKickoff(
            app,
            workshopSubfolderId,
            WorkshopBuildKickoff.LOGIC,
            commit = true,
        )
        WorkshopProjectPreferences.setEidosModeOverride(
            app,
            workshopSubfolderId,
            WorkshopEidosMode.BUILD_LOGIC,
            commit = true,
        )
        setWorkshopScope(workshopSubfolderId)
        _workshopEidosModeOverride.value = WorkshopEidosMode.BUILD_LOGIC
        val footer = PanelPlatformSpec.workshopBuildLogicKickoffFooter()
        val text = buildString {
            append("Build logic — wire behavior from accepted design (do not read or write .md files):\n\n")
            append(footer)
        }
        _input.value = text
        sendMessage()
    }

    /** Approval gate — refresh spec .md from current code (Plan mode). */
    fun sendWorkshopAlignDocsFromCode(workshopSubfolderId: Long, scope: WorkshopDocAlignScope) {
        val app = getApplication<Application>()
        _pendingDocAlignScope.value = scope
        WorkshopProjectPreferences.setEidosModeOverride(
            app,
            workshopSubfolderId,
            WorkshopEidosMode.PLAN,
            commit = true,
        )
        setWorkshopScope(workshopSubfolderId)
        _workshopEidosModeOverride.value = WorkshopEidosMode.PLAN
        val footer = PanelPlatformSpec.workshopAlignDocsKickoffFooter(
            scope,
            _workshopUpdateSection.value,
        )
        val header = when (scope) {
            WorkshopDocAlignScope.DESIGN ->
                "Accept design — sync spec docs from code if needed (update only files that diverge):\n\n"
            WorkshopDocAlignScope.FINISH ->
                "Accept logic — sync spec docs from code if needed (update only files that diverge):\n\n"
            WorkshopDocAlignScope.UPDATE ->
                "Accept update — sync spec docs from code if needed (update only files that diverge):\n\n"
        }
        val text = buildString {
            append(header)
            append(footer)
        }
        _input.value = text
        sendMessage()
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
        val conv = db.conversationDao().getRecentQuickNotesDay(subfolderId, 1).firstOrNull()
            ?: db.conversationDao().getRecentBySubfolder(subfolderId, 1).firstOrNull()
        if (conv != null) {
            val sameThread = activeConversationId == conv.id
            loadConversationInternal(conv.id, resetProviderChain = !sameThread)
        } else {
            activeConversationId = null
            _hasActiveConversation.value = false
            previousResponseId = null
            _messages.value = emptyList()
        }
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
            ?: activeWebSearchKey
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
        _activeConversationMemoryDepth.value = conv.memoryDepth
        _hasActiveConversation.value = true
        val msgs = ChatMessageHistoryLoader.forUi(db.chatMessageDao(), conv.id)
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
                    restoreLastConversation()
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
        if (scope !is ConversationScope.PanelRunner) {
            panelRunnerScopeLabel = null
        }
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
            -> null
        }
        _selectedHistoryDirectory.value = scopeToDirectory(scope)
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
            restoreLastConversation()
            _isSending.value = activeConversationId?.let { EidosActiveSendRegistry.isActive(it) } == true
            refreshSummaries()
        }
    }

    /** True when [conversationId] is the thread currently shown in chat UI (any scope). */
    private fun isActiveConversation(conversationId: Long): Boolean =
        activeConversationId == conversationId

    /** Restore the last conversation for the current scope (stored pointers, not global recency). */
    private suspend fun restoreLastConversation() {
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
            is ConversationScope.WebEditor, is ConversationScope.WebWidget -> return
        } ?: return
        loadConversationInternal(conversation.id)
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
            is ConversationScope.WebEditor, is ConversationScope.WebWidget -> Unit
        }
    }

    // ── Conversation load / switch ────────────────────────────────────────────

    /** Load a conversation by ID — called from history browser, ConversationListScreen, or widget. */
    fun loadConversation(conversationId: Long) {
        viewModelScope.launch {
            loadConversationInternal(conversationId)
            refreshSummaries()
        }
    }

    /**
     * Open a thread from a scoped conversation list (parent / subfolder / general).
     * Sets [viewedScope] to the list location without clearing the picked conversation.
     */
    fun openConversationFromDirectory(conversationId: Long, scopeType: String, scopeId: Long) {
        viewModelScope.launch {
            applyViewedScopeFromDirectory(scopeType, scopeId)
            loadConversationInternal(conversationId)
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
    ) {
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
        refreshHistoryDirectoryOptions()
        refreshHistoryLocationTargets()
        activeConversationId = conversationId
        _activeConversationMemoryDepth.value = conversation.memoryDepth
        _hasActiveConversation.value = true
        if (resetProviderChain) {
            previousResponseId = null
        }
        val msgs = ChatMessageHistoryLoader.forUi(db.chatMessageDao(), conversationId)
        _messages.value = msgs.map { it.toUiMessage() }
        if (!ConversationScopes.isWebScope(conversation.scopeType)) {
            persistConversationPointerIfNeeded(conversation)
        }
    }

    // ── New chat / switch ─────────────────────────────────────────────────────

    fun newChat() {
        clearWorkshopChunkedRun()
        clearActiveWorkshopBuildKickoff()
        currentScope = viewedScope
        clearSubfolderEditorSurfaceIfStale(currentScope)
        updateChatScopeLabel()
        clearStoredPointersForNewChat()
        activeConversationId = null
        _activeConversationMemoryDepth.value = null
        _hasActiveConversation.value = false
        previousResponseId = null
        _selectedHistoryDirectory.value = scopeToDirectory(viewedScope)
        _messages.value = emptyList()
        viewModelScope.launch {
            refreshHistoryLocationTargets()
            refreshSummaries()
        }
    }

    /** Cycle this thread: App default → Low → Medium → High → App default. */
    fun cycleConversationMemoryDepth() {
        val conversationId = activeConversationId ?: return
        viewModelScope.launch {
            val conversation = db.conversationDao().getById(conversationId) ?: return@launch
            val next = EidosContextLimits.nextConversationMemoryDepth(conversation.memoryDepth)
            val updated = conversation.copy(memoryDepth = next)
            db.conversationDao().update(updated)
            _activeConversationMemoryDepth.value = next
        }
    }

    fun switchConversation(id: Long) {
        viewModelScope.launch {
            loadConversationInternal(id)
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
        _selectedHistoryDirectory.value = ConversationDirectory.RECENT
        viewModelScope.launch {
            refreshHistoryLocationTargets()
            refreshSummaries()
        }
    }

    fun moveConversationToViewedDirectory(conversationId: Long) {
        viewModelScope.launch {
            val conversation = db.conversationDao().getById(conversationId) ?: return@launch
            val now = System.currentTimeMillis()
            val moved = when (_selectedHistoryDirectory.value) {
                ConversationDirectory.RECENT -> return@launch
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
            else -> when (_selectedHistoryDirectory.value) {
                ConversationDirectory.RECENT -> db.conversationDao().getRecentMainChat(5)
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
            else -> when (_selectedHistoryDirectory.value) {
            ConversationDirectory.RECENT -> "Chats / Recent (Last 5)"
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

    fun sendMessage() {
        val text = _input.value.trim()
        if (text.isBlank() || _isSending.value) return
        if (isWebScope(viewedScope) && activeWebSearchKey.isNullOrBlank()) return

        val job = appRef.eidosSendScope.launch {
            _isSending.value = true
            suppressStoppedReplyOnCancellation = false
            clearWorkshopChunkedRun()
            var conversation: Conversation? = null
            try {
                val now = System.currentTimeMillis()
                conversation = ensureConversation(now, text)
                var conv = conversation!!
                coroutineContext[Job]?.let { EidosActiveSendRegistry.register(conv.id, it) }
                if (!ConversationScopes.isWebScope(conv.scopeType)) {
                    conv = maybeRetitleConversation(conv, text, now)
                    conversation = conv
                }
                val userMsgId = db.chatMessageDao().insert(
                    ChatMessagePersistLimits.clampForStorage(
                        ChatMessage(conversationId = conv.id, role = "user", content = text, createdAt = now),
                    ),
                )
                _messages.value = _messages.value + EidosUiMessage(
                    id = userMsgId, role = EidosRole.USER, text = text,
                    timeLabel = messageTimeFormatter.format(Instant.ofEpochMilli(now)),
                )
                _input.value = ""
                callApiAndInsertReply(conv, text)
                refreshSummaries()
            } catch (ce: CancellationException) {
                (viewedScope as? ConversationScope.Workshop)?.subfolderId?.let {
                    clearActiveWorkshopBuildKickoff(it)
                }
                if (!suppressStoppedReplyOnCancellation) {
                    conversation?.let { appendStoppedReplyIfNeeded(it) }
                }
                throw ce
            } catch (t: Throwable) {
                conversation?.let { appendErrorMessage(it.id) }
            } finally {
                val convId = conversation?.id
                if (convId == null || !EidosActiveSendRegistry.isActive(convId)) {
                    if (activeConversationId == convId || convId == null) {
                        _isSending.value = false
                    }
                }
                apiExchangeJob = null
                suppressStoppedReplyOnCancellation = false
            }
        }
        apiExchangeJob = job
    }

    // ── Shared API call + reply insert ────────────────────────────────────────

    private suspend fun callApiAndInsertReply(conversation: Conversation, userText: String) {
        val apiScope = viewedScope
        if (apiScope is ConversationScope.Workshop) {
            workshopFlushOpenFileBeforeSend?.invoke()
        }
        val rawHistory = ChatMessageHistoryLoader
            .forApi(db.chatMessageDao(), conversation.id)
            .toEidosApiHistoryExcludingLatestUser(userText)
        val history = rawHistory
        val subfolderIdForApi = when (val s = apiScope) {
            is ConversationScope.Subfolder -> s.id
            is ConversationScope.Workshop -> s.subfolderId
            is ConversationScope.PanelRunner -> s.subfolderId
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
            is ConversationScope.QuickNotesDay -> db.subfolderDao().getById(s.subfolderId)?.parentFolderId
            is ConversationScope.WebEditor -> db.subfolderDao().getById(s.subfolderId)?.parentFolderId
            is ConversationScope.General,
            is ConversationScope.WebWidget,
            is ConversationScope.DumpEdit,
            is ConversationScope.PanelGallery,
            -> null
        }
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
        var workshopPausedForToolCap = false
        var workshopChained = false
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
                    baseSystemPrompt = buildBasePrompt(),
                    streamListener = streamListener,
                )
            } finally {
                _pendingDocAlignScope.value = null
                if (isActiveConversation(boundConversationId)) {
                    _streamPreview.value = null
                }
            }
            workshopPausedForToolCap = response.workshopPausedForToolCap
            if (workshopPausedForToolCap && apiScope is ConversationScope.Workshop) {
                Log.d(
                    WorkshopToolRoundPause.LOG_TAG,
                    "tool_cap_pause persisted rounds=${response.workshopToolRoundsCompleted} " +
                        "conv=${conversation.id}",
                )
            }
            val replyText = response.textResponse.ifBlank {
                "I ran the request but did not receive a text response."
            }
            val reasoningContent = response.persistableReasoningContent()
            val replyMsgId = db.chatMessageDao().insert(
                ChatMessagePersistLimits.clampForStorage(
                    ChatMessage(
                        conversationId = conversation.id,
                        role = "eidos",
                        content = replyText,
                        assistantReasoningContent = reasoningContent,
                    ),
                ),
            )
            if (isActiveConversation(boundConversationId)) {
                _messages.value = _messages.value + EidosUiMessage(
                    id = replyMsgId,
                    role = EidosRole.ASSISTANT,
                    text = replyText,
                    timeLabel = messageTimeFormatter.format(Instant.ofEpochMilli(System.currentTimeMillis())),
                    reasoningText = reasoningContent,
                )
                previousResponseId = response.providerResponseId ?: previousResponseId
            }
            db.conversationDao().update(conversation.copy(updatedAt = System.currentTimeMillis()))
            requestRetrievalSync("conversation_reply_written:${conversation.id}")
            maybeNotifyBackgroundReply(conversation, replyText)
            workshopSendSucceeded = true
            workshopChained = maybeContinueWorkshopChunk(
                conversation = conversation,
                response = response,
                apiScope = apiScope,
                workshopPausedForToolCap = workshopPausedForToolCap,
                replyText = replyText,
            )
        } finally {
            if (apiScope is ConversationScope.Workshop) {
                WorkshopUpdateCompletion.onWorkshopSendFinished(
                    context = appRef,
                    db = db,
                    subfolderId = apiScope.subfolderId,
                    docAlignScope = docAlignScope,
                    sendSucceeded = workshopSendSucceeded,
                    workshopRunContinuing = workshopChained,
                    workshopPausedForToolCap = workshopPausedForToolCap,
                )
                refreshWorkshopProjectPhase()
                refreshImplementationPlanGate()
                if (workshopSendSucceeded &&
                    !workshopPausedForToolCap &&
                    !workshopChained &&
                    WorkshopProjectPreferences.getBuildKickoff(appRef, apiScope.subfolderId) != null
                ) {
                    val subId = apiScope.subfolderId
                    WorkshopProjectPreferences.clearBuildKickoff(appRef, subId)
                    WorkshopProjectPreferences.setEidosModeOverride(appRef, subId, WorkshopEidosMode.EDIT)
                    _workshopEidosModeOverride.value = WorkshopEidosMode.EDIT
                    clearWorkshopChunkedRun()
                }
            }
        }
    }

    private suspend fun isWorkshopAutoContinueEnabled(): Boolean =
        appRef.settingsDataStore.data.first()[SettingsKeys.WORKSHOP_AUTO_CONTINUE_ENABLED]
            ?: SettingsDefaults.WORKSHOP_AUTO_CONTINUE_ENABLED

    private suspend fun isWorkshopPauseBetweenChunksEnabled(): Boolean =
        appRef.settingsDataStore.data.first()[SettingsKeys.WORKSHOP_PAUSE_BETWEEN_CHUNKS]
            ?: SettingsDefaults.WORKSHOP_PAUSE_BETWEEN_CHUNKS

    private fun clearWorkshopChunkedRun() {
        workshopChunkedRun = null
        _workshopAutoContinueActive.value = false
    }

    private fun ensureWorkshopChunkedRun(conversationId: Long, subfolderId: Long): WorkshopChunkedRunState {
        val existing = workshopChunkedRun
        if (existing != null && existing.conversationId == conversationId) return existing
        return WorkshopChunkedRunState(
            conversationId = conversationId,
            subfolderId = subfolderId,
            chunksCompleted = 1,
        ).also { workshopChunkedRun = it }
    }

    /**
     * Phase 1.5 — after assistant reply is saved, parse handoff → synthetic user → next send.
     * @return true when a follow-up chunk was started (kickoff must stay active).
     */
    private suspend fun maybeContinueWorkshopChunk(
        conversation: Conversation,
        response: EidosResponse,
        apiScope: ConversationScope,
        workshopPausedForToolCap: Boolean,
        replyText: String,
    ): Boolean {
        if (apiScope !is ConversationScope.Workshop) return false
        if (!isWorkshopAutoContinueEnabled()) return false
        if (isWorkshopPauseBetweenChunksEnabled() && workshopPausedForToolCap) return false

        val subfolderId = apiScope.subfolderId
        val mode = resolveWorkshopEidosModeForSend()
        val phase = _workshopProjectPhase.value
            ?: WorkshopProjectPreferences.getProjectPhase(appRef, subfolderId)
        val activeKickoff = WorkshopProjectPreferences.getBuildKickoff(appRef, subfolderId)

        if (!WorkshopExecutionProfile.shouldChainAfterSend(
                assistantText = replyText,
                pausedForToolCap = workshopPausedForToolCap,
                mode = mode,
                phase = phase,
                activeKickoff = activeKickoff,
            )
        ) {
            clearWorkshopChunkedRun()
            return false
        }

        val state = ensureWorkshopChunkedRun(conversation.id, subfolderId)
        val planPhaseComplete = activeKickoff == WorkshopBuildKickoff.PLAN &&
            WorkshopExecutionProfile.suggestsPlanPhaseComplete(replyText, activeKickoff)

        if (activeKickoff == WorkshopBuildKickoff.PLAN &&
            state.planPhasesCompleted >= WorkshopAutoContinue.MAX_PLAN_PHASES_PER_RUN
        ) {
            Log.w(
                WorkshopAutoContinue.LOG_TAG,
                "chain_stopped max_plan_phases=${state.planPhasesCompleted} subfolder=$subfolderId",
            )
            clearWorkshopChunkedRun()
            return false
        }

        if (!planPhaseComplete && state.chunksCompleted >= WorkshopAutoContinue.MAX_CHUNKS_PER_KICKOFF) {
            Log.w(
                WorkshopAutoContinue.LOG_TAG,
                "chain_stopped max_chunks=${state.chunksCompleted} subfolder=$subfolderId",
            )
            clearWorkshopChunkedRun()
            return false
        }

        val fallback = WorkshopContinueTicket.build(
            phase = phase,
            mode = mode,
            toolNames = emptyList(),
            lastAssistantSnippet = replyText,
            activeKickoff = activeKickoff,
        )
        val (handoffText, usedFallback) = WorkshopHandoffParser.handoffForSyntheticUser(replyText, fallback)
        if (usedFallback) {
            WorkshopContinueTicket.logFallbackUsed(phase, mode)
        }

        workshopChunkedRun = state.copy(
            chunksCompleted = if (planPhaseComplete) 1 else state.chunksCompleted + 1,
            planPhasesCompleted = if (planPhaseComplete) {
                state.planPhasesCompleted + 1
            } else {
                state.planPhasesCompleted
            },
        )
        _workshopAutoContinueActive.value = true
        Log.d(
            WorkshopAutoContinue.LOG_TAG,
            "chain_chunk next=${workshopChunkedRun?.chunksCompleted} planPhases=${workshopChunkedRun?.planPhasesCompleted} " +
                "planPhaseBoundary=$planPhaseComplete fallback=$usedFallback " +
                "toolCap=$workshopPausedForToolCap conv=${conversation.id}",
        )

        insertSyntheticHandoffUser(conversation, handoffText)
        callApiAndInsertReply(conversation, handoffText)
        return true
    }

    private suspend fun insertSyntheticHandoffUser(conversation: Conversation, handoffText: String) {
        val now = System.currentTimeMillis()
        val userMsgId = db.chatMessageDao().insert(
            ChatMessagePersistLimits.clampForStorage(
                ChatMessage(
                    conversationId = conversation.id,
                    role = "user",
                    content = handoffText,
                    createdAt = now,
                    isSyntheticHandoff = true,
                ),
            ),
        )
        if (isActiveConversation(conversation.id)) {
            _messages.value = _messages.value + EidosUiMessage(
                id = userMsgId,
                role = EidosRole.USER,
                text = handoffText,
                timeLabel = messageTimeFormatter.format(Instant.ofEpochMilli(now)),
                isSyntheticHandoff = true,
            )
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
        )
    }

    private fun syncActiveConversationFromDatabase() {
        val conversationId = activeConversationId ?: return
        viewModelScope.launch {
            loadConversationInternal(conversationId, resetProviderChain = false)
            refreshSummaries()
        }
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
        _messages.value = _messages.value + EidosUiMessage(
            id = replyMsgId,
            role = EidosRole.ASSISTANT,
            text = text,
            timeLabel = messageTimeFormatter.format(Instant.ofEpochMilli(now)),
        )
        db.conversationDao().update(conversation.copy(updatedAt = now))
        requestRetrievalSync("conversation_stopped_reply:${conversation.id}")
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

    fun refreshOpenAiKeyPresence() {
        _hasOpenAiApiKey.value = readHasOpenAiApiKey()
    }

    fun setMicUseWhisperApi(enabled: Boolean) {
        if (enabled && !readHasOpenAiApiKey()) return
        viewModelScope.launch {
            appRef.settingsDataStore.edit { it[SettingsKeys.MIC_USE_WHISPER_API] = enabled }
        }
    }

    fun setRereadMessageId(id: Long?) { _rereadMessageId.value = id }

    private fun readHasOpenAiApiKey(): Boolean =
        !getEncryptedPrefs(appRef).getString(ApiKeyNames.OPENAI, null).isNullOrBlank()

    /**
     * Edit a previously sent user message. All messages after it are hard-deleted,
     * the edited content is saved, and the conversation is ready for the next send.
     */
    fun editMessage(messageId: Long, newText: String) {
        val trimmed = newText.trim()
        if (trimmed.isBlank() || _isSending.value) return
        val job = appRef.eidosSendScope.launch {
            _isSending.value = true
            suppressStoppedReplyOnCancellation = false
            var conversation: Conversation? = null
            try {
                val msg = db.chatMessageDao().getById(messageId) ?: return@launch
                coroutineContext[Job]?.let { EidosActiveSendRegistry.register(msg.conversationId, it) }
                val isEditingFirstMessage =
                    db.chatMessageDao().getFirstMessage(msg.conversationId)?.id == messageId
                db.chatMessageDao().updateContent(messageId, trimmed)
                db.chatMessageDao().deleteAfter(msg.conversationId, msg.createdAt)
                activeConversationId = msg.conversationId
                previousResponseId = null
                loadConversationInternal(msg.conversationId)
                var conv = db.conversationDao().getById(msg.conversationId) ?: return@launch
                conversation = conv
                if (isEditingFirstMessage) {
                    conv = maybeRetitleConversation(conv, trimmed)
                    conversation = conv
                }
                callApiAndInsertReply(conv, trimmed)
                refreshSummaries()
            } catch (ce: CancellationException) {
                (viewedScope as? ConversationScope.Workshop)?.subfolderId?.let {
                    clearActiveWorkshopBuildKickoff(it)
                }
                if (!suppressStoppedReplyOnCancellation) {
                    conversation?.let { appendStoppedReplyIfNeeded(it) }
                }
                throw ce
            } catch (t: Throwable) {
                conversation?.let { appendErrorMessage(it.id) }
            } finally {
                val convId = conversation?.id
                if (convId == null || !EidosActiveSendRegistry.isActive(convId)) {
                    if (activeConversationId == convId || convId == null) {
                        _isSending.value = false
                    }
                }
                apiExchangeJob = null
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
        isSyntheticHandoff = isSyntheticHandoff,
    )

    private fun refreshHistoryDirectoryOptions() {
        val options = listOf(
            ConversationDirectoryOption(ConversationDirectory.RECENT, "Recent"),
            ConversationDirectoryOption(ConversationDirectory.GENERAL, "General"),
            ConversationDirectoryOption(ConversationDirectory.PARENT, "Parent"),
        )
        _historyDirectoryOptions.value = options
        if (options.none { it.directory == _selectedHistoryDirectory.value }) {
            _selectedHistoryDirectory.value = scopeToDirectory(viewedScope)
            if (options.none { it.directory == _selectedHistoryDirectory.value }) {
                _selectedHistoryDirectory.value = ConversationDirectory.RECENT
            }
        }
    }

    private suspend fun refreshHistoryLocationTargets() {
        when (_selectedHistoryDirectory.value) {
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

    private fun scopeToDirectory(scope: ConversationScope): ConversationDirectory {
        return when (scope) {
            is ConversationScope.General -> ConversationDirectory.GENERAL
            is ConversationScope.ParentFolder -> ConversationDirectory.PARENT
            is ConversationScope.Subfolder -> ConversationDirectory.PARENT
            is ConversationScope.QuickNotesRoot -> ConversationDirectory.PARENT
            is ConversationScope.QuickNotesDay -> ConversationDirectory.PARENT
            is ConversationScope.Workshop -> ConversationDirectory.PARENT
            is ConversationScope.DumpEdit -> ConversationDirectory.GENERAL
            is ConversationScope.PanelGallery -> ConversationDirectory.GENERAL
            is ConversationScope.PanelRunner -> ConversationDirectory.SUBFOLDER
            is ConversationScope.WebEditor, is ConversationScope.WebWidget -> ConversationDirectory.RECENT
        }
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
    }




    private fun buildBasePrompt() = """
        You are Eidos inside OptimalX.
        Be concise, clear, and operationally helpful.
        Use tools when needed and explain actions briefly.
    """.trimIndent()

    private fun requestRetrievalSync(reason: String) {
        appIndexSync.requestSync(reason)
        semanticSync.requestSync(reason)
    }
}
