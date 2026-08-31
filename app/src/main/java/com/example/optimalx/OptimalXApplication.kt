package com.example.optimalx

import android.app.Application
import android.util.Log
import com.example.optimalx.data.backup.AutoBackupNotifier
import com.example.optimalx.data.db.AppDatabase
import com.example.optimalx.data.db.seedDatabaseIfNeeded
import com.example.optimalx.data.preferences.DumpEditPreferences
import com.example.optimalx.data.panel.PanelReleaseStore
import com.example.optimalx.data.eidos.ContentSummaryService
import com.example.optimalx.data.eidos.EidosApiClient
import com.example.optimalx.data.eidos.EidosNetworkMonitor
import com.example.optimalx.data.eidos.EidosLogWriter
import com.example.optimalx.data.eidos.EidosSystemFeatureFlags
import com.example.optimalx.data.eidos.MemoryRolloverScheduler
import com.example.optimalx.data.eidos.MemoryRolloverService
import com.example.optimalx.data.eidos.PanelBridgeRegistry
import com.example.optimalx.data.eidos.RoomToolExecutor
import androidx.datastore.preferences.core.edit
import com.example.optimalx.data.litert.applyLitertEngineWarmState
import com.example.optimalx.data.preferences.ApiKeyNames
import com.example.optimalx.data.preferences.SettingsDefaults
import com.example.optimalx.data.preferences.SettingsKeys
import com.example.optimalx.data.preferences.getEncryptedPrefs
import com.example.optimalx.data.preferences.settingsDataStore
import com.example.optimalx.data.repository.FolderRepository
import com.example.optimalx.data.repository.HomePinRepository
import com.example.optimalx.data.repository.PanelStateRepository
import com.example.optimalx.voice.NoteReadAloudSessionBridge
import com.example.optimalx.voice.ReadAloudSession
import com.example.optimalx.voice.WakeWordDetector
import com.example.optimalx.data.eidos.FileTextExtractor
import com.example.optimalx.data.litert.LitertLmEngineHolder
import com.example.optimalx.data.litert.LitertLmWarmPoolLifecycle
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
    val litertLmEngineHolder by lazy { LitertLmEngineHolder(this) }
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
    val homePinRepository by lazy { HomePinRepository(database) }
    val panelStateRepository by lazy { PanelStateRepository(database) }
    val folderRepository by lazy {
        FolderRepository(
            database,
            semanticIndexer,
            semanticSyncService,
            semanticChunkBuilder,
            homePinRepository,
            panelStateRepository,
        )
    }
    val readAloudSession by lazy { ReadAloudSession(this) }

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

    val eidosNetworkMonitor by lazy {
        EidosNetworkMonitor(this).also { it.start() }
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
                panelBridgeRegistry = panelBridgeRegistry,
                semanticSync = semanticSyncService,
            ),
            panelBridgeRegistry = panelBridgeRegistry,
            networkMonitor = eidosNetworkMonitor,
        )
    }
    val eidosLogWriter by lazy {
        EidosLogWriter(
            db = database,
            semanticIndexer = semanticIndexer,
            semanticChunkBuilder = semanticChunkBuilder,
        )
    }
    val contentSummaryService by lazy {
        ContentSummaryService(
            database = database,
            eidosApiClient = eidosApiClient,
            semanticChunkBuilder = semanticChunkBuilder,
            semanticIndexer = semanticIndexer,
        )
    }
    val memoryRolloverService by lazy {
        MemoryRolloverService(
            context = this,
            database = database,
            eidosApiClient = eidosApiClient,
            embeddingEngine = embeddingEngine,
            semanticIndexer = semanticIndexer,
        )
    }

    override fun onCreate() {
        super.onCreate()
        NoteReadAloudSessionBridge.register(readAloudSession)
        AutoBackupNotifier.register(this)
        LitertLmWarmPoolLifecycle.register(this)
        if (EidosSystemFeatureFlags.MEMORY_ROLLOVER_ENABLED) {
            MemoryRolloverScheduler.ensureScheduled(this)
        } else {
            MemoryRolloverScheduler.cancelScheduled(this)
        }
        appScope.launch {
            migrateLegacySttPreferences()
            seedDatabaseIfNeeded(this@OptimalXApplication, database)
            DumpEditPreferences.ensureSyncMetadata(this@OptimalXApplication)
            PanelReleaseStore.ensureAllWorkshopReleasesFromSources(this@OptimalXApplication, database)
            // Load/embed probe before background semantic bootstrap — TextEmbedder JNI is not thread-safe.
            val diagnostics = embeddingEngine.diagnostics()
            Log.i("OptimalX.Semantic", "Embedding diagnostics: ${diagnostics.toLogMessage()}")
            if (database.semanticChunkDao().count() == 0) {
                semanticSyncService.requestSync("startup_seed")
            } else {
                Log.i("OptimalX.Semantic", "Semantic index present; skipping startup bootstrap")
            }
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
        applyLitertEngineWarmState(this@OptimalXApplication)
    }
}
