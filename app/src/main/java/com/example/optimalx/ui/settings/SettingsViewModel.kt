package com.example.optimalx.ui.settings

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.example.optimalx.data.preferences.ApiKeyNames
import com.example.optimalx.data.preferences.EncryptedSettingKeys
import com.example.optimalx.data.eidos.EidosApiTraceFeature
import com.example.optimalx.data.eidos.provider.XAI_MODEL_CHOICES
import com.example.optimalx.data.litert.LitertLmBackend
import com.example.optimalx.data.litert.LitertLmDefaults
import com.example.optimalx.data.litert.LitertLmDiscoveredModel
import com.example.optimalx.data.litert.LitertLmModelAvailability
import com.example.optimalx.data.litert.LitertLmModelDownloadScheduler
import com.example.optimalx.data.litert.LitertLmModelDownloadState
import com.example.optimalx.data.litert.LitertLmModelDownloadTracker
import com.example.optimalx.data.litert.LitertLmModelLocator
import com.example.optimalx.data.litert.LitertLmWarmState
import com.example.optimalx.data.litert.litertModelAvailability
import com.example.optimalx.data.litert.applyLitertEngineWarmState
import com.example.optimalx.data.preferences.SettingsDefaults
import com.example.optimalx.data.preferences.SettingsKeys
import com.example.optimalx.data.imagestudio.ImageAspectRatio
import com.example.optimalx.data.imagestudio.ImageStudioPreferences
import com.example.optimalx.data.imagestudio.ImageTier
import com.example.optimalx.data.preferences.getEncryptedPrefs
import com.example.optimalx.data.preferences.settingsDataStore
import com.example.optimalx.ui.theme.getThemePreference
import com.example.optimalx.ui.theme.saveThemePreference
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import androidx.datastore.preferences.core.edit

data class MemoryRolloverUiState(
    val isRunning: Boolean = false,
    val message: String? = null,
)

data class SemanticIndexUiState(
    val isRunning: Boolean = false,
    val message: String? = null,
)

class SettingsViewModel(app: Application) : AndroidViewModel(app) {

    private val ctx = app.applicationContext
    private val appRef = app as com.example.optimalx.OptimalXApplication

    // Lazy so the MasterKey is created off the main thread if needed
    private val encPrefs by lazy { getEncryptedPrefs(ctx) }

    private val _memoryRollover = MutableStateFlow(MemoryRolloverUiState())
    val memoryRollover: StateFlow<MemoryRolloverUiState> = _memoryRollover.asStateFlow()

    private val _semanticIndex = MutableStateFlow(SemanticIndexUiState())
    val semanticIndex: StateFlow<SemanticIndexUiState> = _semanticIndex.asStateFlow()

    // ── Theme ─────────────────────────────────────────────────────────────────

    val themePreference: StateFlow<String> = getThemePreference(ctx)
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), "system")

    fun setTheme(value: String) {
        viewModelScope.launch { saveThemePreference(ctx, value) }
    }

    // ── Active provider ───────────────────────────────────────────────────────

    private val _activeProvider = MutableStateFlow(
        encPrefs.getString(
            EncryptedSettingKeys.ACTIVE_PROVIDER,
            null,
        ) ?: SettingsDefaults.ACTIVE_PROVIDER
    )
    val activeProvider: StateFlow<String> = _activeProvider.asStateFlow()

    fun setActiveProvider(value: String) {
        _activeProvider.value = value
        encPrefs.edit().putString(EncryptedSettingKeys.ACTIVE_PROVIDER, value).apply()
        viewModelScope.launch {
            ctx.settingsDataStore.edit { it[SettingsKeys.ACTIVE_PROVIDER] = value }
            applyLitertEngineWarmState(ctx)
        }
    }

    val litertModelPath: StateFlow<String> = ctx.settingsDataStore.data
        .map { prefs ->
            val stored = prefs[SettingsKeys.LITERT_MODEL_PATH]?.trim().orEmpty()
            if (stored.isNotEmpty()) stored
            else LitertLmModelLocator.canonicalInstallFile(ctx).absolutePath
        }
        .stateIn(
            viewModelScope,
            SharingStarted.WhileSubscribed(5000),
            LitertLmModelLocator.canonicalInstallFile(ctx).absolutePath,
        )

    val litertBackend: StateFlow<LitertLmBackend> = ctx.settingsDataStore.data
        .map { LitertLmBackend.fromWire(it[SettingsKeys.LITERT_BACKEND]) }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), LitertLmBackend.GPU)

    val litertModelAvailability: StateFlow<LitertLmModelAvailability> = ctx.settingsDataStore.data
        .map { prefs ->
            litertModelAvailability(ctx, prefs[SettingsKeys.LITERT_MODEL_PATH].orEmpty())
        }
        .stateIn(
            viewModelScope,
            SharingStarted.WhileSubscribed(5000),
            litertModelAvailability(ctx, ""),
        )

    val litertWarmState: StateFlow<LitertLmWarmState> = appRef.litertLmEngineHolder.warmState
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), LitertLmWarmState.Idle)

    val litertEngineError: StateFlow<String?> = appRef.litertLmEngineHolder.lastError
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), null)

    val litertModelDownloadState: StateFlow<LitertLmModelDownloadState> =
        LitertLmModelDownloadTracker.state
            .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), LitertLmModelDownloadState.Idle)

    private val _litertDiscoveredModels = MutableStateFlow<List<LitertLmDiscoveredModel>>(emptyList())
    val litertDiscoveredModels: StateFlow<List<LitertLmDiscoveredModel>> =
        _litertDiscoveredModels.asStateFlow()

    fun setLitertModelPath(path: String) {
        viewModelScope.launch {
            ctx.settingsDataStore.edit { it[SettingsKeys.LITERT_MODEL_PATH] = path.trim() }
            if (_activeProvider.value == LitertLmDefaults.PROVIDER_ID ||
                ctx.settingsDataStore.data.first()[SettingsKeys.MIC_USE_LOCAL_GEMMA_SCRIBE] == true
            ) {
                applyLitertEngineWarmState(ctx)
            }
        }
    }

    fun setLitertBackend(backend: LitertLmBackend) {
        viewModelScope.launch {
            ctx.settingsDataStore.edit { it[SettingsKeys.LITERT_BACKEND] = backend.wire }
            if (_activeProvider.value == LitertLmDefaults.PROVIDER_ID ||
                ctx.settingsDataStore.data.first()[SettingsKeys.MIC_USE_LOCAL_GEMMA_SCRIBE] == true
            ) {
                applyLitertEngineWarmState(ctx)
            }
        }
    }

    fun scanLitertModels() {
        viewModelScope.launch(Dispatchers.IO) {
            _litertDiscoveredModels.value = LitertLmModelLocator.discoverKnownModels(ctx)
        }
    }

    fun startLitertModelDownload() {
        if (LitertLmModelDownloadScheduler.isDownloadRunning(ctx)) return
        LitertLmModelDownloadTracker.markDownloading(0L, LitertLmDefaults.MODEL_SIZE_BYTES_APPROX)
        LitertLmModelDownloadScheduler.enqueueDownload(ctx)
    }

    fun useLitertModelPath(path: String) {
        viewModelScope.launch {
            ctx.settingsDataStore.edit { it[SettingsKeys.LITERT_MODEL_PATH] = path.trim() }
            applyLitertEngineWarmState(ctx)
        }
    }

    fun installLitertModelToAppStorage(sourcePath: String) {
        viewModelScope.launch(Dispatchers.IO) {
            LitertLmModelDownloadTracker.markDownloading(0L, null)
            val result = LitertLmModelLocator.copyToCanonical(
                context = ctx,
                sourcePath = sourcePath,
                onProgress = { read, total ->
                    LitertLmModelDownloadTracker.markDownloading(read, total)
                },
            )
            result.fold(
                onSuccess = { file ->
                    ctx.settingsDataStore.edit {
                        it[SettingsKeys.LITERT_MODEL_PATH] = file.absolutePath
                    }
                    LitertLmModelDownloadTracker.markComplete(file.absolutePath)
                    applyLitertEngineWarmState(ctx)
                },
                onFailure = { error ->
                    LitertLmModelDownloadTracker.markFailed(
                        error.message ?: "Could not copy model file",
                    )
                },
            )
        }
    }

    fun importLitertModelFromUri(uri: android.net.Uri) {
        viewModelScope.launch(Dispatchers.IO) {
            LitertLmModelDownloadTracker.markDownloading(0L, null)
            val result = LitertLmModelLocator.importFromUri(
                context = ctx,
                uri = uri,
                onProgress = { read, total ->
                    LitertLmModelDownloadTracker.markDownloading(read, total)
                },
            )
            result.fold(
                onSuccess = { file ->
                    ctx.settingsDataStore.edit {
                        it[SettingsKeys.LITERT_MODEL_PATH] = file.absolutePath
                    }
                    LitertLmModelDownloadTracker.markComplete(file.absolutePath)
                    applyLitertEngineWarmState(ctx)
                },
                onFailure = { error ->
                    LitertLmModelDownloadTracker.markFailed(
                        error.message ?: "Could not import model file",
                    )
                },
            )
        }
    }

    fun clearLitertModelDownloadState() {
        LitertLmModelDownloadTracker.markIdle()
    }

    // ── API keys (EncryptedSharedPreferences) ─────────────────────────────────

    private val _xaiKey = MutableStateFlow(encPrefs.getString(ApiKeyNames.XAI, "") ?: "")
    val xaiKey: StateFlow<String> = _xaiKey.asStateFlow()

    private val _openAiKey = MutableStateFlow(encPrefs.getString(ApiKeyNames.OPENAI, "") ?: "")
    val openAiKey: StateFlow<String> = _openAiKey.asStateFlow()

    private val _anthropicKey = MutableStateFlow(encPrefs.getString(ApiKeyNames.ANTHROPIC, "") ?: "")
    val anthropicKey: StateFlow<String> = _anthropicKey.asStateFlow()

    private val _kimiKey = MutableStateFlow(encPrefs.getString(ApiKeyNames.KIMI, "") ?: "")
    val kimiKey: StateFlow<String> = _kimiKey.asStateFlow()

    fun setXaiKey(key: String) {
        _xaiKey.value = key
        encPrefs.edit().putString(ApiKeyNames.XAI, key).apply()
    }

    fun setOpenAiKey(key: String) {
        val trimmed = key.trim()
        _openAiKey.value = trimmed
        encPrefs.edit().putString(ApiKeyNames.OPENAI, trimmed).apply()
    }

    /** Reload keys from encrypted storage (e.g. when opening Settings). */
    fun refreshApiKeysFromStorage() {
        _xaiKey.value = encPrefs.getString(ApiKeyNames.XAI, "")?.trim().orEmpty()
        _openAiKey.value = encPrefs.getString(ApiKeyNames.OPENAI, "")?.trim().orEmpty()
        _anthropicKey.value = encPrefs.getString(ApiKeyNames.ANTHROPIC, "")?.trim().orEmpty()
        _kimiKey.value = encPrefs.getString(ApiKeyNames.KIMI, "")?.trim().orEmpty()
    }

    fun setAnthropicKey(key: String) {
        _anthropicKey.value = key
        encPrefs.edit().putString(ApiKeyNames.ANTHROPIC, key).apply()
    }

    fun setKimiKey(key: String) {
        _kimiKey.value = key
        encPrefs.edit().putString(ApiKeyNames.KIMI, key).apply()
    }

    val xaiModel: StateFlow<String> = ctx.settingsDataStore.data
        .map { prefs ->
            val stored = prefs[SettingsKeys.XAI_MODEL]
            if (stored.isNullOrBlank()) SettingsDefaults.XAI_MODEL
            else if (XAI_MODEL_CHOICES.any { it.modelId == stored }) stored
            else SettingsDefaults.XAI_MODEL
        }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), SettingsDefaults.XAI_MODEL)

    fun setXaiModel(modelId: String) {
        viewModelScope.launch {
            ctx.settingsDataStore.edit { it[SettingsKeys.XAI_MODEL] = modelId }
        }
    }

    val imageStudioDefaultTier: StateFlow<ImageTier> = ctx.settingsDataStore.data
        .map { prefs ->
            val wire = prefs[SettingsKeys.IMAGE_STUDIO_DEFAULT_TIER]
                ?: SettingsDefaults.IMAGE_STUDIO_DEFAULT_TIER
            ImageTier.fromWire(wire) ?: ImageTier.DRAFT
        }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), ImageTier.DRAFT)

    val imageStudioDefaultAspect: StateFlow<ImageAspectRatio> = ctx.settingsDataStore.data
        .map { prefs ->
            val wire = prefs[SettingsKeys.IMAGE_STUDIO_DEFAULT_ASPECT]
                ?: SettingsDefaults.IMAGE_STUDIO_DEFAULT_ASPECT
            ImageAspectRatio.fromWire(wire) ?: ImageAspectRatio.DEFAULT
        }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), ImageAspectRatio.DEFAULT)

    fun setImageStudioDefaultTier(tier: ImageTier) {
        viewModelScope.launch {
            ImageStudioPreferences.saveDefaultTier(ctx, tier)
        }
    }

    fun setImageStudioDefaultAspect(aspectRatio: ImageAspectRatio) {
        viewModelScope.launch {
            ImageStudioPreferences.saveDefaultAspectRatio(ctx, aspectRatio)
        }
    }

    // ── Voice ─────────────────────────────────────────────────────────────────

    val wakeWord: StateFlow<String> = ctx.settingsDataStore.data
        .map { it[SettingsKeys.WAKE_WORD] ?: SettingsDefaults.WAKE_WORD }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), SettingsDefaults.WAKE_WORD)

    val readAloud: StateFlow<Boolean> = ctx.settingsDataStore.data
        .map { it[SettingsKeys.READ_ALOUD] ?: SettingsDefaults.READ_ALOUD }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), SettingsDefaults.READ_ALOUD)

    val readAloudMicPassback: StateFlow<Boolean> = ctx.settingsDataStore.data
        .map { it[SettingsKeys.READ_ALOUD_MIC_PASSBACK] ?: SettingsDefaults.READ_ALOUD_MIC_PASSBACK }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), SettingsDefaults.READ_ALOUD_MIC_PASSBACK)

    val widgetVoiceHandsFree: StateFlow<Boolean> = ctx.settingsDataStore.data
        .map { it[SettingsKeys.WIDGET_VOICE_HANDS_FREE] ?: SettingsDefaults.WIDGET_VOICE_HANDS_FREE }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), SettingsDefaults.WIDGET_VOICE_HANDS_FREE)

    val micUseWhisperApi: StateFlow<Boolean> = ctx.settingsDataStore.data
        .map { it[SettingsKeys.MIC_USE_WHISPER_API] ?: SettingsDefaults.MIC_USE_WHISPER_API }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), SettingsDefaults.MIC_USE_WHISPER_API)

    val micUseLocalGemmaScribe: StateFlow<Boolean> = ctx.settingsDataStore.data
        .map { it[SettingsKeys.MIC_USE_LOCAL_GEMMA_SCRIBE] ?: SettingsDefaults.MIC_USE_LOCAL_GEMMA_SCRIBE }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), SettingsDefaults.MIC_USE_LOCAL_GEMMA_SCRIBE)

    fun setWakeWord(word: String) {
        viewModelScope.launch {
            ctx.settingsDataStore.edit { it[SettingsKeys.WAKE_WORD] = word }
        }
    }

    fun setReadAloud(enabled: Boolean) {
        viewModelScope.launch {
            ctx.settingsDataStore.edit { it[SettingsKeys.READ_ALOUD] = enabled }
        }
    }

    fun setReadAloudMicPassback(enabled: Boolean) {
        viewModelScope.launch {
            ctx.settingsDataStore.edit { it[SettingsKeys.READ_ALOUD_MIC_PASSBACK] = enabled }
        }
    }

    fun setWidgetVoiceHandsFree(enabled: Boolean) {
        viewModelScope.launch {
            ctx.settingsDataStore.edit { it[SettingsKeys.WIDGET_VOICE_HANDS_FREE] = enabled }
        }
    }

    fun setMicUseWhisperApi(enabled: Boolean) {
        viewModelScope.launch {
            ctx.settingsDataStore.edit {
                it[SettingsKeys.MIC_USE_WHISPER_API] = enabled
                if (enabled) it[SettingsKeys.MIC_USE_LOCAL_GEMMA_SCRIBE] = false
            }
            applyLitertEngineWarmState(ctx)
        }
    }

    fun setMicUseLocalGemmaScribe(enabled: Boolean) {
        viewModelScope.launch {
            ctx.settingsDataStore.edit {
                it[SettingsKeys.MIC_USE_LOCAL_GEMMA_SCRIBE] = enabled
                if (enabled) it[SettingsKeys.MIC_USE_WHISPER_API] = false
            }
            applyLitertEngineWarmState(ctx)
        }
    }

    val eidosApiTraceEnabled: StateFlow<Boolean> = ctx.settingsDataStore.data
        .map { prefs ->
            EidosApiTraceFeature.enabledOverride
                ?: prefs[SettingsKeys.EIDOS_API_TRACE_ENABLED]
                ?: EidosApiTraceFeature.ENABLED_BY_DEFAULT
        }
        .stateIn(
            viewModelScope,
            SharingStarted.WhileSubscribed(5000),
            EidosApiTraceFeature.ENABLED_BY_DEFAULT,
        )

    fun setEidosApiTraceEnabled(enabled: Boolean) {
        viewModelScope.launch {
            ctx.settingsDataStore.edit { it[SettingsKeys.EIDOS_API_TRACE_ENABLED] = enabled }
        }
    }

    fun forceMemoryRollover() {
        if (_memoryRollover.value.isRunning) return
        viewModelScope.launch {
            _memoryRollover.value = MemoryRolloverUiState(
                isRunning = true,
                message = "Running memory rollover...",
            )
            val result = appRef.memoryRolloverService.runMemoryRollover()
            _memoryRollover.value = MemoryRolloverUiState(
                isRunning = false,
                message = result.message,
            )
        }
    }

    fun rebuildSemanticIndex() {
        if (_semanticIndex.value.isRunning) return
        viewModelScope.launch(Dispatchers.IO) {
            _semanticIndex.value = SemanticIndexUiState(
                isRunning = true,
                message = "Rebuilding semantic index...",
            )
            val result = appRef.semanticSyncService.syncNow("manual_rebuild")
            val chunkCount = appRef.database.semanticChunkDao().getAll().size
            _semanticIndex.value = SemanticIndexUiState(
                isRunning = false,
                message = "Rebuilt: $chunkCount chunks from ${result.upsertedCount} objects.",
            )
        }
    }
}
