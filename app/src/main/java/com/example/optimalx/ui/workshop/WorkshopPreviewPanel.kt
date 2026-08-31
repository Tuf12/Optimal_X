package com.example.optimalx.ui.workshop

import android.annotation.SuppressLint
import android.view.MotionEvent
import android.webkit.ConsoleMessage
import android.webkit.WebChromeClient
import android.webkit.WebView
import android.webkit.WebViewClient
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.viewinterop.AndroidView
import com.example.optimalx.OptimalXApplication
import com.example.optimalx.data.eidos.EidosChatSendWorker
import com.example.optimalx.data.eidos.PanelBridgeRegistry
import com.example.optimalx.data.eidos.prompt.EidosEntrySurface
import com.example.optimalx.data.eidos.WorkshopEidosModeResolver
import com.example.optimalx.data.eidos.model.EidosRequest
import com.example.optimalx.data.model.ChatMessage
import com.example.optimalx.data.model.Conversation
import com.example.optimalx.data.model.ConversationScopes
import com.example.optimalx.data.preferences.WorkshopProjectPreferences
import com.example.optimalx.data.eidos.provider.AnthropicProvider
import com.example.optimalx.data.eidos.provider.KimiProvider
import com.example.optimalx.data.eidos.provider.OpenAIProvider
import com.example.optimalx.data.eidos.provider.XAIProvider
import com.example.optimalx.data.preferences.ApiKeyNames
import com.example.optimalx.data.preferences.EncryptedSettingKeys
import com.example.optimalx.data.preferences.SettingsDefaults
import com.example.optimalx.data.preferences.SettingsKeys
import com.example.optimalx.data.preferences.getEncryptedPrefs
import com.example.optimalx.data.preferences.settingsDataStore
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import androidx.work.Data
import okhttp3.OkHttpClient
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap

private const val OPENAI_PANEL_MODEL = "gpt-5.6-luna"
private const val KIMI_PANEL_MODEL = "kimi-k2.6"

@SuppressLint("SetJavaScriptEnabled")
@Composable
fun WorkshopPreviewPanel(
    html: String,
    htmlFilePath: String? = null,
    workshopSubfolderId: Long? = null,
    panelContextType: String = "workshop_preview",
    panelStateScopeKey: String? = null,
    isVisibleAndFocused: Boolean = true,
    onConsoleError: (String) -> Unit = {},
    modifier: Modifier = Modifier,
) {
    val instanceId = remember { "panel_${UUID.randomUUID()}" }
    val app = androidx.compose.ui.platform.LocalContext.current.applicationContext as? OptimalXApplication
    val registry = app?.panelBridgeRegistry
    val bridgeScope = remember { CoroutineScope(SupervisorJob() + Dispatchers.Default) }
    val bridgeJson = remember { Json { ignoreUnknownKeys = true; explicitNulls = false } }
    val bridgeHttp = remember {
        OkHttpClient.Builder().build()
    }
    var loadedSourceKey by remember { mutableStateOf<String?>(null) }
    val sourceKey = remember(html, htmlFilePath) {
        if (!htmlFilePath.isNullOrBlank()) "file:$htmlFilePath" else "inline:${html.hashCode()}"
    }
    val panelStateStore = remember(workshopSubfolderId, panelStateScopeKey, app) {
        if (app == null || workshopSubfolderId == null || panelStateScopeKey.isNullOrBlank()) {
            null
        } else {
            val repo = app.panelStateRepository
            val scopeKey = panelStateScopeKey
            val projectId = workshopSubfolderId
            PanelBridgeJsInterface.PanelStateStore(
                scopeKey = scopeKey,
                loadStateJson = { repo.loadStateJson(projectId, scopeKey) },
                saveStateJson = { json -> repo.saveStateJson(projectId, scopeKey, json) },
            )
        }
    }

    DisposableEffect(registry, instanceId) {
        onDispose {
            if (registry != null) {
                bridgeScope.launch {
                    registry.unregister(instanceId)
                }
            }
        }
    }

    AndroidView(
        factory = { ctx ->
            WebView(ctx).apply {
                PanelHtmlComposer.configureWebViewForLocalPanel(this)
                // Keep vertical scroll in WebView when embedded in HorizontalPager (editor custom panels).
                setOnTouchListener { v, event ->
                    when (event.actionMasked) {
                        MotionEvent.ACTION_DOWN, MotionEvent.ACTION_MOVE ->
                            v.parent?.requestDisallowInterceptTouchEvent(true)
                        MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL ->
                            v.parent?.requestDisallowInterceptTouchEvent(false)
                    }
                    false
                }

                val jsInterface = if (registry != null && workshopSubfolderId != null) {
                    PanelBridgeJsInterface(
                        registry = registry,
                        instanceId = instanceId,
                        workshopSubfolderId = workshopSubfolderId,
                        contextType = panelContextType,
                        isVisibleProvider = { isVisibleAndFocused },
                        isFocusedProvider = { isVisibleAndFocused },
                        panelStateStore = panelStateStore,
                        onEventEmitted = { eventName, payloadJson, eventWorkshopSubfolderId, eventContextType ->
                            bridgeScope.launch {
                                routeBridgeEventToEidos(
                                    app = app,
                                    eventName = eventName,
                                    payloadJson = payloadJson,
                                    workshopSubfolderId = eventWorkshopSubfolderId,
                                    contextType = eventContextType,
                                )
                            }
                        },
                    )
                } else null
                if (jsInterface != null) {
                    addJavascriptInterface(jsInterface, PANEL_BRIDGE_JS_INTERFACE_NAME)
                }

                webViewClient = object : WebViewClient() {
                    override fun onPageFinished(view: WebView?, url: String?) {
                        if (view == null) return
                        installPanelBridgeShim(view)
                        val store = panelStateStore
                        if (store != null) {
                            bridgeScope.launch {
                                val saved = store.loadStateJson()
                                if (saved.isNotBlank() && saved != "{}") {
                                    kotlinx.coroutines.delay(120)
                                    restorePersistedPanelState(view, saved)
                                }
                            }
                        }
                    }
                }
                webChromeClient = object : WebChromeClient() {
                    override fun onConsoleMessage(consoleMessage: ConsoleMessage?): Boolean {
                        if (consoleMessage == null) return true
                        when (consoleMessage.messageLevel()) {
                            ConsoleMessage.MessageLevel.ERROR ->
                                onConsoleError("Line ${consoleMessage.lineNumber()}: ${consoleMessage.message()}")
                            ConsoleMessage.MessageLevel.WARNING ->
                                onConsoleError("Warning line ${consoleMessage.lineNumber()}: ${consoleMessage.message()}")
                            else -> Unit
                        }
                        return true
                    }
                }

                if (registry != null && workshopSubfolderId != null) {
                    val endpoint = object : PanelBridgeRegistry.JsEndpoint {
                        override fun invoke(requestId: String, functionName: String, argsJson: String) {
                            val intercepted = tryInterceptNativeRunAction(
                                app = app,
                                json = bridgeJson,
                                http = bridgeHttp,
                                functionName = functionName,
                                argsJson = argsJson,
                                onResolve = { payload ->
                                    bridgeScope.launch { registry.resolve(requestId, payload) }
                                },
                                onReject = { error ->
                                    bridgeScope.launch { registry.reject(requestId, error) }
                                },
                            )
                            if (intercepted) return
                            bridgeInvokeJs(this@apply, requestId, functionName, argsJson)
                        }
                    }
                    bridgeScope.launch {
                        registry.registerOrUpdate(
                            PanelBridgeRegistry.BridgeInstance(
                                instanceId = instanceId,
                                workshopSubfolderId = workshopSubfolderId,
                                contextType = panelContextType,
                                isVisible = isVisibleAndFocused,
                                isFocused = isVisibleAndFocused,
                                functions = listOf(
                                    PanelBridgeRegistry.BridgeFunction("getState", "Read current panel state"),
                                    PanelBridgeRegistry.BridgeFunction("runAction", "Run panel action", isMutating = true),
                                ),
                                endpoint = endpoint,
                            ),
                        )
                    }
                }

                PanelHtmlComposer.loadPanelContent(
                    webView = this,
                    compositeHtml = html,
                    htmlFilePath = htmlFilePath,
                )
                loadedSourceKey = sourceKey
            }
        },
        update = { webView ->
            if (registry != null && workshopSubfolderId != null) {
                bridgeScope.launch {
                    registry.updateVisibility(
                        instanceId = instanceId,
                        isVisible = isVisibleAndFocused,
                        isFocused = isVisibleAndFocused,
                    )
                }
            }
            if (loadedSourceKey != sourceKey) {
                PanelHtmlComposer.loadPanelContent(
                    webView = webView,
                    compositeHtml = html,
                    htmlFilePath = htmlFilePath,
                )
                loadedSourceKey = sourceKey
            }
        },
        modifier = modifier.fillMaxSize(),
    )
}

private fun tryInterceptNativeRunAction(
    app: OptimalXApplication?,
    json: Json,
    http: OkHttpClient,
    functionName: String,
    argsJson: String,
    onResolve: (String) -> Unit,
    onReject: (String) -> Unit,
): Boolean {
    if (app == null || !functionName.equals("runAction", ignoreCase = true)) return false
    val root = runCatching { json.parseToJsonElement(argsJson).jsonObject }.getOrNull() ?: return false
    val action = root["action"]?.jsonPrimitive?.contentOrNull?.trim().orEmpty()
    if (!action.equals("eidosInfer", ignoreCase = true)) return false

    CoroutineScope(SupervisorJob() + Dispatchers.IO).launch {
        runCatching {
            nativeEidosInfer(app = app, json = json, http = http, args = root)
        }.onSuccess { payload ->
            onResolve(payload)
        }.onFailure { t ->
            onReject(t.message ?: "eidosInfer failed")
        }
    }
    return true
}

private suspend fun nativeEidosInfer(
    app: OptimalXApplication,
    json: Json,
    http: OkHttpClient,
    args: JsonObject,
): String {
    val prompt = args["prompt"]?.jsonPrimitive?.contentOrNull?.trim().orEmpty()
    if (prompt.isBlank()) error("eidosInfer requires non-empty 'prompt'")

    val prefs = getEncryptedPrefs(app)
    val storedProvider = prefs.getString(EncryptedSettingKeys.ACTIVE_PROVIDER, null)
        ?: app.settingsDataStore.data.first()[SettingsKeys.ACTIVE_PROVIDER]
        ?: SettingsDefaults.ACTIVE_PROVIDER
    val requestedProvider = args["provider"]?.jsonPrimitive?.contentOrNull?.trim()?.lowercase()
    val provider = when (requestedProvider) {
        "openai", "anthropic", "xai", "kimi" -> requestedProvider
        null, "" -> storedProvider
        else -> error("Unsupported provider '$requestedProvider' (use openai, anthropic, xai, or kimi)")
    }

    val apiKeyName = when (provider) {
        "openai" -> ApiKeyNames.OPENAI
        "anthropic" -> ApiKeyNames.ANTHROPIC
        "kimi" -> ApiKeyNames.KIMI
        else -> ApiKeyNames.XAI
    }
    val apiKey = prefs.getString(apiKeyName, null)?.trim().orEmpty()
    if (apiKey.isBlank()) error("No API key saved for provider '$provider'")

    val modelOverride = args["model"]?.jsonPrimitive?.contentOrNull?.trim().orEmpty()
    val systemPrompt = args["systemPrompt"]?.jsonPrimitive?.contentOrNull?.trim().orEmpty()
        .ifBlank { "You are a panel-runtime inference helper for OptimalX custom panels. Return concise, structured outputs." }
    val bypassCache = args["bypassCache"]?.jsonPrimitive?.contentOrNull?.trim()?.equals("true", ignoreCase = true) == true
    val cacheTtlMs = args["cacheTtlMs"]?.jsonPrimitive?.contentOrNull?.toLongOrNull()?.coerceIn(1_000L, 3_600_000L)
        ?: InferResponseCache.DEFAULT_TTL_MS

    val resolvedModel = when (provider) {
        "xai" -> modelOverride.ifBlank {
            app.settingsDataStore.data.first()[SettingsKeys.XAI_MODEL] ?: SettingsDefaults.XAI_MODEL
        }
        "openai" -> modelOverride.ifBlank { OPENAI_PANEL_MODEL }
        "kimi" -> modelOverride.ifBlank { KIMI_PANEL_MODEL }
        else -> ""
    }
    val cacheKey = if (provider == "xai" || provider == "openai" || provider == "kimi") {
        InferResponseCache.keyFor(
            provider = provider,
            model = resolvedModel,
            systemPrompt = systemPrompt,
            prompt = prompt,
        )
    } else {
        null
    }

    val request = EidosRequest(
        systemPrompt = systemPrompt,
        conversationHistory = emptyList(),
        toolDefinitions = emptyList(),
        userMessage = prompt,
        previousResponseId = null,
        promptCacheKey = when (provider) {
            "openai", "xai" -> cacheKey ?: "optimalx-panel-infer"
            else -> null
        },
    )

    if (!bypassCache && cacheKey != null) {
        val cached = InferResponseCache.get(cacheKey, ttlMs = cacheTtlMs)
        if (cached != null) {
            return cached
        }
    }

    val response = when (provider) {
        "openai" -> OpenAIProvider(apiKey = apiKey, client = http, json = json).send(request)
        "anthropic" -> AnthropicProvider(apiKey = apiKey, client = http, json = json).send(request)
        "kimi" -> KimiProvider(apiKey = apiKey, client = http, json = json, model = resolvedModel).send(request)
        else -> {
            XAIProvider(apiKey = apiKey, client = http, json = json, model = resolvedModel).send(request)
        }
    }
    val payload = buildJsonObject {
        put("ok", JsonPrimitive(true))
        put("action", JsonPrimitive("eidosInfer"))
        put("provider", JsonPrimitive(provider))
        if (modelOverride.isNotBlank()) put("model", JsonPrimitive(modelOverride))
        put("text", JsonPrimitive(response.textResponse))
        if (!response.providerResponseId.isNullOrBlank()) {
            put("providerResponseId", JsonPrimitive(response.providerResponseId))
        }
        put("toolCalls", buildJsonArray {
            response.toolCalls.forEach { call ->
                add(
                    buildJsonObject {
                        put("id", JsonPrimitive(call.id))
                        put("name", JsonPrimitive(call.name))
                        put("argumentsJson", JsonPrimitive(call.argumentsJson))
                    },
                )
            }
        })
    }.toString()

    if (cacheKey != null && !bypassCache) {
        InferResponseCache.put(cacheKey, payload)
    }
    return payload
}

private object InferResponseCache {
    const val DEFAULT_TTL_MS: Long = 120_000L
    private const val MAX_ENTRIES: Int = 150
    private data class Entry(val value: String, val createdAtMs: Long)
    private val entries = ConcurrentHashMap<String, Entry>()

    fun keyFor(provider: String, model: String, systemPrompt: String, prompt: String): String {
        return listOf(provider, model, systemPrompt, prompt).joinToString(separator = "\u0000")
    }

    fun get(key: String, ttlMs: Long): String? {
        val now = System.currentTimeMillis()
        val entry = entries[key] ?: return null
        if (now - entry.createdAtMs > ttlMs) {
            entries.remove(key)
            return null
        }
        return entry.value
    }

    fun put(key: String, value: String) {
        val now = System.currentTimeMillis()
        if (entries.size >= MAX_ENTRIES) {
            prune(now)
        }
        entries[key] = Entry(value = value, createdAtMs = now)
    }

    private fun prune(now: Long) {
        val hardExpiry = now - (DEFAULT_TTL_MS * 3)
        entries.entries.removeIf { (_, entry) -> entry.createdAtMs < hardExpiry }
        if (entries.size <= MAX_ENTRIES) return
        entries.entries
            .sortedBy { it.value.createdAtMs }
            .take(entries.size - MAX_ENTRIES)
            .forEach { entries.remove(it.key) }
    }
}

private suspend fun routeBridgeEventToEidos(
    app: OptimalXApplication?,
    eventName: String,
    payloadJson: String,
    workshopSubfolderId: Long,
    contextType: String,
) {
    if (app == null) return
    if (!eventName.startsWith("eidos_", ignoreCase = true)) return

    val db = app.database
    val now = System.currentTimeMillis()
    val conversation = db.conversationDao().getRecentPanelWorkshop(workshopSubfolderId, 1).firstOrNull()
        ?: run {
            val title = "Panel Event — " + DateTimeFormatter.ofPattern("yyyy-MM-dd h:mm a")
                .withZone(ZoneId.systemDefault())
                .format(Instant.ofEpochMilli(now))
            val id = db.conversationDao().insert(
                Conversation(
                    scopeType = ConversationScopes.PANEL_WORKSHOP,
                    subfolderId = workshopSubfolderId,
                    title = title,
                    createdAt = now,
                    updatedAt = now,
                ),
            )
            db.conversationDao().getById(id) ?: return
        }

    val userText = buildString {
        appendLine("Panel bridge event requires action.")
        appendLine("Event: $eventName")
        appendLine("Context: $contextType")
        appendLine("Payload JSON:")
        appendLine(payloadJson)
        appendLine()
        append("Use call_panel_function as needed to complete the requested operation and report outcome.")
    }.trim()

    db.chatMessageDao().insert(
        ChatMessage(
            conversationId = conversation.id,
            role = "user",
            content = userText,
            createdAt = now,
        ),
    )
    db.conversationDao().update(conversation.copy(updatedAt = now))

    val projectPhase = WorkshopProjectPreferences.getProjectPhase(app, workshopSubfolderId)
    val updateSection = WorkshopProjectPreferences.getUpdateSection(app, workshopSubfolderId)
    val storedMode = WorkshopProjectPreferences.getEidosModeOverride(app, workshopSubfolderId)
    val activeBuildKickoff = WorkshopProjectPreferences.getBuildKickoff(app, workshopSubfolderId)
    val storedResolved = projectPhase.resolveStoredEidosMode(
        storedMode,
        updateSection,
        activeBuildKickoff = activeBuildKickoff,
    )
    val resolvedMode = if (WorkshopEidosModeResolver.isBuildKickoffModeActive(
            storedResolved,
            projectPhase,
            activeBuildKickoff,
        )
    ) {
        storedResolved
    } else {
        WorkshopEidosModeResolver.coerceModeForPhase(
            mode = storedResolved,
            phase = projectPhase,
            activeBuildKickoff = activeBuildKickoff,
        )
    }

    val workData = Data.Builder()
        .putLong(EidosChatSendWorker.KEY_CONVERSATION_ID, conversation.id)
        .putString(EidosChatSendWorker.KEY_USER_TEXT, userText.take(4000))
        .putString(EidosChatSendWorker.KEY_PREVIOUS_RESPONSE_ID, null)
        .putString(
            EidosChatSendWorker.KEY_BASE_SYSTEM_PROMPT,
            "You are Eidos inside OptimalX.\nBe concise, clear, and operationally helpful.\nUse tools when needed.",
        )
        .putString(EidosChatSendWorker.KEY_SCOPE_TYPE, ConversationScopes.PANEL_WORKSHOP)
        .putLong(EidosChatSendWorker.KEY_SUBFOLDER_ID, workshopSubfolderId)
        .putLong(EidosChatSendWorker.KEY_PARENT_ID, -1L)
        .putString(EidosChatSendWorker.KEY_EDITOR_SURFACE_HINT, "Panel bridge event: $eventName")
        .putString(EidosChatSendWorker.KEY_WEB_PANEL_PAGE_URL, null)
        .putString(EidosChatSendWorker.KEY_WORKSHOP_OPEN_FILE_NAME, null)
        .putString(EidosChatSendWorker.KEY_WORKSHOP_OPEN_FILE_CONTENT, null)
        .putString(EidosChatSendWorker.KEY_WORKSHOP_EIDOS_MODE, resolvedMode.name)
        .putString(EidosChatSendWorker.KEY_WORKSHOP_PROJECT_PHASE, projectPhase.name)
        .putString(EidosChatSendWorker.KEY_WORKSHOP_UPDATE_SECTION, updateSection?.name)
        .putString(EidosChatSendWorker.KEY_ENTRY_SURFACE, EidosEntrySurface.BACKGROUND_WORKER.name)
        .build()

    EidosChatSendWorker.enqueue(
        context = app,
        payload = workData,
        conversationId = conversation.id,
    )
}
