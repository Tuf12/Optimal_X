package com.example.optimalx

import android.app.Application
import android.util.Log
import com.example.optimalx.data.db.AppDatabase
import com.example.optimalx.data.db.seedDatabaseIfNeeded
import com.example.optimalx.data.eidos.EidosIndexFeature
import com.example.optimalx.data.eidos.AppIndexMaterializer
import com.example.optimalx.data.eidos.AppIndexSyncService
import com.example.optimalx.data.eidos.ContentSummaryService
import com.example.optimalx.data.eidos.EidosApiClient
import com.example.optimalx.data.eidos.MemoryRolloverScheduler
import com.example.optimalx.data.eidos.MemoryRolloverService
import com.example.optimalx.data.eidos.PanelBridgeRegistry
import com.example.optimalx.data.eidos.RoomToolExecutor
import com.example.optimalx.data.eidos.agentbyte.TagHintNotifierAndroid
import com.example.optimalx.data.eidos.agentbyte.TagHintIndexingService
import androidx.datastore.preferences.core.edit
import com.example.optimalx.data.preferences.ApiKeyNames
import com.example.optimalx.data.preferences.SettingsDefaults
import com.example.optimalx.data.preferences.SettingsKeys
import com.example.optimalx.data.preferences.getEncryptedPrefs
import com.example.optimalx.data.preferences.settingsDataStore
import com.example.optimalx.data.repository.FolderRepository
import com.example.optimalx.data.repository.HomePinRepository
import com.example.optimalx.data.repository.PanelStateRepository
import com.example.optimalx.voice.WakeWordDetector
import com.example.optimalx.data.eidos.FileTextExtractor
import com.example.optimalx.data.semantic.EmbeddingEngine
import com.example.optimalx.data.semantic.SemanticChunkBuilder
import com.example.optimalx.data.semantic.SemanticIndexer
import com.example.optimalx.data.semantic.SemanticMaterializer
import com.example.optimalx.data.semantic.SemanticSyncService
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch

class OptimalXApplication : Application() {

    private val appScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    /** Outlives Activity/ViewModel; Eidos chat sends run here so they finish after UI closes or app minimizes. */
    val eidosSendScope: CoroutineScope get() = appScope

    val database get() = AppDatabase.getInstance(this)
    val embeddingEngine by lazy { EmbeddingEngine(this) }
    val semanticIndexer by lazy { SemanticIndexer(database, embeddingEngine) }
    val semanticChunkBuilder by lazy {
        SemanticChunkBuilder(
            noteDao = database.noteDao(),
            subfolderDao = database.subfolderDao(),
            parentFolderDao = database.parentFolderDao(),
            conversationDao = database.conversationDao(),
            chatMessageDao = database.chatMessageDao(),
        )
    }
    private val semanticMaterializer by lazy {
        SemanticMaterializer(
            db = database,
            semanticIndexer = semanticIndexer,
            chunkBuilder = semanticChunkBuilder,
            fileTextExtractor = FileTextExtractor(this),
        )
    }
    val semanticSyncService by lazy { SemanticSyncService(semanticMaterializer, appScope) }

    // ON HOLD — Eidos Index (tag_hint_lines). Sync/materializer inactive while EidosIndexFeature is off.
    private val appIndexMaterializer by lazy { AppIndexMaterializer(database, database.tagHintLineDao()) }
    val appIndexSyncService by lazy { AppIndexSyncService(appIndexMaterializer, appScope) }
    val homePinRepository by lazy { HomePinRepository(database) }
    val panelStateRepository by lazy { PanelStateRepository(database) }
    val folderRepository by lazy {
        FolderRepository(
            database,
            semanticIndexer,
            appIndexSyncService,
            semanticSyncService,
            semanticChunkBuilder,
            homePinRepository,
            panelStateRepository,
        )
    }
    val wakeWordDetector by lazy { WakeWordDetector(this) }
    val panelBridgeRegistry by lazy { PanelBridgeRegistry() }

    /**
     * Home widget "New chat" clears the active thread in [WidgetVoiceService]; widget chat UI
     * collects this so the in-memory session matches without reopening the activity.
     */
    private val widgetChatSessionResetBus = MutableSharedFlow<Unit>(extraBufferCapacity = 0)

    val widgetChatSessionResetEvents = widgetChatSessionResetBus.asSharedFlow()

    fun notifyWidgetChatSessionReset() {
        widgetChatSessionResetBus.tryEmit(Unit)
    }
    val tagHintNotifier by lazy {
        if (EidosIndexFeature.isActive) TagHintNotifierAndroid(this) else com.example.optimalx.data.eidos.agentbyte.TagHintNotifier.NoOp
    }
    val eidosApiClient by lazy {
        EidosApiClient(
            context = this,
            database = database,
            toolExecutor = RoomToolExecutor(
                context = this,
                db = database,
                embeddingEngine = embeddingEngine,
                semanticIndexer = semanticIndexer,
                folderRepository = folderRepository,
                tagHintNotifier = tagHintNotifier,
                panelBridgeRegistry = panelBridgeRegistry,
            ),
            panelBridgeRegistry = panelBridgeRegistry,
        )
    }
    val contentSummaryService by lazy {
        ContentSummaryService(
            context = this,
            database = database,
            eidosApiClient = eidosApiClient,
        )
    }
    val memoryRolloverService by lazy {
        MemoryRolloverService(
            context = this,
            database = database,
            eidosApiClient = eidosApiClient,
            embeddingEngine = embeddingEngine,
            semanticIndexer = semanticIndexer,
            appIndexSync = appIndexSyncService,
        )
    }
    // ON HOLD — TagHintIndexingService (background index enrichment). Not started while index is off.
    val tagHintIndexingService by lazy {
        TagHintIndexingService.fromApiClient(
            db = database,
            apiClient = eidosApiClient,
            scope = appScope,
        )
    }

    override fun onCreate() {
        super.onCreate()
        MemoryRolloverScheduler.ensureScheduled(this)
        appScope.launch {
            migrateLegacySttPreferences()
            seedDatabaseIfNeeded(this@OptimalXApplication, database)
            // ON HOLD — index bootstrap on startup (EidosIndexFeature). Use semantic embeddings instead.
            if (EidosIndexFeature.isActive) {
                appIndexSyncService.requestSync("startup_seed")
            }
            // Load/embed probe before background semantic bootstrap — TextEmbedder JNI is not thread-safe.
            val diagnostics = embeddingEngine.diagnostics()
            Log.i("OptimalX.Semantic", "Embedding diagnostics: ${diagnostics.toLogMessage()}")
            semanticSyncService.requestSync("startup_seed")
        }
    }

    private suspend fun migrateLegacySttPreferences() {
        val settingsSnapshot = settingsDataStore.data.first()
        val sttKey = settingsSnapshot[SettingsKeys.STT_BACKEND] ?: SettingsDefaults.STT_BACKEND
        val legacySttKeys = setOf(
            "sherpa_onnx",
            "local_whisper",
            "auto",
            "android_recognizer",
        )
        if (sttKey in legacySttKeys) {
            settingsDataStore.edit { prefs ->
                prefs[SettingsKeys.STT_BACKEND] = SettingsDefaults.STT_BACKEND
            }
        }
        settingsDataStore.edit { prefs ->
            prefs.remove(SettingsKeys.WHISPER_MODEL_FILE_NAME)
        }
        val openAiKey = getEncryptedPrefs(this)
            .getString(ApiKeyNames.OPENAI, null)
            ?.trim()
            .orEmpty()
        val whisperMicOn = settingsSnapshot[SettingsKeys.MIC_USE_WHISPER_API]
            ?: SettingsDefaults.MIC_USE_WHISPER_API
        if (whisperMicOn && openAiKey.isBlank()) {
            settingsDataStore.edit { prefs ->
                prefs[SettingsKeys.MIC_USE_WHISPER_API] = false
            }
            Log.i("OptimalX.Settings", "Whisper mic disabled — no OpenAI API key saved")
        }
    }

    // ON HOLD — manual index bootstrap for development when EidosIndexFeature is re-enabled.
    fun bootstrapTagHintIndex() {
        if (!EidosIndexFeature.isActive) {
            Log.i("OptimalX.TagHintIndex", "Eidos Index on hold; bootstrap skipped")
            return
        }
        appScope.launch {
            val count = appIndexSyncService.syncNow("manual_bootstrap").upsertedCount
            Log.i("OptimalX.TagHintIndex", "Bootstrap materialized $count index rows")
        }
    }
}
