package com.example.optimalx.data.eidos

import android.content.Context
import android.content.pm.ApplicationInfo
import com.example.optimalx.OptimalXApplication
import com.example.optimalx.data.db.AppDatabase
import com.example.optimalx.data.eidos.model.ConfirmationHandler
import com.example.optimalx.data.eidos.model.EidosMessage
import com.example.optimalx.data.eidos.model.EidosReasoningHop
import com.example.optimalx.data.eidos.model.EidosRequest
import com.example.optimalx.data.eidos.model.EidosRequestPhase
import com.example.optimalx.data.eidos.model.EidosResponse
import com.example.optimalx.data.eidos.model.EidosRole
import com.example.optimalx.data.eidos.model.EidosStreamListener
import com.example.optimalx.data.eidos.model.EidosToolCall
import com.example.optimalx.data.eidos.model.EidosToolDefinition
import com.example.optimalx.data.eidos.model.ToolExecutionResult
import com.example.optimalx.data.eidos.model.ToolExecutor
import com.example.optimalx.data.eidos.model.persistableReasoningContent
import com.example.optimalx.data.eidos.model.recordReasoningHop
import com.example.optimalx.data.eidos.model.withReasoningTrace
import com.example.optimalx.data.eidos.EidosNavigationCodec
import com.example.optimalx.data.eidos.EidosNavigationLookup
import com.example.optimalx.data.eidos.EidosNavigationResolver
import com.example.optimalx.data.eidos.EidosNavigationScope
import com.example.optimalx.data.eidos.EidosNavigationTarget
import com.example.optimalx.data.eidos.provider.AnthropicProvider
import com.example.optimalx.data.eidos.provider.EidosProvider
import com.example.optimalx.data.eidos.provider.KimiFormulaToolService
import com.example.optimalx.data.eidos.provider.KimiProvider
import com.example.optimalx.data.eidos.provider.LitertLmProvider
import com.example.optimalx.data.eidos.provider.OpenAIProvider
import com.example.optimalx.data.eidos.provider.ProviderHttpException
import com.example.optimalx.data.eidos.provider.XAIProvider
import com.example.optimalx.data.eidos.provider.XAI_MODEL_CHOICES

import com.example.optimalx.data.eidos.prompt.ComposedPrompt
import com.example.optimalx.data.eidos.prompt.EidosEntrySurface
import com.example.optimalx.data.eidos.prompt.EidosIdentityPrompt
import com.example.optimalx.data.eidos.prompt.EidosPromptComposeContext
import com.example.optimalx.data.eidos.prompt.EidosPromptComposer
import com.example.optimalx.data.eidos.prompt.EidosScopeProfileIds
import com.example.optimalx.data.eidos.prompt.EidosScopeProfileRegistry
import com.example.optimalx.data.eidos.prompt.EidosPromptTrace
import com.example.optimalx.data.eidos.prompt.EidosScopeRouter
import com.example.optimalx.data.eidos.prompt.EidosSendContext
import com.example.optimalx.data.eidos.prompt.EidosSystemPromptLayers
import com.example.optimalx.data.model.ConversationScopes
import com.example.optimalx.data.preferences.ApiKeyNames
import com.example.optimalx.data.preferences.EncryptedSettingKeys
import com.example.optimalx.data.litert.GemmaLocalPolicy
import com.example.optimalx.data.litert.GemmaLocalPrompt
import com.example.optimalx.data.litert.LitertLmBackend
import com.example.optimalx.data.litert.LitertLmDefaults
import com.example.optimalx.data.preferences.WorkshopProjectPreferences
import com.example.optimalx.data.preferences.SettingsDefaults
import com.example.optimalx.data.preferences.SettingsKeys
import com.example.optimalx.data.preferences.getEncryptedPrefs
import com.example.optimalx.data.preferences.settingsDataStore
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.first
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import okhttp3.Dns
import okhttp3.OkHttpClient
import java.io.InterruptedIOException
import java.net.ConnectException
import java.net.Inet4Address
import java.net.InetAddress
import java.net.SocketTimeoutException
import java.net.UnknownHostException
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

/**
 * Resolver that returns IPv4 addresses ahead of IPv6.
 *
 * Cloudflare-fronted hosts (e.g. api.moonshot.ai) publish AAAA records; on networks with
 * broken/partial IPv6 the system resolver can return "No address associated with hostname"
 * (UnknownHostException). Preferring A records sidesteps that failure mode while still
 * falling back to IPv6 when IPv4 is unavailable.
 */
private object Ipv4PreferredDns : Dns {
    override fun lookup(hostname: String): List<InetAddress> {
        val resolved = Dns.SYSTEM.lookup(hostname)
        return resolved.sortedBy { if (it is Inet4Address) 0 else 1 }
    }
}

private fun buildInteractiveHttpClient(): OkHttpClient =
    OkHttpClient.Builder()
        .connectTimeout(20, TimeUnit.SECONDS)
        .writeTimeout(30, TimeUnit.SECONDS)
        .readTimeout(120, TimeUnit.SECONDS)
        .callTimeout(180, TimeUnit.SECONDS)
        .retryOnConnectionFailure(true)
        .dns(Ipv4PreferredDns)
        .build()

private fun buildInternalHttpClient(): OkHttpClient =
    OkHttpClient.Builder()
        .connectTimeout(20, TimeUnit.SECONDS)
        .writeTimeout(30, TimeUnit.SECONDS)
        .readTimeout(90, TimeUnit.SECONDS)
        .callTimeout(120, TimeUnit.SECONDS)
        .retryOnConnectionFailure(true)
        .dns(Ipv4PreferredDns)
        .build()

class EidosApiClient(
    private val context: Context,
    private val database: AppDatabase,
    private val toolExecutor: ToolExecutor,
    private val panelBridgeRegistry: PanelBridgeRegistry? = null,
    private val confirmationHandler: ConfirmationHandler? = null,
    private val networkMonitor: EidosNetworkMonitor? = null,
    private val interactiveHttpClient: OkHttpClient = buildInteractiveHttpClient(),
    private val internalHttpClient: OkHttpClient = buildInternalHttpClient(),
    private val json: Json = Json { ignoreUnknownKeys = true; explicitNulls = false },
) {
    private val resolvedNetworkMonitor: EidosNetworkMonitor? by lazy {
        networkMonitor ?: (context.applicationContext as? OptimalXApplication)?.eidosNetworkMonitor
    }

    private val onNetworkLostListener = { resetConnections() }

    private val connectionPoolResetExecutor = Executors.newSingleThreadExecutor { runnable ->
        Thread(runnable, "eidos-okhttp-reset").apply { isDaemon = true }
    }

    init {
        resolvedNetworkMonitor?.addOnNetworkLostListener(onNetworkLostListener)
    }

    /** Abort in-flight calls and evict pooled sockets after cancel, handoff, or retry. */
    fun resetConnections() {
        interactiveHttpClient.dispatcher.cancelAll()
        internalHttpClient.dispatcher.cancelAll()
        // evictAll() closes sockets and must not run on the main thread (NetworkOnMainThreadException).
        connectionPoolResetExecutor.execute {
            runCatching {
                interactiveHttpClient.connectionPool.evictAll()
                internalHttpClient.connectionPool.evictAll()
            }
        }
    }

    fun hasValidatedInternetOrUnknown(): Boolean =
        resolvedNetworkMonitor?.hasValidatedInternetNow() ?: true

    private fun httpClientFor(entrySurface: EidosEntrySurface): OkHttpClient =
        if (entrySurface == EidosEntrySurface.INTERNAL) internalHttpClient else interactiveHttpClient
    private data class ProviderConfig(
        val activeProvider: String,
        val apiKey: String?,
    )

    private val logTokenUsage: Boolean by lazy {
        (context.applicationInfo.flags and ApplicationInfo.FLAG_DEBUGGABLE) != 0
    }

    suspend fun send(
        userMessage: String,
        currentSubfolderId: Long?,
        currentParentFolderId: Long? = null,
        currentScopeType: String? = null,
        conversationHistory: List<EidosMessage>,
        toolDefinitions: List<EidosToolDefinition> = EidosToolCatalog.all,
        baseSystemPrompt: String = EidosIdentityPrompt.TEXT,
        entrySurface: EidosEntrySurface = EidosEntrySurface.APP_CHAT,
        previousResponseId: String? = null,
        confirmationHandler: ConfirmationHandler? = this.confirmationHandler,
        /** Which editor tab/panel the user has open (note, files list, web, or a specific file viewer). */
        subfolderEditorSurfaceHint: String? = null,
        /** Loaded URL when the user is in the in-app Web panel (any chat scope). */
        webPanelPageUrl: String? = null,
        /** Panel Workshop: file open in the editor (name + full text for context). */
        workshopOpenFileName: String? = null,
        workshopOpenFileContent: String? = null,
        workshopEidosMode: WorkshopEidosMode? = null,
        workshopProjectPhase: WorkshopProjectPhase? = null,
        workshopDocAlignScope: WorkshopDocAlignScope? = null,
        workshopUpdateSection: WorkshopUpdateSection? = null,
        /** When set, Responses providers use this as `prompt_cache_key` for sticky prompt-cache routing. */
        conversationId: Long? = null,
        promptCacheKey: String? = null,
        streamListener: EidosStreamListener? = null,
        attachedImagePaths: List<String> = emptyList(),
        imageStudioHub: Boolean? = null,
        imageStudioSaveSubfolderId: Long? = null,
        imageStudioActivePreviewFileName: String? = null,
        /** Phase-specific instructions for internal.memory_rollover sends. */
        internalVolatilePrompt: String? = null,
        /** When set, replaces profile-derived tools (rollover synthesis steps). */
        toolDefinitionsOverride: List<EidosToolDefinition>? = null,
    ): EidosResponse {
        val isPanelWorkshop = currentScopeType == ConversationScopes.PANEL_WORKSHOP
        val resolvedWorkshopPhase = if (isPanelWorkshop) {
            workshopProjectPhase
                ?: currentSubfolderId?.let { WorkshopProjectPreferences.getProjectPhase(context, it) }
        } else {
            null
        }
        val resolvedUpdateSection = if (isPanelWorkshop) {
            workshopUpdateSection
                ?: currentSubfolderId?.let { WorkshopProjectPreferences.getUpdateSection(context, it) }
        } else {
            null
        }
        val activeBuildKickoff = currentSubfolderId?.let {
            WorkshopProjectPreferences.getBuildKickoff(context, it)
        }
        val workshopMode = if (isPanelWorkshop) {
            val base = workshopEidosMode ?: WorkshopEidosMode.EDIT
            if (WorkshopEidosModeResolver.isBuildKickoffModeActive(
                    base,
                    resolvedWorkshopPhase,
                    activeBuildKickoff,
                )
            ) {
                base
            } else {
                WorkshopEidosModeResolver.coerceModeForPhase(
                    mode = base,
                    phase = resolvedWorkshopPhase,
                    docAlignScope = workshopDocAlignScope,
                    activeBuildKickoff = activeBuildKickoff,
                )
            }
        } else {
            null
        }
        val sendContext = EidosSendContext(
            userMessage = userMessage,
            conversationId = conversationId,
            scopeType = currentScopeType,
            entrySurface = entrySurface,
            subfolderId = currentSubfolderId,
            parentFolderId = currentParentFolderId,
            conversationHistory = conversationHistory,
            workshopEidosMode = workshopMode,
            workshopProjectPhase = resolvedWorkshopPhase,
            workshopDocAlignScope = if (isPanelWorkshop) workshopDocAlignScope else null,
            imageStudioHub = imageStudioHub,
            imageStudioSaveSubfolderId = imageStudioSaveSubfolderId,
            imageStudioActivePreviewFileName = imageStudioActivePreviewFileName,
        )
        val resolvedScope = EidosScopeRouter.resolve(sendContext)
        val resolvedProfile = EidosScopeProfileRegistry.require(resolvedScope.profileId)
        val workshopHostLink = if (
            resolvedScope.profileId == EidosScopeProfileIds.WORKSHOP_CHAT &&
            currentSubfolderId != null
        ) {
            WorkshopHostLinkContext.resolve(database, currentSubfolderId)
        } else {
            null
        }
        val providerConfig = resolveProviderConfig(entrySurface)
        val isLocalProvider = providerConfig.activeProvider == LitertLmDefaults.PROVIDER_ID
        val localToolsEnabled = if (isLocalProvider) {
            GemmaLocalPolicy.toolsEnabled(context)
        } else {
            true
        }
        val effectiveToolDefinitions = when {
            toolDefinitionsOverride != null -> toolDefinitionsOverride
            isLocalProvider -> GemmaLocalPolicy.toolDefinitions(toolsEnabled = localToolsEnabled)
            else -> EidosToolCatalog.toolsForProfile(
                profileId = resolvedScope.profileId,
                scopeType = currentScopeType,
                workshopMode = workshopMode,
                workshopPhase = resolvedWorkshopPhase,
            )
        }
        val workshopUserTurns = if (isPanelWorkshop) {
            WorkshopEidosModeResolver.countUserTurns(conversationHistory) + 1
        } else {
            0
        }

        val providerName = providerDisplayName(providerConfig.activeProvider)
        if (!isLocalProvider) {
            val apiKey = providerConfig.apiKey
            if (apiKey.isNullOrBlank()) {
                return EidosResponse(
                    textResponse = "No API key is saved for $providerName. Open Settings > API Keys, add the key, and send again.",
                    toolCalls = emptyList(),
                )
            }
            val monitor = resolvedNetworkMonitor
            if (monitor != null &&
                !monitor.awaitValidatedInternet(EidosTransportRetryPolicy.initialAwaitMs(entrySurface))
            ) {
                return offlineTransportResponse()
            }
        }

        val activeHttpClient = if (isLocalProvider) interactiveHttpClient else httpClientFor(entrySurface)

        EidosSendForegroundService.acquire(context)
        try {
        val apiKey = providerConfig.apiKey
        val kimiFormulaTools = if (providerConfig.activeProvider == "kimi" && !apiKey.isNullOrBlank()) {
            KimiFormulaToolService(apiKey = apiKey, client = activeHttpClient, json = json).also {
                it.ensureLoadedWithRetry(
                    maxAttempts = EidosTransportRetryPolicy.TRANSIENT_MAX_ATTEMPTS,
                    awaitValidatedInternet = {
                        resolvedNetworkMonitor?.awaitValidatedInternet(
                            timeoutMs = EidosTransportRetryPolicy.RETRY_REVALIDATE_WAIT_MS,
                        ) ?: true
                    },
                )
            }
        } else {
            null
        }

        val semanticIndexer = (context.applicationContext as? OptimalXApplication)?.semanticIndexer

        val composedPrompt = if (isLocalProvider) {
            ComposedPrompt(
                systemPrompt = GemmaLocalPrompt.compose(
                    scopeType = currentScopeType,
                    subfolderId = currentSubfolderId,
                    parentFolderId = currentParentFolderId,
                    toolsEnabled = localToolsEnabled,
                ),
                profileId = resolvedScope.profileId,
                entrySurface = resolvedScope.entrySurface,
            )
        } else {
            EidosPromptComposer.compose(
                resolved = resolvedScope,
                ctx = EidosPromptComposeContext(
                    androidContext = context,
                    database = database,
                    scopeType = currentScopeType,
                    currentSubfolderId = currentSubfolderId,
                    currentParentFolderId = currentParentFolderId,
                    subfolderEditorSurfaceHint = subfolderEditorSurfaceHint,
                    webPanelPageUrl = webPanelPageUrl,
                    panelBridgeRegistry = panelBridgeRegistry,
                    workshopOpenFileName = workshopOpenFileName,
                    workshopOpenFileContent = workshopOpenFileContent,
                    workshopEidosMode = workshopMode,
                    workshopProjectPhase = resolvedWorkshopPhase,
                    workshopDocAlignScope = if (isPanelWorkshop) workshopDocAlignScope else null,
                    workshopUpdateSection = resolvedUpdateSection,
                    workshopUserTurns = workshopUserTurns,
                    dumpEditUserMessage = if (currentScopeType == ConversationScopes.DUMP_EDIT) userMessage else null,
                    userMessage = userMessage,
                    conversationId = conversationId,
                    semanticIndexer = semanticIndexer,
                    internalVolatilePrompt = internalVolatilePrompt,
                    imageStudioHub = imageStudioHub,
                    imageStudioSaveSubfolderId = imageStudioSaveSubfolderId,
                    imageStudioActivePreviewFileName = imageStudioActivePreviewFileName,
                    legacyAssembler = {
                        assembleSystemPrompt(
                            baseSystemPrompt = baseSystemPrompt,
                            currentSubfolderId = currentSubfolderId,
                            currentParentFolderId = currentParentFolderId,
                            currentScopeType = currentScopeType,
                            subfolderEditorSurfaceHint = subfolderEditorSurfaceHint,
                            webPanelPageUrl = webPanelPageUrl,
                            workshopOpenFileName = workshopOpenFileName,
                            workshopOpenFileContent = workshopOpenFileContent,
                            workshopEidosMode = workshopMode,
                            workshopProjectPhase = resolvedWorkshopPhase,
                            workshopDocAlignScope = if (isPanelWorkshop) workshopDocAlignScope else null,
                            workshopUpdateSection = resolvedUpdateSection,
                            workshopUserTurns = workshopUserTurns,
                        )
                    },
                ),
            )
        }
        val assembledSystemPrompt = composedPrompt.systemPrompt
        val stableSystemPrefix = composedPrompt.stableSystemPrefix.takeIf { it.isNotBlank() }
        val volatileSystemSuffix = composedPrompt.volatileSystemSuffix.takeIf { it.isNotBlank() }
        val toolAllowlistHash = EidosPromptTrace.toolAllowlistHash(effectiveToolDefinitions.map { it.name })

        val storedXaiModel = context.settingsDataStore.data.first()[SettingsKeys.XAI_MODEL]
        val xaiModel = when {
            storedXaiModel.isNullOrBlank() -> SettingsDefaults.XAI_MODEL
            XAI_MODEL_CHOICES.any { it.modelId == storedXaiModel } -> storedXaiModel
            else -> SettingsDefaults.XAI_MODEL
        }
        val provider = if (isLocalProvider) {
            buildLitertLmProvider()
        } else {
            getProvider(
                activeProvider = providerConfig.activeProvider,
                apiKey = apiKey.orEmpty(),
                xaiModel = xaiModel,
                httpClient = activeHttpClient,
                kimiFormulaTools = kimiFormulaTools,
            )
        }
        val userThinkingLevel = EidosThinkingLevel.fromWire(
            context.settingsDataStore.data.first()[SettingsKeys.EIDOS_THINKING_LEVEL]
                ?: SettingsDefaults.EIDOS_THINKING_LEVEL,
        )
        val resolvedThinking = EidosThinkingResolver.resolve(
            scopeAllowsThinking = resolvedProfile.thinkingEnabled,
            userLevel = userThinkingLevel,
            provider = providerConfig.activeProvider,
        )
        val family = providerFamily(providerConfig.activeProvider)
        val resolvedPromptCacheKey = promptCacheKey
            ?: conversationId?.let { "optimalx-conv-$it" }
        val transportMetrics = EidosContextTransportMetrics(providerName, enabled = logTokenUsage)
        val mutableHistory = conversationHistory.toMutableList()

        if (workshopMode != null) {
            WorkshopEidosSession.begin(
                workshopMode,
                resolvedWorkshopPhase,
                workshopDocAlignScope,
                resolvedUpdateSection,
                conversationId,
            )
        }
        val reasoningHops = mutableListOf<EidosReasoningHop>()
        val navigationTargets = mutableListOf<EidosNavigationTarget>()
        val navigationLookup = EidosNavigationLookup(
            database.subfolderDao(),
            database.parentFolderDao(),
        )
        val navigationScope = EidosNavigationScope(
            subfolderId = currentSubfolderId,
            workshopSubfolderId = if (isPanelWorkshop) currentSubfolderId else null,
        )
        val traceRecorder = EidosApiTraceRecorder.createIfEnabled(context, database)
        val traceScopeType = currentScopeType ?: when {
            currentSubfolderId != null -> "subfolder"
            currentParentFolderId != null -> "parent"
            else -> "general"
        }
        if (traceRecorder != null) {
            val directory = EidosApiTraceDirectoryResolver.resolve(
                database = database,
                scopeType = currentScopeType,
                subfolderId = currentSubfolderId,
                parentFolderId = currentParentFolderId,
            )
            val conversationTitle = conversationId?.let { id ->
                database.conversationDao().getById(id)?.title
            }.orEmpty()
            traceRecorder.beginRun(
                conversationId = conversationId,
                conversationTitle = conversationTitle,
                directory = directory,
                scopeType = traceScopeType,
                provider = providerConfig.activeProvider,
                userMessage = userMessage,
                resolvedProfileId = resolvedScope.profileId,
                entrySurface = resolvedScope.entrySurface.name,
                toolAllowlistHash = toolAllowlistHash,
                stablePrefixSha256 = composedPrompt.stablePrefixSha256,
                sectionCharCountsJson = composedPrompt.sectionCharCountsJson,
            )
        }
        var traceStatus = "completed"
        try {
        var workingRequest = buildTracedRequest(
            traceRecorder = traceRecorder,
            systemPrompt = assembledSystemPrompt,
            stableSystemPrefix = stableSystemPrefix,
            volatileSystemSuffix = volatileSystemSuffix,
            conversationHistory = mutableHistory,
            toolDefinitions = effectiveToolDefinitions,
            userMessage = userMessage,
            previousResponseId = previousResponseId,
            promptCacheKey = resolvedPromptCacheKey,
            phase = EidosRequestPhase.FULL,
            streamListener = streamListener,
            thinkingEnabled = resolvedThinking.thinkingEnabled,
            reasoningEffort = resolvedThinking.reasoningEffort,
            toolResultReplayRounds = resolvedProfile.transportHints.toolResultReplayRounds,
            localGemma = isLocalProvider,
            attachedImagePaths = attachedImagePaths,
        )

        var attempts = 0
        var maxAttempts = EidosTransportRetryPolicy.maxAttempts(
            isTransientNetwork = false,
            isInternal = EidosTransportRetryPolicy.isFailFastSurface(entrySurface),
        )
        var lastError: Throwable? = null
        var providerRound = 0
        while (attempts < maxAttempts) {
            currentCoroutineContext().ensureActive()
            try {
                var response = provider.send(workingRequest)
                recordReasoningHop(reasoningHops, response, providerRound, workingRequest.phase)
                logProviderUsage(
                    providerName = providerName,
                    phase = workingRequest.phase,
                    round = providerRound,
                    response = response,
                )
                transportMetrics.record(providerRound, response.usage?.inputTokens)
                // Some provider response chains can get "stale" after tool configuration changes
                // (for example, switching to hosted web tools). If a chained turn returns no text
                // and no tool calls, retry once without previous_response_id.
                if (workingRequest.previousResponseId != null &&
                    response.textResponse.isBlank() &&
                    response.toolCalls.isEmpty()
                ) {
                    val freshRequest = workingRequest.copy(previousResponseId = null)
                    response = provider.send(freshRequest)
                    providerRound += 1
                    recordReasoningHop(reasoningHops, response, providerRound, freshRequest.phase)
                    logProviderUsage(
                        providerName = providerName,
                        phase = freshRequest.phase,
                        round = providerRound,
                        response = response,
                    )
                    transportMetrics.record(providerRound, response.usage?.inputTokens)
                    workingRequest = freshRequest
                }
                var currentResponseId = response.providerResponseId
                var toolRound = 0
                val maxToolRounds = resolvedProfile.loopPolicy.maxToolRounds

                while (response.toolCalls.isNotEmpty()) {
                    currentCoroutineContext().ensureActive()
                    // Tool continuations send conversationHistory only (userMessage is blank).
                    // Seed the current turn so the model is not one message behind.
                    ensureActiveUserTurnInHistory(mutableHistory, userMessage)
                    if (workshopMode == WorkshopEidosMode.CHAT) {
                        val disallowed = response.toolCalls.filter { call ->
                            !isAllowedToolName(
                                name = call.name,
                                effectiveToolDefinitions = effectiveToolDefinitions,
                                kimiFormulaTools = kimiFormulaTools,
                            )
                        }
                        if (disallowed.isNotEmpty()) {
                            return EidosResponse(
                                textResponse = chatModeBlockedToolMessage(disallowed.map { it.name }.distinct()),
                                toolCalls = emptyList(),
                                providerResponseId = currentResponseId,
                            ).withReasoningTrace(reasoningHops)
                                .withNavigationTargets(navigationTargets)
                        }
                    }
                    // Circuit breaker: stop before executing another round past the profile cap.
                    toolRound += 1
                    if (toolRound > maxToolRounds) {
                        return EidosResponse(
                            textResponse = EidosToolLoopPause.message(
                                priorText = response.textResponse,
                                toolRounds = toolRound - 1,
                                pendingToolNames = response.toolCalls.map { it.name }.distinct(),
                            ),
                            toolCalls = emptyList(),
                            providerResponseId = currentResponseId,
                            assistantReasoningContent = response.assistantReasoningContent,
                        ).withReasoningTrace(reasoningHops)
                            .withNavigationTargets(navigationTargets)
                    }
                    val replayToolCalls = response.toolCalls.map { call ->
                        redactToolCallForKimiReplay(
                            call = call,
                            activeProvider = providerConfig.activeProvider,
                        )
                    }
                    mutableHistory += EidosMessage(
                        role = EidosRole.ASSISTANT,
                        content = response.textResponse,
                        assistantToolCalls = replayToolCalls,
                        assistantReasoningContent = response.assistantReasoningContent,
                    )

                    response.toolCalls.forEach { toolCall ->
                        if (kimiFormulaTools?.isFormulaTool(toolCall.name) == true) {
                            val toolResultText = kimiFormulaTools.executeFormulaTool(
                                name = toolCall.name,
                                argumentsJson = toolCall.argumentsJson,
                            )
                            mutableHistory += EidosMessage(
                                role = EidosRole.TOOL,
                                content = toolResultText,
                                toolCallId = toolCall.id,
                                toolName = toolCall.name,
                            )
                            return@forEach
                        }
                        val def = effectiveToolDefinitions.find { it.name == toolCall.name }
                        if (def == null) {
                            mutableHistory += EidosMessage(
                                role = EidosRole.TOOL,
                                content = "Tool ${toolCall.name} is not available in this chat scope.",
                                toolCallId = toolCall.id,
                                toolName = toolCall.name,
                            )
                            return@forEach
                        }
                        val requiresConfirmation = def.requiresConfirmation

                        if (requiresConfirmation) {
                            val approved = confirmationHandler?.confirm(toolCall.name, toolCall.argumentsJson) ?: false
                            if (!approved) {
                                writeConfirmationOutcomeLog(
                                    toolName = toolCall.name,
                                    approved = false,
                                    currentSubfolderId = currentSubfolderId,
                                )
                                val declinedIndex = response.toolCalls.indexOfFirst { it.id == toolCall.id }
                                response.toolCalls.forEachIndexed { index, call ->
                                    if (index < declinedIndex) return@forEachIndexed
                                    mutableHistory += EidosMessage(
                                        role = EidosRole.TOOL,
                                        content = if (index == declinedIndex) {
                                            "User declined confirmation for ${toolCall.name}."
                                        } else {
                                            "Skipped because user declined confirmation for ${toolCall.name}."
                                        },
                                        toolCallId = call.id,
                                        toolName = call.name,
                                    )
                                }
                                return EidosResponse(
                                    textResponse = "I didn't run ${toolCall.name} because you declined the action.",
                                    toolCalls = emptyList(),
                                    providerResponseId = currentResponseId,
                                ).withReasoningTrace(reasoningHops)
                                    .withNavigationTargets(navigationTargets)
                            }
                        }

                        val enrichedArgumentsJson = enrichToolArguments(
                            toolName = toolCall.name,
                            argumentsJson = toolCall.argumentsJson,
                            currentSubfolderId = currentSubfolderId,
                            currentParentFolderId = currentParentFolderId,
                            currentScopeType = currentScopeType,
                            workshopHostLink = workshopHostLink,
                            imageStudioHub = imageStudioHub,
                            imageStudioSaveSubfolderId = imageStudioSaveSubfolderId,
                        )
                        val toolResult = toolExecutor.execute(
                            toolCall.name,
                            enrichedArgumentsJson,
                        )
                        if (toolResult is ToolExecutionResult.Success) {
                            // Resolve from enriched args (scope-injected ids) so chips match what ran.
                            EidosNavigationResolver.resolve(
                                toolName = toolCall.name,
                                args = EidosNavigationResolver.parseToolArguments(enrichedArgumentsJson),
                                result = toolResult,
                                lookup = navigationLookup,
                                scope = navigationScope,
                            )?.let { navigationTargets += it }
                        }
                        val toolResultText = when (toolResult) {
                            is ToolExecutionResult.Success -> toolResult.content
                            is ToolExecutionResult.Failure -> "Tool execution failed: ${toolResult.message}"
                        }

                        mutableHistory += EidosMessage(
                            role = EidosRole.TOOL,
                            content = toolResultText,
                            toolCallId = toolCall.id,
                            toolName = toolCall.name,
                        )

                    }

                    val continuationSlice = lastToolRoundMessages(mutableHistory)

                    // RESPONSES_CHAINED (xAI/OpenAI): chain via previous_response_id + empty system +
                    // last tool round only. Never opt workshop out of this — that was the token-burn bug.
                    val useIncremental = resolvedProfile.transportHints.incrementalContinuation &&
                        providerFamilyUsesIncrementalToolContinuation(family) &&
                        !currentResponseId.isNullOrBlank()
                    // MESSAGES_CACHED (Kimi/Anthropic): keep growing history but drop the cached
                    // system block on hop 2+ instead of re-sending the full prose every hop.
                    val omitSystemForMessages = resolvedProfile.transportHints.omitSystemOnContinuation &&
                        providerFamilyOmitsSystemOnToolContinuation(family)
                    workingRequest = buildTracedRequest(
                        traceRecorder = traceRecorder,
                        systemPrompt = if (useIncremental || omitSystemForMessages) "" else assembledSystemPrompt,
                        stableSystemPrefix = if (useIncremental || omitSystemForMessages) null else stableSystemPrefix,
                        volatileSystemSuffix = if (useIncremental || omitSystemForMessages) null else volatileSystemSuffix,
                        conversationHistory = if (useIncremental) {
                            continuationSlice
                        } else {
                            mutableHistory
                        },
                        toolDefinitions = effectiveToolDefinitions,
                        userMessage = "",
                        previousResponseId = currentResponseId,
                        promptCacheKey = resolvedPromptCacheKey,
                        phase = EidosRequestPhase.TOOL_CONTINUATION,
                        streamListener = streamListener,
                        thinkingEnabled = resolvedThinking.thinkingEnabled,
                        reasoningEffort = resolvedThinking.reasoningEffort,
                        toolResultReplayRounds = resolvedProfile.transportHints.toolResultReplayRounds,
                        localGemma = isLocalProvider,
                        attachedImagePaths = emptyList(),
                    )
                    currentCoroutineContext().ensureActive()
                    response = provider.send(workingRequest)
                    providerRound += 1
                    recordReasoningHop(reasoningHops, response, providerRound, workingRequest.phase)
                    logProviderUsage(
                        providerName = providerName,
                        phase = workingRequest.phase,
                        round = providerRound,
                        response = response,
                    )
                    transportMetrics.record(providerRound, response.usage?.inputTokens)
                    currentResponseId = response.providerResponseId
                }

                return response
                    .withReasoningTrace(reasoningHops)
                    .withNavigationTargets(navigationTargets)
            } catch (t: CancellationException) {
                traceStatus = "cancelled"
                throw t
            } catch (t: Throwable) {
                currentCoroutineContext().ensureActive()
                if (EidosTransportRetryPolicy.isCanceledCall(t)) {
                    traceStatus = "cancelled"
                    throw CancellationException("Provider call canceled", t)
                }
                attempts += 1
                lastError = t
                val isTransient = isTransientNetworkError(t)
                val stillOnline = if (isTransient) {
                    resolvedNetworkMonitor?.awaitValidatedInternet(
                        timeoutMs = EidosTransportRetryPolicy.retryRevalidateWaitMs(entrySurface),
                    ) ?: true
                } else {
                    resolvedNetworkMonitor?.hasValidatedInternetNow() ?: true
                }
                maxAttempts = EidosTransportRetryPolicy.maxAttempts(
                    isTransientNetwork = isTransient,
                    isInternal = EidosTransportRetryPolicy.isFailFastSurface(entrySurface),
                )
                if (!EidosTransportRetryPolicy.shouldRetry(
                        attemptsUsed = attempts,
                        maxAttempts = maxAttempts,
                        hasValidatedInternet = stillOnline,
                    )
                ) {
                    break
                }
                delay(
                    when {
                        isDnsError(t) -> EidosTransportRetryPolicy.retryDelayMs(
                            isDns = true,
                            attemptsUsed = attempts,
                        )
                        isTransient -> EidosTransportRetryPolicy.retryDelayMs(
                            isDns = false,
                            attemptsUsed = attempts,
                        )
                        else -> 700L
                    },
                )
            }
        }

        traceStatus = "error"
        if (resolvedNetworkMonitor?.hasValidatedInternetNow() == false) {
            return offlineTransportResponse()
                .withReasoningTrace(reasoningHops)
                .withNavigationTargets(navigationTargets)
        }
        return EidosResponse(
            textResponse = buildProviderErrorMessage(
                activeProvider = providerConfig.activeProvider,
                error = lastError,
                attemptedRetry = attempts > 1,
            ),
            toolCalls = emptyList(),
            transportFailure = true,
        ).withReasoningTrace(reasoningHops)
            .withNavigationTargets(navigationTargets)
        } finally {
            traceRecorder?.finishRun(traceStatus)
            if (workshopMode != null) {
                WorkshopEidosSession.end()
            }
        }
        } finally {
            EidosSendForegroundService.release(context)
        }
    }

    /**
     * Warms Moonshot Formula tool schemas (web_search, fetch, etc.) so the first web-panel or
     * widget send does not attach an empty tool list on cold-start DNS. No-op when Kimi is not
     * active or the API key is missing.
     */
    suspend fun preloadKimiFormulaTools() {
        val config = getProviderConfig()
        if (config.activeProvider != "kimi" || config.apiKey.isNullOrBlank()) return
        KimiFormulaToolService(apiKey = config.apiKey, client = interactiveHttpClient, json = json)
            .ensureLoadedWithRetry(
                maxAttempts = EidosTransportRetryPolicy.TRANSIENT_MAX_ATTEMPTS,
                awaitValidatedInternet = {
                    resolvedNetworkMonitor?.awaitValidatedInternet(
                        timeoutMs = EidosTransportRetryPolicy.PRELOAD_AWAIT_MS,
                    ) ?: true
                },
            )
    }

    private suspend fun getProviderConfig(): ProviderConfig {
        val encryptedPrefs = getEncryptedPrefs(context)
        val activeProvider = encryptedPrefs.getString(EncryptedSettingKeys.ACTIVE_PROVIDER, null)
            ?: context.settingsDataStore.data.first()[SettingsKeys.ACTIVE_PROVIDER]
            ?: SettingsDefaults.ACTIVE_PROVIDER

        val keyName = when (activeProvider) {
            LitertLmDefaults.PROVIDER_ID -> null
            "openai" -> ApiKeyNames.OPENAI
            "anthropic" -> ApiKeyNames.ANTHROPIC
            "kimi" -> ApiKeyNames.KIMI
            else -> ApiKeyNames.XAI
        }

        val apiKey = keyName?.let { encryptedPrefs.getString(it, null) }
        return ProviderConfig(activeProvider = activeProvider, apiKey = apiKey)
    }

    private suspend fun buildLitertLmProvider(): LitertLmProvider {
        val prefs = context.settingsDataStore.data.first()
        val modelPath = LitertLmDefaults.resolveModelPath(
            context,
            prefs[SettingsKeys.LITERT_MODEL_PATH].orEmpty(),
        )
        val backend = LitertLmBackend.fromWire(
            prefs[SettingsKeys.LITERT_BACKEND] ?: SettingsDefaults.LITERT_BACKEND,
        )
        val app = context.applicationContext as OptimalXApplication
        return LitertLmProvider(
            engineHolder = app.litertLmEngineHolder,
            modelPath = modelPath,
            backend = backend,
        )
    }

    /**
     * Background/summary work ([EidosEntrySurface.INTERNAL] — conversation and note rolling
     * summaries, content summaries, memory rollover) is farmed out to xAI when an xAI key is saved.
     * This keeps the user's active provider (e.g. Kimi) focused on the user-facing reply, cuts
     * first-token latency, and avoids extra provider round-trips after every save. Falls back to the
     * active provider when no xAI key is present.
     */
    private suspend fun resolveProviderConfig(entrySurface: EidosEntrySurface): ProviderConfig {
        if (entrySurface == EidosEntrySurface.INTERNAL) {
            getXaiProviderConfig()?.let { return it }
        }
        return getProviderConfig()
    }

    private suspend fun getXaiProviderConfig(): ProviderConfig? {
        val apiKey = getEncryptedPrefs(context).getString(ApiKeyNames.XAI, null)
        if (apiKey.isNullOrBlank()) return null
        return ProviderConfig(activeProvider = "xai", apiKey = apiKey)
    }

    private fun getProvider(
        activeProvider: String,
        apiKey: String,
        xaiModel: String,
        httpClient: OkHttpClient,
        kimiFormulaTools: KimiFormulaToolService? = null,
    ): EidosProvider {
        return when (activeProvider) {
            "openai" -> OpenAIProvider(apiKey = apiKey, client = httpClient, json = json)
            "anthropic" -> AnthropicProvider(apiKey = apiKey, client = httpClient, json = json)
            "kimi" -> KimiProvider(
                apiKey = apiKey,
                client = httpClient,
                json = json,
                formulaToolService = kimiFormulaTools,
            )
            else -> XAIProvider(apiKey = apiKey, client = httpClient, json = json, model = xaiModel)
        }
    }

    private fun offlineTransportResponse(): EidosResponse = EidosResponse(
        textResponse = "No internet connection right now. Check your signal and try again.",
        toolCalls = emptyList(),
        transportFailure = true,
    )

    private fun isDnsError(error: Throwable): Boolean {
        var current: Throwable? = error
        while (current != null) {
            if (current is UnknownHostException) return true
            current = current.cause
        }
        return error.message.orEmpty().contains("Unable to resolve host", ignoreCase = true)
    }

    private fun isTransientNetworkError(error: Throwable): Boolean {
        var current: Throwable? = error
        while (current != null) {
            when (current) {
                is UnknownHostException,
                is ConnectException,
                is SocketTimeoutException,
                is InterruptedIOException,
                -> return true
            }
            current = current.cause
        }
        val message = error.message.orEmpty()
        return message.contains("Unable to resolve host", ignoreCase = true) ||
            message.contains("failed to connect", ignoreCase = true) ||
            message.contains("ENETUNREACH", ignoreCase = true) ||
            message.contains("Network is unreachable", ignoreCase = true)
    }

    private fun buildProviderErrorMessage(
        activeProvider: String,
        error: Throwable?,
        attemptedRetry: Boolean,
    ): String {
        val provider = providerDisplayName(activeProvider)
        val retryLine = if (attemptedRetry) " Retried several times but it still failed." else ""
        val endpoint = providerEndpoint(activeProvider)

        val httpError = error as? ProviderHttpException
        if (httpError != null) {
            val summary = summarizeErrorBody(httpError.responseBody)
            return when (httpError.statusCode) {
                401, 403 -> "$provider rejected your API key (HTTP ${httpError.statusCode}) at $endpoint.$retryLine Update the key in Settings > API Keys and try again."
                429 -> "$provider rate limit reached (HTTP 429) at $endpoint.$retryLine Wait a moment and try again."
                else -> "$provider request failed (HTTP ${httpError.statusCode}) at $endpoint.$retryLine ${if (summary.isNotBlank()) "Details: $summary" else ""}".trim()
            }
        }

        if (error != null && isDnsError(error)) {
            return "$provider couldn't reach $endpoint — DNS resolution failed.$retryLine " +
                "This usually happens when the app is minimized with aggressive battery optimization. " +
                "Keep OptimalX in the foreground while Kimi is reasoning, or disable Battery Optimization " +
                "for OptimalX in device Settings > Apps > OptimalX > Battery."
        }

        if (error is SocketTimeoutException || error is InterruptedIOException) {
            return "$provider timed out at $endpoint.$retryLine The request may still be processing on the provider side. Try again, reduce request size, or switch to a faster model."
        }

        val detail = error?.message?.take(220)?.trim().orEmpty()
        return if (detail.isNotBlank()) {
            "$provider is unavailable right now.$retryLine Details: $detail"
        } else {
            "$provider is unavailable right now.$retryLine Try again or switch providers in Settings."
        }
    }

    private fun providerDisplayName(activeProvider: String): String = when (activeProvider) {
        LitertLmDefaults.PROVIDER_ID -> "Local Gemma (LiteRT-LM)"
        "openai" -> "OpenAI"
        "anthropic" -> "Anthropic"
        "kimi" -> "Kimi (Moonshot)"
        else -> "xAI"
    }

    private fun providerEndpoint(activeProvider: String): String = when (activeProvider) {
        LitertLmDefaults.PROVIDER_ID -> "on-device litertlm"
        "openai" -> "https://api.openai.com/v1/responses"
        "anthropic" -> "https://api.anthropic.com/v1/messages"
        "kimi" -> "https://api.moonshot.ai/v1/chat/completions"
        else -> "https://api.x.ai/v1/responses"
    }

    private fun summarizeErrorBody(body: String): String {
        if (body.isBlank()) return ""
        return body
            .replace(Regex("\\s+"), " ")
            .trim()
            .take(220)
    }

    private suspend fun assembleSystemPrompt(
        baseSystemPrompt: String,
        currentSubfolderId: Long?,
        currentParentFolderId: Long?,
        currentScopeType: String?,
        subfolderEditorSurfaceHint: String?,
        webPanelPageUrl: String?,
        workshopOpenFileName: String?,
        workshopOpenFileContent: String? = null,
        workshopEidosMode: WorkshopEidosMode? = null,
        workshopProjectPhase: WorkshopProjectPhase? = null,
        workshopDocAlignScope: WorkshopDocAlignScope? = null,
        workshopUpdateSection: WorkshopUpdateSection? = null,
        workshopUserTurns: Int = 0,
    ): String {
        val lines = mutableListOf<String>()
        val activeScope = EidosSystemPromptLayers.resolveActiveScope(
            currentScopeType = currentScopeType,
            currentSubfolderId = currentSubfolderId,
            currentParentFolderId = currentParentFolderId,
        )
        lines += EidosSystemPromptLayers.universalIdentityAndRetrieval(baseSystemPrompt, activeScope)

        val isWebChatScope = EidosSystemPromptLayers.isWebChatScope(activeScope)
        if (!isWebChatScope) {
            EidosSystemPromptLayers.webPanelUrlBlock(webPanelPageUrl.orEmpty())?.let { lines += it }
        }

        if (currentSubfolderId != null &&
            activeScope != "content_summary" &&
            activeScope != ConversationScopes.QUICK_NOTES_DAY &&
            activeScope != ConversationScopes.SUBFOLDER &&
            activeScope != ConversationScopes.WEB_EDITOR &&
            activeScope != ConversationScopes.PANEL_RUNNER &&
            activeScope != ConversationScopes.PANEL_WORKSHOP
        ) {
            val subfolder = database.subfolderDao().getById(currentSubfolderId)
            if (subfolder != null) {
                lines += SubfolderContext.buildVolatileContext(database, subfolder)
                if (EidosSystemPromptLayers.isNoteEditScope(activeScope)) {
                    lines += EidosSystemPromptLayers.NOTE_WRITE_RULES
                }
                lines += ParentFolderContext.formatActiveParentLine(
                    database.parentFolderDao().getById(subfolder.parentFolderId)?.name,
                    subfolder.parentFolderId,
                )
                if (!isWebChatScope) {
                    val surface = subfolderEditorSurfaceHint?.trim().orEmpty()
                    if (surface.isNotEmpty()) {
                        lines += "Optional UI context (use only when relevant; the user may discuss any topic):\n$surface"
                    }
                }
            }
        }

        return lines.joinToString("\n\n")
    }

    private fun enrichToolArguments(
        toolName: String,
        argumentsJson: String,
        currentSubfolderId: Long?,
        currentParentFolderId: Long?,
        currentScopeType: String?,
        workshopHostLink: WorkshopHostLink? = null,
        imageStudioHub: Boolean? = null,
        imageStudioSaveSubfolderId: Long? = null,
    ): String {
        val args = runCatching { json.parseToJsonElement(argumentsJson).jsonObject }.getOrNull()
            ?: return argumentsJson

        return when (toolName) {
            "call_panel_function" -> {
                val enriched = buildJsonObject {
                    args.forEach { (key, value) -> put(key, value) }
                    if (currentSubfolderId != null && !args.containsKey("currentSubfolderId")) {
                        put("currentSubfolderId", JsonPrimitive(currentSubfolderId))
                    }
                    if (!currentScopeType.isNullOrBlank() && !args.containsKey("currentScopeType")) {
                        put("currentScopeType", JsonPrimitive(currentScopeType))
                    }
                }
                enriched.toString()
            }
            "search_semantic" -> {
                val enriched = EidosSearchSemanticEnrich.enrich(
                    args = args,
                    currentScopeType = currentScopeType,
                    currentSubfolderId = currentSubfolderId,
                    currentParentFolderId = currentParentFolderId,
                    workshopHostLink = workshopHostLink,
                )
                enriched?.toString() ?: argumentsJson
            }
            "create_subfolder" -> {
                if (currentParentFolderId == null || args.containsKey("parentFolderId")) {
                    return argumentsJson
                }
                val enriched = buildJsonObject {
                    args.forEach { (key, value) -> put(key, value) }
                    put("parentFolderId", JsonPrimitive(currentParentFolderId))
                }
                enriched.toString()
            }
            "search_folders" -> {
                if (currentParentFolderId == null || args.containsKey("parentFolderId")) {
                    return argumentsJson
                }
                val enriched = buildJsonObject {
                    args.forEach { (key, value) -> put(key, value) }
                    put("parentFolderId", JsonPrimitive(currentParentFolderId))
                }
                enriched.toString()
            }
            "list_images" -> {
                val enriched = buildJsonObject {
                    args.forEach { (key, value) -> put(key, value) }
                    if (!currentScopeType.isNullOrBlank() && !args.containsKey("currentScopeType")) {
                        put("currentScopeType", JsonPrimitive(currentScopeType))
                    }
                    if (currentSubfolderId != null && !args.containsKey("currentSubfolderId")) {
                        put("currentSubfolderId", JsonPrimitive(currentSubfolderId))
                    }
                    if (currentScopeType == ConversationScopes.IMAGE_STUDIO) {
                        if (!args.containsKey("scope")) {
                            put("scope", JsonPrimitive(if (imageStudioHub == true) "all" else "subfolder"))
                        }
                        if (imageStudioHub != null && !args.containsKey("imageStudioHub")) {
                            put("imageStudioHub", JsonPrimitive(imageStudioHub))
                        }
                        val scopeArg = args["scope"]?.jsonPrimitive?.contentOrNull
                            ?: if (imageStudioHub == true) "all" else "subfolder"
                        if (
                            scopeArg == "subfolder" &&
                            !args.containsKey("subfolderId")
                        ) {
                            val sid = imageStudioSaveSubfolderId ?: currentSubfolderId
                            if (sid != null) put("subfolderId", JsonPrimitive(sid))
                        }
                    }
                }
                enriched.toString()
            }
            "list_folder_contents" -> {
                if (
                    currentScopeType == ConversationScopes.IMAGE_STUDIO &&
                    imageStudioHub != true &&
                    !args.containsKey("folderId")
                ) {
                    val sid = imageStudioSaveSubfolderId ?: currentSubfolderId
                    if (sid != null) {
                        val enriched = buildJsonObject {
                            args.forEach { (key, value) -> put(key, value) }
                            put("folderId", JsonPrimitive(sid))
                        }
                        return enriched.toString()
                    }
                }
                argumentsJson
            }
            "workshop_write_file", "workshop_create_file", "workshop_edit_file",
            "workshop_append_file",
            "workshop_read_file", "workshop_list_pending_review",
            "write_note", "edit_note_section", "read_note_section", "write_note_summary",
            -> {
                if (currentSubfolderId == null && currentScopeType.isNullOrBlank()) {
                    return argumentsJson
                }
                val enriched = buildJsonObject {
                    args.forEach { (key, value) -> put(key, value) }
                    if (currentSubfolderId != null && !args.containsKey("currentSubfolderId")) {
                        put("currentSubfolderId", JsonPrimitive(currentSubfolderId))
                    }
                    if (!currentScopeType.isNullOrBlank() && !args.containsKey("currentScopeType")) {
                        put("currentScopeType", JsonPrimitive(currentScopeType))
                    }
                    if (currentSubfolderId != null &&
                        !args.containsKey("subfolderId") &&
                        toolName in noteSubfolderTools
                    ) {
                        put("subfolderId", JsonPrimitive(currentSubfolderId))
                    }
                }
                enriched.toString()
            }
            else -> argumentsJson
        }
    }

    private val noteSubfolderTools = setOf(
        "write_note",
        "edit_note_section",
        "read_note_section",
        "write_note_summary",
    )

    private fun chatModeBlockedToolMessage(toolNames: List<String>): String {
        val listed = toolNames.joinToString(", ")
        return buildString {
            append("I'm in Chat mode and can't run ")
            append(if (toolNames.size == 1) listed else "these tools: $listed")
            append(". I can discuss the project and read files when you ask. ")
            append("Switch to Plan or Edit to change files.")
        }
    }

    private fun isAllowedToolName(
        name: String,
        effectiveToolDefinitions: List<EidosToolDefinition>,
        kimiFormulaTools: KimiFormulaToolService?,
    ): Boolean {
        if (kimiFormulaTools?.isFormulaTool(name) == true) return true
        return effectiveToolDefinitions.any { it.name == name }
    }

    private fun redactToolCallForKimiReplay(
        call: EidosToolCall,
        activeProvider: String,
    ): EidosToolCall {
        if (activeProvider != "kimi") return call
        val validArgs = runCatching { json.parseToJsonElement(call.argumentsJson) }.isSuccess
        if (!validArgs) return call.copy(argumentsJson = "{}")
        return when (call.name) {
            "workshop_write_file" -> {
                val args = json.parseToJsonElement(call.argumentsJson).jsonObject
                val redacted = buildJsonObject {
                    args["fileReferenceId"]?.let { put("fileReferenceId", it) }
                    put("content", JsonPrimitive("[omitted from replay; see tool result]"))
                }
                call.copy(argumentsJson = redacted.toString())
            }
            "workshop_create_file" -> {
                val args = json.parseToJsonElement(call.argumentsJson).jsonObject
                val redacted = buildJsonObject {
                    args["subfolderId"]?.let { put("subfolderId", it) }
                    args["fileName"]?.let { put("fileName", it) }
                    put("content", JsonPrimitive("[omitted from replay; see tool result]"))
                }
                call.copy(argumentsJson = redacted.toString())
            }
            else -> call
        }
    }

    private fun lastToolRoundMessages(history: List<EidosMessage>): List<EidosMessage> {
        val toolMsgs = mutableListOf<EidosMessage>()
        var i = history.lastIndex
        while (i >= 0 && history[i].role == EidosRole.TOOL) {
            toolMsgs.add(0, history[i])
            i--
        }
        if (i >= 0 && history[i].role == EidosRole.ASSISTANT) {
            return listOf(history[i]) + toolMsgs
        }
        return toolMsgs
    }

    private suspend fun writeConfirmationOutcomeLog(
        toolName: String,
        approved: Boolean,
        currentSubfolderId: Long?,
    ) {
        val payload: JsonObject = buildJsonObject {
            put(
                "action",
                kotlinx.serialization.json.JsonPrimitive(
                    if (approved) "Confirmation approved for $toolName" else "Confirmation declined for $toolName"
                ),
            )
            put("timestamp", kotlinx.serialization.json.JsonPrimitive(System.currentTimeMillis()))
            if (currentSubfolderId != null) {
                put("location", kotlinx.serialization.json.JsonPrimitive("subfolder:$currentSubfolderId"))
            }
        }

        toolExecutor.execute(
            toolName = "write_log_entry",
            argumentsJson = payload.toString(),
        )
    }

    private companion object {
    }

    private fun buildTracedRequest(
        traceRecorder: EidosApiTraceRecorder?,
        systemPrompt: String,
        stableSystemPrefix: String?,
        volatileSystemSuffix: String?,
        conversationHistory: List<EidosMessage>,
        toolDefinitions: List<EidosToolDefinition>,
        userMessage: String,
        previousResponseId: String?,
        promptCacheKey: String?,
        phase: EidosRequestPhase,
        streamListener: EidosStreamListener?,
        thinkingEnabled: Boolean,
        reasoningEffort: String?,
        toolResultReplayRounds: Int,
        localGemma: Boolean = false,
        attachedImagePaths: List<String> = emptyList(),
    ): EidosRequest = EidosRequest(
        systemPrompt = systemPrompt,
        stableSystemPrefix = stableSystemPrefix,
        volatileSystemSuffix = volatileSystemSuffix,
        conversationHistory = if (localGemma) {
            GemmaLocalPolicy.trimOutboundHistory(conversationHistory)
        } else {
            EidosHistoryTrimmer.prepareOutboundHistory(
                history = conversationHistory,
                replayRounds = toolResultReplayRounds,
            )
        },
        toolDefinitions = toolDefinitions,
        userMessage = userMessage,
        previousResponseId = previousResponseId,
        promptCacheKey = promptCacheKey,
        phase = phase,
        thinkingEnabled = thinkingEnabled,
        reasoningEffort = reasoningEffort,
        streamListener = streamListener,
        attachedImagePaths = if (phase == EidosRequestPhase.FULL) attachedImagePaths else emptyList(),
        onProviderExchange = traceRecorder?.let { recorder ->
            { body, response ->
                recorder.recordExchange(phase, body, response)
            }
        },
    )

    private fun logProviderUsage(
        providerName: String,
        phase: EidosRequestPhase,
        round: Int,
        response: EidosResponse,
    ) {
        if (!logTokenUsage) return
        EidosUsageLogger.log(
            providerName = providerName,
            phase = phase,
            round = round,
            usage = response.usage,
            providerResponseId = response.providerResponseId,
            toolCallCount = response.toolCalls.size,
        )
    }
}

private fun EidosResponse.withNavigationTargets(
    targets: List<EidosNavigationTarget>,
): EidosResponse = copy(navigationTargets = EidosNavigationCodec.dedupeTargets(targets))
