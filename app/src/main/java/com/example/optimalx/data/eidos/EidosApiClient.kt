package com.example.optimalx.data.eidos

import android.content.Context
import android.content.pm.ApplicationInfo
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
import com.example.optimalx.data.eidos.model.recordReasoningHop
import com.example.optimalx.data.eidos.model.withReasoningTrace
import com.example.optimalx.data.eidos.provider.AnthropicProvider
import com.example.optimalx.data.eidos.provider.EidosProvider
import com.example.optimalx.data.eidos.provider.KimiFormulaToolService

import com.example.optimalx.data.eidos.provider.KimiProvider
import com.example.optimalx.data.eidos.provider.OpenAIProvider
import com.example.optimalx.data.eidos.provider.ProviderHttpException
import com.example.optimalx.data.eidos.provider.XAIProvider
import com.example.optimalx.data.eidos.provider.XAI_MODEL_CHOICES

import com.example.optimalx.OptimalXApplication
import com.example.optimalx.data.dumpedit.DumpEditContext
import com.example.optimalx.data.model.ConversationScopes
import com.example.optimalx.data.semantic.ContentSectionRetriever
import com.example.optimalx.data.model.Subfolder
import com.example.optimalx.data.preferences.ApiKeyNames
import com.example.optimalx.data.preferences.EncryptedSettingKeys
import com.example.optimalx.data.preferences.WorkshopProjectPreferences
import com.example.optimalx.data.revision.SCOPE_WORKSHOP_PROJECT
import com.example.optimalx.data.revision.WorkshopReviewPolicy
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
import kotlinx.serialization.json.jsonObject
import okhttp3.Dns
import okhttp3.OkHttpClient
import java.io.InterruptedIOException
import java.net.ConnectException
import java.net.Inet4Address
import java.net.InetAddress
import java.net.SocketTimeoutException
import java.net.UnknownHostException
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

class EidosApiClient(
    private val context: Context,
    private val database: AppDatabase,
    private val toolExecutor: ToolExecutor,
    private val panelBridgeRegistry: PanelBridgeRegistry? = null,
    private val confirmationHandler: ConfirmationHandler? = null,
    private val httpClient: OkHttpClient = OkHttpClient.Builder()
        .connectTimeout(30, TimeUnit.SECONDS)
        .writeTimeout(30, TimeUnit.SECONDS)
        .readTimeout(300, TimeUnit.SECONDS)
        .callTimeout(600, TimeUnit.SECONDS)
        .retryOnConnectionFailure(true)
        .dns(Ipv4PreferredDns)
        .build(),
    private val json: Json = Json { ignoreUnknownKeys = true; explicitNulls = false },
) {
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
        baseSystemPrompt: String,
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
        /** When set, xAI uses this as `prompt_cache_key` for sticky prompt-cache routing. */
        conversationId: Long? = null,
        promptCacheKey: String? = null,
        streamListener: EidosStreamListener? = null,
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
            WorkshopEidosModeResolver.modeForDocAlign(workshopDocAlignScope)
                ?: WorkshopEidosModeResolver.modeForActiveBuildKickoff(
                    resolvedWorkshopPhase,
                    activeBuildKickoff,
                )
                ?: run {
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
                }
        } else {
            null
        }
        val effectiveToolDefinitions = when (currentScopeType) {
            ConversationScopes.PANEL_RUNNER -> EidosToolCatalog.toolsForPanelRunner()
            ConversationScopes.PANEL_GALLERY -> EidosToolCatalog.toolsForPanelGallery()
            else -> when {
                workshopMode != null -> EidosToolCatalog.toolsForWorkshopMode(
                    workshopMode,
                    resolvedWorkshopPhase,
                )
                else -> toolDefinitions
            }

        }
        val workshopUserTurns = if (isPanelWorkshop) {
            WorkshopEidosModeResolver.countUserTurns(conversationHistory) + 1
        } else {
            0
        }
        val settingsMemoryDepth = context.settingsDataStore.data.first()[SettingsKeys.CONVERSATION_MEMORY_DEPTH]
            ?: SettingsDefaults.CONVERSATION_MEMORY_DEPTH
        val conversationStoredDepth = conversationId?.let { id ->
            database.conversationDao().getById(id)?.memoryDepth
        }
        val memoryDepth = EidosContextLimits.effectiveMemoryDepth(
            conversationStored = conversationStoredDepth,
            settingsDefault = settingsMemoryDepth,
        )
        val historyBudget = EidosContextLimits.historyBudget(memoryDepth)
        val trimmedHistory = EidosHistoryTrimmer.trimHistoryIfNeeded(conversationHistory, historyBudget)

        val providerConfig = getProviderConfig()
        val providerName = providerDisplayName(providerConfig.activeProvider)
        val apiKey = providerConfig.apiKey
        if (apiKey.isNullOrBlank()) {
            return EidosResponse(
                textResponse = "No API key is saved for $providerName. Open Settings > API Keys, add the key, and send again.",
                toolCalls = emptyList(),
            )
        }

        EidosSendForegroundService.acquire(context)
        try {
        val kimiFormulaTools = if (providerConfig.activeProvider == "kimi") {
            KimiFormulaToolService(apiKey = apiKey, client = httpClient, json = json).also {
                it.ensureLoadedWithRetry()
            }
        } else {
            null
        }

        val assembledSystemPrompt = assembleSystemPrompt(
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
            chatHistoryTrimmed = trimmedHistory.size < conversationHistory.size,
            conversationMemoryDepth = memoryDepth,
            dumpEditUserMessage = if (currentScopeType == ConversationScopes.DUMP_EDIT) userMessage else null,
            activeProvider = providerConfig.activeProvider,
            kimiFormulaToolsLoaded = kimiFormulaTools?.isLoaded() == true,
        )

        val storedXaiModel = context.settingsDataStore.data.first()[SettingsKeys.XAI_MODEL]
        val xaiModel = when {
            storedXaiModel.isNullOrBlank() -> SettingsDefaults.XAI_MODEL
            XAI_MODEL_CHOICES.any { it.modelId == storedXaiModel } -> storedXaiModel
            else -> SettingsDefaults.XAI_MODEL
        }
        val provider = getProvider(
            activeProvider = providerConfig.activeProvider,
            apiKey = apiKey,
            xaiModel = xaiModel,
            kimiFormulaTools = kimiFormulaTools,
        )
        val family = providerFamily(providerConfig.activeProvider)
        val resolvedPromptCacheKey = promptCacheKey
            ?: conversationId?.let { "optimalx-conv-$it" }
        val mutableHistory = trimmedHistory.toMutableList()

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
            )
        }
        var traceStatus = "completed"
        try {
        var workingRequest = buildTracedRequest(
            traceRecorder = traceRecorder,
            systemPrompt = assembledSystemPrompt,
            conversationHistory = mutableHistory,
            toolDefinitions = effectiveToolDefinitions,
            userMessage = userMessage,
            previousResponseId = previousResponseId,
            promptCacheKey = resolvedPromptCacheKey,
            phase = EidosRequestPhase.FULL,
            streamListener = streamListener,
        )

        var attempts = 0
        var maxAttempts = 2
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
                    workingRequest = freshRequest
                }
                var currentResponseId = response.providerResponseId
                var workshopChatToolRound = 0
                var workshopBuildToolRound = 0

                while (response.toolCalls.isNotEmpty()) {
                    currentCoroutineContext().ensureActive()
                    // Tool continuations send conversationHistory only (userMessage is blank).
                    // Seed the current turn so the model is not one message behind.
                    ensureActiveUserTurnInHistory(mutableHistory, userMessage)
                    if (isPanelWorkshop && workshopMode != WorkshopEidosMode.CHAT) {
                        workshopBuildToolRound += 1
                        if (workshopBuildToolRound > WorkshopToolRoundPause.WORKSHOP_EDIT_BUILD_MAX_TOOL_ROUNDS) {
                            val toolNames = WorkshopToolRoundPause.toolNamesInCurrentExchange(
                                history = mutableHistory,
                                pendingToolCalls = response.toolCalls,
                            )
                            if (logTokenUsage) {
                                WorkshopToolRoundPause.logToolCapPaused(
                                    roundsCompleted = workshopBuildToolRound - 1,
                                    cap = WorkshopToolRoundPause.WORKSHOP_EDIT_BUILD_MAX_TOOL_ROUNDS,
                                    toolNames = toolNames,
                                )
                            }
                            val pendingProposalCount = currentSubfolderId?.let { subId ->
                                database.pendingChangeDao().findOpenSetForScope(
                                    SCOPE_WORKSHOP_PROJECT,
                                    subId,
                                )?.let { database.pendingChangeDao().countPending(it.id) } ?: 0
                            } ?: 0
                            val pauseText = WorkshopToolRoundPause.buildPauseMessage(
                                cap = WorkshopToolRoundPause.WORKSHOP_EDIT_BUILD_MAX_TOOL_ROUNDS,
                                roundsCompleted = workshopBuildToolRound - 1,
                                toolNames = toolNames,
                                lastAssistantText = response.textResponse,
                                phase = resolvedWorkshopPhase,
                                mode = workshopMode,
                                activeKickoff = activeBuildKickoff,
                                pendingProposalCount = pendingProposalCount,
                            )
                            return EidosResponse(
                                textResponse = pauseText,
                                toolCalls = emptyList(),
                                providerResponseId = currentResponseId,
                                assistantReasoningContent = response.assistantReasoningContent,
                                workshopPausedForToolCap = true,
                                workshopToolRoundsCompleted = workshopBuildToolRound - 1,
                                workshopPausedForHandoff = true,
                            ).withReasoningTrace(reasoningHops)
                        }
                    }
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
                        }
                        workshopChatToolRound += 1
                        if (workshopChatToolRound > WORKSHOP_CHAT_MAX_TOOL_ROUNDS) {
                            return EidosResponse(
                                textResponse = response.textResponse.ifBlank {
                                    "I'm in Chat mode and have read enough context. Reply with your question, " +
                                        "or switch to Edit mode if you want file changes."
                                },
                                toolCalls = emptyList(),
                                providerResponseId = currentResponseId,
                                assistantReasoningContent = response.assistantReasoningContent,
                            ).withReasoningTrace(reasoningHops)
                        }
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
                            }
                        }

                        val toolResult = toolExecutor.execute(
                            toolCall.name,
                            enrichToolArguments(
                                toolName = toolCall.name,
                                argumentsJson = toolCall.argumentsJson,
                                currentSubfolderId = currentSubfolderId,
                                currentScopeType = currentScopeType,
                            ),
                        )
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
                    // Workshop multi-tool runs need full system + history every hop (file manifest, mode, IDs).
                    val useIncremental = !isPanelWorkshop &&
                        providerFamilyUsesIncrementalToolContinuation(family) &&
                        !currentResponseId.isNullOrBlank()
                    workingRequest = buildTracedRequest(
                        traceRecorder = traceRecorder,
                        systemPrompt = if (useIncremental) "" else assembledSystemPrompt,
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
                    currentResponseId = response.providerResponseId
                }

                return response.withReasoningTrace(reasoningHops)
            } catch (t: CancellationException) {
                traceStatus = "cancelled"
                throw t
            } catch (t: Throwable) {
                attempts += 1
                lastError = t
                val isDns = isDnsError(t)
                if (isTransientNetworkError(t)) {
                    // DNS failures get more attempts — the foreground service may need
                    // several seconds to re-elevate network priority after app minimise.
                    maxAttempts = maxOf(maxAttempts, if (isDns) 5 else 4)
                }
                if (attempts < maxAttempts) {
                    delay(
                        when {
                            isDns -> (2_000L shl (attempts - 1).coerceAtMost(3)).coerceAtMost(15_000L)
                            isTransientNetworkError(t) -> 2_500L
                            else -> 700L
                        },
                    )
                }
            }
        }

        traceStatus = "error"
        return EidosResponse(
            textResponse = buildProviderErrorMessage(
                activeProvider = providerConfig.activeProvider,
                error = lastError,
                attemptedRetry = true,
            ),
            toolCalls = emptyList(),
        ).withReasoningTrace(reasoningHops)
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
        EidosSendForegroundService.acquire(context)
        try {
            KimiFormulaToolService(apiKey = config.apiKey, client = httpClient, json = json)
                .ensureLoadedWithRetry()
        } finally {
            EidosSendForegroundService.release(context)
        }
    }

    private suspend fun getProviderConfig(): ProviderConfig {
        val encryptedPrefs = getEncryptedPrefs(context)
        val activeProvider = encryptedPrefs.getString(EncryptedSettingKeys.ACTIVE_PROVIDER, null)
            ?: context.settingsDataStore.data.first()[SettingsKeys.ACTIVE_PROVIDER]
            ?: SettingsDefaults.ACTIVE_PROVIDER

        val keyName = when (activeProvider) {
            "openai" -> ApiKeyNames.OPENAI
            "anthropic" -> ApiKeyNames.ANTHROPIC
            "kimi" -> ApiKeyNames.KIMI
            else -> ApiKeyNames.XAI
        }

        val apiKey = encryptedPrefs.getString(keyName, null)
        return ProviderConfig(activeProvider = activeProvider, apiKey = apiKey)
    }

    private fun getProvider(
        activeProvider: String,
        apiKey: String,
        xaiModel: String,
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

        if (ChatMessagePersistLimits.isCursorWindowRowTooLarge(error)) {
            return "Local storage hit SQLite's row size limit (oversized chat message). " +
                "The app will try to repair this on the next send; if it persists, tap New chat for this workshop.$retryLine " +
                "Clearing build plan or deleting IMPLEMENTATION_PLAN.md does not remove old chat rows."
        }

        val detail = error?.message?.take(220)?.trim().orEmpty()
        return if (detail.isNotBlank()) {
            "$provider is unavailable right now.$retryLine Details: $detail"
        } else {
            "$provider is unavailable right now.$retryLine Try again or switch providers in Settings."
        }
    }

    private fun providerDisplayName(activeProvider: String): String = when (activeProvider) {
        "openai" -> "OpenAI"
        "anthropic" -> "Anthropic"
        "kimi" -> "Kimi (Moonshot)"
        else -> "xAI"
    }

    private fun providerEndpoint(activeProvider: String): String = when (activeProvider) {
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
        chatHistoryTrimmed: Boolean = false,
        conversationMemoryDepth: String = SettingsDefaults.CONVERSATION_MEMORY_DEPTH,
        dumpEditUserMessage: String? = null,
        activeProvider: String? = null,
        kimiFormulaToolsLoaded: Boolean = false,
    ): String {
        val activeScope = currentScopeType ?: when {
            currentSubfolderId != null -> "subfolder"
            currentParentFolderId != null -> "parent"
            else -> "general"
        }
        val isWorkshopScope = activeScope == ConversationScopes.PANEL_WORKSHOP
        val lines = mutableListOf(baseSystemPrompt)
        lines += if (isWorkshopScope) {
            EidosContextLimits.WORKSHOP_TOOL_FIRST_CONTEXT_RULES
        } else {
            EidosContextLimits.TOOL_FIRST_CONTEXT_RULES
        }
        if (!isWorkshopScope) {
            lines += """
                Active location rule:
                - Default all folder/note create/write actions to the current in-app location.
                - Do not create or write in other folders unless the user explicitly names a different destination.
            """.trimIndent()
        }
        when {
            isWorkshopScope && activeProvider == "kimi" && kimiFormulaToolsLoaded -> lines += """
                Kimi Formula (Panel Workshop): web_search and fetch for public docs/APIs when needed; cite URLs.
                Workshop tools only in this scope — see mode instructions for the active allowlist.
            """.trimIndent()
            isWorkshopScope && activeProvider == "kimi" -> lines += """
                Kimi Formula web_search/fetch did not load — use workshop tools and local context only.
            """.trimIndent()
            isWorkshopScope -> lines += """
                Use the active provider's hosted web search when available for public facts; cite sources.
            """.trimIndent()
            activeProvider == "kimi" && kimiFormulaToolsLoaded -> lines += """
                Provider-native web access is enabled when the selected API provider supports it (xAI, OpenAI, Anthropic, Kimi).
                Use provider web search for public web information when available; cite source links when present.
                Kimi uses Moonshot Formula web_search and fetch with thinking enabled (not Kimi builtin_function search).
                For Kimi: use web_search to discover sources, then fetch to read specific URLs when page content is needed.
                When fetch or search returns URLs, cite them in your reply (markdown links when helpful).
                Kimi also has Formula utility tools — use them instead of in-thought math or flattened reads:
                • convert — unit and currency conversions (length, mass, volume, temperature, currency, etc.)
                • date — date/time arithmetic, weekday, timezone math, formatting
                • excel — Excel/CSV structural analysis. Prefer this over read_file for .xlsx/.xls/.csv (read_file flattens cells).
                Local Eidos tools handle OptimalX folders, notes, files, journal, log, and local search.
            """.trimIndent()
            activeProvider == "kimi" -> lines += """
                Kimi Formula web tools (web_search, fetch) are unavailable this turn — the Moonshot Formula API did not load.
                Answer from local knowledge and OptimalX tools only; tell the user live web lookup failed if they asked for current web data.
                Local Eidos tools handle OptimalX folders, notes, files, journal, log, and local search.
            """.trimIndent()
            else -> lines += """
                Provider-native web access is enabled when the selected API provider supports it (xAI, OpenAI, Anthropic, Kimi).
                Use provider web search for public web information when available; cite source links when present.
                Local Eidos tools handle OptimalX folders, notes, files, journal, log, and local search.
            """.trimIndent()
        }
        lines += "Active scope: $activeScope"

        val isWebChatScope = activeScope == "web_editor" || activeScope == "web_widget"
        val loadedWebUrl = webPanelPageUrl?.trim().orEmpty()
        if (loadedWebUrl.isNotEmpty()) {
            lines += """
                In-app web browser (OptimalX Web panel):
                Loaded page URL: $loadedWebUrl
                The WebView renders this page for the user, but page HTML/text is NOT in this prompt.
                When they refer to "this page", "this site", "the article", "here", or visible page content, they mean this URL — call Kimi Formula fetch on it (or web_search then fetch) before answering; do not guess page content.
            """.trimIndent()
        }
        if (isWebChatScope) {
            val webToolNote = if (activeProvider == "kimi" && kimiFormulaToolsLoaded) {
                """
                - Kimi Formula web_search and fetch are available — use them for live web facts and to read page bodies (including the loaded tab URL).
                - web_search: discover sources and current public information; fetch: read a specific URL's content for grounding and citations.
                """.trimIndent()
            } else if (activeProvider == "kimi") {
                """
                - Kimi Formula web_search/fetch did not load this turn — say live web lookup is unavailable if the user needs off-device facts or page text.
                """.trimIndent()
            } else {
                """
                - Use the active provider's hosted web search/fetch when available for live facts and URL content.
                """.trimIndent()
            }
            lines += """
                Web-scoped chat rules:
                - The loaded tab URL (when present) is focus metadata only — you cannot see the WebView; retrieve page text via tools.
                $webToolNote
                - Use prior messages in this same search thread only when they help answer about the current page or the active search topic.
                - Do not assume context from other search threads unless the user explicitly refers to earlier research outside this search.
            """.trimIndent()
        }

        if (activeScope == ConversationScopes.PANEL_GALLERY) {
            lines += PanelGalleryContext.buildPromptBlock(context, database)
        }

        if (activeScope == ConversationScopes.DUMP_EDIT) {
            val app = context.applicationContext as OptimalXApplication
            lines += DumpEditContext.buildPromptBlock(
                context = context,
                userMessage = dumpEditUserMessage,
                contentSectionRetriever = ContentSectionRetriever(app.embeddingEngine),
            )
            lines += """
                DumpEdit rules:
                - The user is in the DumpEdit scratch buffer — not a saved folder note.
                - Use read_dump_edit(query=...) to read buffer sections when content is large or AI locked.
                - Do not use write_note on DumpEdit; the user promotes to a folder when they want to keep content.
            """.trimIndent()
        } else if (currentSubfolderId != null) {
            val subfolder = database.subfolderDao().getById(currentSubfolderId)
            if (subfolder != null) {
                if (activeScope == ConversationScopes.PANEL_RUNNER) {
                    lines += PanelRunnerContext.buildPromptBlock(context, database, subfolder)
                } else if (activeScope == ConversationScopes.PANEL_WORKSHOP) {
                    lines += buildWorkshopPanelContext(
                        subfolder = subfolder,
                        workshopOpenFileName = workshopOpenFileName,
                        workshopOpenFileContent = workshopOpenFileContent,
                        workshopEidosMode = workshopEidosMode ?: WorkshopEidosMode.EDIT,
                        workshopProjectPhase = workshopProjectPhase,
                        workshopDocAlignScope = workshopDocAlignScope,
                        workshopUpdateSection = workshopUpdateSection,
                        workshopUserTurns = workshopUserTurns,
                    )
                } else {
                    lines += buildSubfolderContext(subfolder)
                }
                val workshopEditWithBridge = activeScope == ConversationScopes.PANEL_WORKSHOP &&
                    WorkshopEidosMode.normalizeToUserChip(
                        workshopEidosMode ?: WorkshopEidosMode.EDIT,
                    ) == WorkshopEidosMode.EDIT &&
                    (workshopProjectPhase?.allowsCallPanelFunction == true)
                val skipPanelBridge = activeScope == ConversationScopes.PANEL_WORKSHOP && !workshopEditWithBridge
                if (!skipPanelBridge || activeScope == ConversationScopes.PANEL_RUNNER) {
                    val bridgeContext = buildPanelBridgeContext(
                        currentSubfolderId = currentSubfolderId,
                        currentScopeType = activeScope,
                    )
                    if (bridgeContext != null) {
                        lines += bridgeContext
                    } else when (activeScope) {
                        ConversationScopes.PANEL_WORKSHOP ->
                            if (workshopEditWithBridge) {
                                lines += PanelPlatformSpec.inactivePanelBridgeContextBlock()
                            }
                        ConversationScopes.PANEL_RUNNER ->
                            lines += PanelPlatformSpec.inactivePanelRunnerBridgeContextBlock()
                        else -> Unit
                    }
                }
                lines += "Active parent folder ID: ${subfolder.parentFolderId}"
                if (!isWebChatScope) {
                    val surface = subfolderEditorSurfaceHint?.trim().orEmpty()
                    if (surface.isNotEmpty()) {
                        lines += "Optional UI context (use only when relevant; the user may discuss any topic):\n$surface"
                    }
                }
            }
        } else if (currentParentFolderId != null) {
            lines += buildParentFolderContext(currentParentFolderId)
        }

        lines += "In-chat memory depth: $conversationMemoryDepth (conversation override or Settings default)."
        if (chatHistoryTrimmed) {
            lines += PanelPlatformSpec.historyTrimNotice(conversationMemoryDepth)
        }

        return lines.joinToString("\n\n")
    }

    private suspend fun buildPanelBridgeContext(
        currentSubfolderId: Long,
        currentScopeType: String,
    ): String? {
        val snapshot = panelBridgeRegistry?.snapshot(
            currentSubfolderId = currentSubfolderId,
            currentScopeType = currentScopeType,
        )
        if (snapshot == null || !snapshot.hasEligiblePanel) {
            return null
        }
        val fnList = if (snapshot.availableFunctions.isEmpty()) "(none reported)" else snapshot.availableFunctions.joinToString(", ")
        val eventLines = if (snapshot.recentEvents.isEmpty()) {
            "- Recent panel events: (none)"
        } else {
            snapshot.recentEvents.joinToString(
                separator = "\n",
                prefix = "- Recent panel events:\n",
            ) { evt ->
                "  - ${evt.name} @ ${evt.timestamp}: ${evt.payloadJson.take(220)}"
            }
        }
        return """
            Panel Bridge (ACTIVE — call_panel_function will reach live panel JS):
            - Context: ${snapshot.activeContextType ?: "unknown"} (workshopSubfolderId=${snapshot.activeWorkshopSubfolderId ?: -1})
            - Registered functions: $fnList
            - getState: functionName=getState, args="{}" — returns panel/game state from script.js panelGetState
            - runAction: functionName=runAction, args JSON string e.g. {"action":"newGame"} or {"action":"move","x":1} — handled by script.js panelHandleAction
            - Game/agent loop: getState → plan → runAction → getState until done; use search_semantic or workshop_read_file(query) for script.js action names
            $eventLines
        """.trimIndent()
    }

    private fun enrichToolArguments(
        toolName: String,
        argumentsJson: String,
        currentSubfolderId: Long?,
        currentScopeType: String?,
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
                val scopeProjectId = when (currentScopeType) {
                    ConversationScopes.PANEL_WORKSHOP, ConversationScopes.PANEL_RUNNER -> currentSubfolderId
                    else -> null
                }
                if (scopeProjectId == null) {
                    return argumentsJson
                }
                val enriched = buildJsonObject {
                    args.forEach { (key, value) -> put(key, value) }
                    if (!args.containsKey("scopeType")) {
                        put("scopeType", JsonPrimitive("local_first"))
                    }
                    if (!args.containsKey("scopeId")) {
                        put("scopeId", JsonPrimitive(scopeProjectId))
                    }
                }
                enriched.toString()
            }
            "workshop_write_file", "workshop_create_file", "workshop_replace_string",
            "workshop_read_file", "workshop_list_pending_review",
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
                }
                enriched.toString()
            }
            else -> argumentsJson
        }
    }

    private suspend fun buildWorkshopPanelContext(
        subfolder: Subfolder,
        workshopOpenFileName: String?,
        workshopOpenFileContent: String?,
        workshopEidosMode: WorkshopEidosMode,
        workshopProjectPhase: WorkshopProjectPhase?,
        workshopDocAlignScope: WorkshopDocAlignScope?,
        workshopUpdateSection: WorkshopUpdateSection?,
        workshopUserTurns: Int,
    ): String {
        val phase = workshopProjectPhase
            ?: WorkshopProjectPreferences.getProjectPhase(context, subfolder.id)
        val updateSection = workshopUpdateSection
            ?: WorkshopProjectPreferences.getUpdateSection(context, subfolder.id)
        val intakeSummary = WorkshopProjectPreferences.getIntakeSummary(context, subfolder.id)
        val files = database.fileReferenceDao().getBySubfolderOnce(subfolder.id)
        val fileManifest = WorkshopProjectContext.formatFileManifest(files)
        val openExcerpt = if (workshopEidosMode == WorkshopEidosMode.CHAT) {
            null
        } else {
            WorkshopProjectContext.formatOpenFileExcerpt(
                workshopOpenFileName,
                workshopOpenFileContent,
            )
        }

        val specFallback = if (workshopEidosMode != WorkshopEidosMode.CHAT &&
            phase != WorkshopProjectPhase.INTAKE
        ) {
            WorkshopSpecMarkdown.loadBounded(files)
        } else {
            ""
        }

        val diffReviewStatusBlock = if (WorkshopReviewPolicy.shouldReview(phase, workshopEidosMode)) {
            val openSet = database.pendingChangeDao().findOpenSetForScope(
                SCOPE_WORKSHOP_PROJECT,
                subfolder.id,
            )
            val pendingItems = openSet?.let { database.pendingChangeDao().countPending(it.id) } ?: 0
            WorkshopProjectContext.formatDiffReviewStatus(pendingItems)
        } else {
            null
        }

        return buildString {
            appendLine("Panel Workshop — custom HTML/JS panel project")
            appendLine("Project: ${subfolder.name} (subfolderId=${subfolder.id})")
            appendLine("Workshop phase: ${phase.displayName} (${phase.name})")
            appendLine("Active Eidos mode: ${workshopEidosMode.displayName} (${workshopEidosMode.name})")
            if (phase == WorkshopProjectPhase.UPDATE) {
                appendLine(
                    "Update/edit: Chat, Plan, or Edit — read spec .md (incl. IMPLEMENTATION_PLAN.md) anytime; " +
                        "spec writes and doc align run on Accept update.",
                )
            }
            appendLine()
            appendLine(fileManifest)
            if (diffReviewStatusBlock != null) {
                appendLine()
                appendLine(diffReviewStatusBlock)
            }
            if (openExcerpt != null) {
                appendLine()
                append(openExcerpt)
            } else {
                val openName = workshopOpenFileName?.trim().orEmpty()
                appendLine()
                appendLine(
                    when {
                        workshopEidosMode == WorkshopEidosMode.CHAT && openName.isNotEmpty() ->
                            "Editor tab: $openName (Chat mode — discuss only; use search_semantic or workshop_read_file with query)"
                        openName.isNotEmpty() ->
                            "Editor tab: $openName (excerpt synced from disk before this send — still prefer workshop_read_file with query before large edits)"
                        else -> "Editor tab: (none reported)"
                    },
                )
            }
            appendLine()
            appendLine(PanelPlatformSpec.eidosInstructionsForMode(
                workshopEidosMode,
                subfolder.id,
                phase,
                workshopDocAlignScope,
                updateSection,
            ))
            appendLine()
            appendLine(workshopContentPolicy(workshopEidosMode, phase, workshopDocAlignScope, updateSection))
            if (intakeSummary.isNotBlank()) {
                appendLine()
                appendLine("Intake summary (chat alignment — authoritative for spec generation):")
                appendLine(intakeSummary)
            }
            if (phase == WorkshopProjectPhase.SPEC_REVIEW) {
                val specContents = loadWorkshopSpecContents(files)
                appendLine()
                appendLine(WorkshopSpecValidation.formatCapReport(specContents))
                val readiness = WorkshopSpecValidation.evaluateAcceptReadiness(specContents)
                appendLine()
                appendLine(
                    if (readiness.ready) {
                        "Spec accept gate: ready — user may tap Accept specs after reviewing Docs."
                    } else {
                        "Spec accept gate: not ready — ${readiness.message}"
                    },
                )
            }
            if (phase == WorkshopProjectPhase.DESIGN_REVIEW) {
                appendLine()
                appendLine(
                    "Design review: read spec .md with workshop_read_file; do not write spec .md in Edit until Accept design (doc align). " +
                        "User validates layout in Preview.",
                )
            }
            if (phase == WorkshopProjectPhase.UPDATE) {
                appendLine()
                appendLine(
                    "Update/edit: runtime/code is truth for Preview; read IMPLEMENTATION_PLAN.md and other specs in Edit. " +
                        "Spec .md writes and sync to code happen on Accept update (doc align), not per edit.",
                )
            }
            if (specFallback.isNotBlank()) {
                appendLine()
                appendLine("Spec markdown (bounded orientation — use search_semantic / workshop_read_file for full text):")
                appendLine(specFallback)
            } else if (workshopEidosMode != WorkshopEidosMode.CHAT && phase != WorkshopProjectPhase.INTAKE) {
                appendLine()
                appendLine(
                    "Spec orientation: use search_semantic or workshop_read_file on README/spec .md files.",
                )
            }
            if (WorkshopEidosModeResolver.shouldNudgeNewChat(workshopUserTurns)) {
                appendLine()
                appendLine(PanelPlatformSpec.newChatNudge(workshopUserTurns))
            }
            appendLine()
            appendLine("User's current request is authoritative for this turn.")
        }.trim()
    }

    private suspend fun buildSubfolderContext(subfolder: Subfolder): String {
        val note = database.noteDao().getBySubfolderOnce(subfolder.id)

        val noteContext = when {
            note == null -> "Note: none."
            note.aiBlind -> "Note: blind from Eidos (do not request content)."
            note.aiLocked -> {
                val summaryBlock = ContentSummaryService.formatNoteSummaryForPrompt(note)
                if (summaryBlock != null) {
                    "Note: AI lock — summary below; use read_note(subfolderId=${subfolder.id}) only if needed.\n$summaryBlock"
                } else {
                    "Note: AI lock (use read_note tool if user allows)."
                }
            }
            else -> {
                val summaryBlock = ContentSummaryService.formatNoteSummaryForPrompt(note)
                if (summaryBlock != null) {
                    "Note: present — summary below; use read_note(subfolderId=${subfolder.id}) for full text.\n$summaryBlock"
                } else {
                    "Note: present — use read_note(subfolderId=${subfolder.id}) for full text; no summary yet (user can Generate Summary)."
                }
            }
        }

        return buildString {
            appendLine("Current subfolder:")
            appendLine("Name: ${subfolder.name}")
            appendLine("subfolderId: ${subfolder.id}")
            appendLine(noteContext)
            appendLine("Attachments: use list_folder_contents / read_file — not listed inline.")
        }.trim()
    }

    private suspend fun buildParentFolderContext(parentFolderId: Long): String {
        val parent = database.parentFolderDao().getById(parentFolderId)
            ?: return "Parent folder context unavailable."
        return """
            Current parent folder:
            Name: ${parent.name}
            parentFolderId: ${parent.id}
            Subfolders: use list_folder_contents(folderId=${parent.id}) — not listed inline.
        """.trimIndent()
    }

    private fun loadWorkshopSpecContents(files: List<com.example.optimalx.data.model.FileReference>): Map<String, String> =
        files
            .filter { it.fileType.equals("md", ignoreCase = true) }
            .associate { ref ->
                ref.fileName to runCatching {
                    java.io.File(ref.filePath).readText()
                }.getOrDefault("")
            }

    private fun workshopContentPolicy(
        mode: WorkshopEidosMode,
        phase: WorkshopProjectPhase,
        docAlignScope: WorkshopDocAlignScope? = null,
        updateSection: WorkshopUpdateSection? = null,
    ): String {
        val chip = WorkshopEidosMode.normalizeToUserChip(mode)
        return when {
        docAlignScope != null ->
            "Workshop content policy (Align docs / Plan): read runtime code and existing spec .md as needed; " +
                "write spec .md only where out of date — no HTML/CSS/JS changes."
        phase == WorkshopProjectPhase.INTAKE ->
            "Workshop content policy (Intake): chat only — no file writes. Spec files are not generated until the user taps Generate specs."
        phase == WorkshopProjectPhase.SPEC_REVIEW && chip == WorkshopEidosMode.CHAT ->
            "Workshop content policy (Spec review / Chat): discuss specs only — no file writes. Plan mode for doc edits; user taps Accept specs when aligned."
        phase == WorkshopProjectPhase.SPEC_REVIEW && chip == WorkshopEidosMode.PLAN ->
            "Workshop content policy (Spec review / Plan): .md spec files only — no runtime writes until Accept specs and Build design."
        phase == WorkshopProjectPhase.SPEC_REVIEW && chip == WorkshopEidosMode.EDIT ->
            "Workshop content policy (Spec review / Edit): prefer Plan for .md; runtime edits only if user explicitly needs a scaffold tweak."
        mode == WorkshopEidosMode.BUILD_DESIGN && phase == WorkshopProjectPhase.DESIGN_BUILD ->
            "Workshop content policy (Design build / Build design): runtime shell only (index.html, style.css, stub script.js) — no .md read/write."
        phase == WorkshopProjectPhase.DESIGN_BUILD && chip == WorkshopEidosMode.PLAN ->
            "Workshop content policy (Design build / Plan): .md spec files only — use Build design for layout shell."
        phase == WorkshopProjectPhase.DESIGN_BUILD && chip == WorkshopEidosMode.EDIT ->
            "Workshop content policy (Design build / Edit): HTML/CSS/stub JS — no .md writes until Accept design."
        phase == WorkshopProjectPhase.DESIGN_REVIEW && chip == WorkshopEidosMode.CHAT ->
            "Workshop content policy (Design review / Chat): discuss only — no file writes."
        phase == WorkshopProjectPhase.DESIGN_REVIEW && chip == WorkshopEidosMode.PLAN ->
            "Workshop content policy (Design review / Plan): read any file; write spec .md only (runtime edits in Edit mode)."
        phase == WorkshopProjectPhase.DESIGN_REVIEW ->
            "Workshop content policy (Design review / Edit): HTML/CSS/stub JS only — .md files frozen until Accept design."
        mode == WorkshopEidosMode.BUILD_LOGIC && phase == WorkshopProjectPhase.LOGIC_BUILD ->
            "Workshop content policy (Logic build / Build logic): script.js and bridge.js primary; no .md read/write."
        phase == WorkshopProjectPhase.LOGIC_BUILD && chip == WorkshopEidosMode.PLAN ->
            "Workshop content policy (Logic build / Plan): .md spec files only — use Build logic for behavior."
        phase == WorkshopProjectPhase.LOGIC_BUILD ->
            "Workshop content policy (Logic build / Edit): edit runtime files directly — Preview not required for workshop_write_file."
        phase == WorkshopProjectPhase.LOGIC_REVIEW && chip == WorkshopEidosMode.CHAT ->
            "Workshop content policy (Logic review / Chat): discuss only — no file writes."
        phase == WorkshopProjectPhase.LOGIC_REVIEW && chip == WorkshopEidosMode.EDIT && phase.allowsDebugMode ->
            "Workshop content policy (Logic review / Edit): edit runtime files directly — no .md writes until Accept logic."
        phase == WorkshopProjectPhase.LOGIC_REVIEW ->
            "Workshop content policy (Logic review / Edit): edit runtime files directly — no .md writes until Accept logic."
        phase == WorkshopProjectPhase.UPDATE && chip == WorkshopEidosMode.CHAT ->
            "Workshop content policy (Update / Chat): discuss only — no file writes."
        phase == WorkshopProjectPhase.UPDATE && chip == WorkshopEidosMode.PLAN ->
            "Workshop content policy (Update / Plan): .md spec files only — runtime edits in Edit mode."
        mode == WorkshopEidosMode.BUILD_PLAN && phase == WorkshopProjectPhase.UPDATE ->
            "Workshop content policy (Update / Build plan): build-run — runtime + ${PanelPlatformSpec.IMPLEMENTATION_PLAN_MD} phase markers; direct disk; Auto-Continue across plan phases."
        phase == WorkshopProjectPhase.UPDATE ->
            "Workshop content policy (Update / Edit): runtime writes in Edit; read any spec .md (incl. IMPLEMENTATION_PLAN.md); spec writes on Accept update only."
        chip == WorkshopEidosMode.CHAT ->
            "Workshop content policy (Chat): discuss only — no file writes. " +
                "Use search_semantic for project content; optional workshop_read_file with query when search is insufficient."
        chip == WorkshopEidosMode.PLAN ->
            "Workshop content policy: search_semantic first for spec passages; writes limited to .md spec files (fileReferenceId from manifest)."
        else ->
            "Workshop content policy: search_semantic → workshop_read_file(query or line range) → workshop_write_file. " +
                "Do not read full large files without query; open-tab excerpt in prompt may be stale after edits."
    }
    }

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
        const val WORKSHOP_CHAT_MAX_TOOL_ROUNDS = 2
    }

    private fun buildTracedRequest(
        traceRecorder: EidosApiTraceRecorder?,
        systemPrompt: String,
        conversationHistory: List<EidosMessage>,
        toolDefinitions: List<EidosToolDefinition>,
        userMessage: String,
        previousResponseId: String?,
        promptCacheKey: String?,
        phase: EidosRequestPhase,
        streamListener: EidosStreamListener?,
    ): EidosRequest = EidosRequest(
        systemPrompt = systemPrompt,
        conversationHistory = conversationHistory,
        toolDefinitions = toolDefinitions,
        userMessage = userMessage,
        previousResponseId = previousResponseId,
        promptCacheKey = promptCacheKey,
        phase = phase,
        streamListener = streamListener,
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
