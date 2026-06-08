package com.example.optimalx.ui.settings

import android.app.Application
import android.net.Uri
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.example.optimalx.data.backup.OptimalXBackupManager
import com.example.optimalx.data.preferences.ApiKeyNames
import com.example.optimalx.data.preferences.EncryptedSettingKeys
import com.example.optimalx.data.eidos.EidosApiTraceFeature
import com.example.optimalx.data.eidos.EidosContextLimits
import com.example.optimalx.data.eidos.provider.XAI_MODEL_CHOICES
import com.example.optimalx.data.preferences.SettingsDefaults
import com.example.optimalx.data.preferences.SettingsKeys
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

data class DataBackupUiState(
    val isWorking: Boolean = false,
    val message: String? = null,
)

class SettingsViewModel(app: Application) : AndroidViewModel(app) {

    private val ctx = app.applicationContext
    private val appRef = app as com.example.optimalx.OptimalXApplication
    private val appVersionName: String = runCatching {
        ctx.packageManager.getPackageInfo(ctx.packageName, 0).versionName
    }.getOrNull() ?: "unknown"

    // Lazy so the MasterKey is created off the main thread if needed
    private val encPrefs by lazy { getEncryptedPrefs(ctx) }

    private val _memoryRollover = MutableStateFlow(MemoryRolloverUiState())
    val memoryRollover: StateFlow<MemoryRolloverUiState> = _memoryRollover.asStateFlow()

    private val _semanticIndex = MutableStateFlow(SemanticIndexUiState())
    val semanticIndex: StateFlow<SemanticIndexUiState> = _semanticIndex.asStateFlow()

    private val _dataBackup = MutableStateFlow(DataBackupUiState())
    val dataBackup: StateFlow<DataBackupUiState> = _dataBackup.asStateFlow()

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
            // Keep DataStore mirror for backward compatibility with existing app state.
            ctx.settingsDataStore.edit { it[SettingsKeys.ACTIVE_PROVIDER] = value }
        }
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

    val conversationMemoryDepth: StateFlow<String> = ctx.settingsDataStore.data
        .map { prefs ->
            val stored = prefs[SettingsKeys.CONVERSATION_MEMORY_DEPTH]
            if (stored.isNullOrBlank() || stored !in EidosContextLimits.MEMORY_OPTIONS) {
                SettingsDefaults.CONVERSATION_MEMORY_DEPTH
            } else {
                stored
            }
        }
        .stateIn(
            viewModelScope,
            SharingStarted.WhileSubscribed(5000),
            SettingsDefaults.CONVERSATION_MEMORY_DEPTH,
        )

    fun setConversationMemoryDepth(value: String) {
        if (value !in EidosContextLimits.MEMORY_OPTIONS) return
        viewModelScope.launch {
            ctx.settingsDataStore.edit { it[SettingsKeys.CONVERSATION_MEMORY_DEPTH] = value }
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
            ctx.settingsDataStore.edit { it[SettingsKeys.MIC_USE_WHISPER_API] = enabled }
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

    val workshopAutoContinueEnabled: StateFlow<Boolean> = ctx.settingsDataStore.data
        .map { it[SettingsKeys.WORKSHOP_AUTO_CONTINUE_ENABLED] ?: SettingsDefaults.WORKSHOP_AUTO_CONTINUE_ENABLED }
        .stateIn(
            viewModelScope,
            SharingStarted.WhileSubscribed(5000),
            SettingsDefaults.WORKSHOP_AUTO_CONTINUE_ENABLED,
        )

    val workshopPauseBetweenChunks: StateFlow<Boolean> = ctx.settingsDataStore.data
        .map { it[SettingsKeys.WORKSHOP_PAUSE_BETWEEN_CHUNKS] ?: SettingsDefaults.WORKSHOP_PAUSE_BETWEEN_CHUNKS }
        .stateIn(
            viewModelScope,
            SharingStarted.WhileSubscribed(5000),
            SettingsDefaults.WORKSHOP_PAUSE_BETWEEN_CHUNKS,
        )

    fun setWorkshopAutoContinueEnabled(enabled: Boolean) {
        viewModelScope.launch {
            ctx.settingsDataStore.edit { it[SettingsKeys.WORKSHOP_AUTO_CONTINUE_ENABLED] = enabled }
        }
    }

    fun setWorkshopPauseBetweenChunks(enabled: Boolean) {
        viewModelScope.launch {
            ctx.settingsDataStore.edit { it[SettingsKeys.WORKSHOP_PAUSE_BETWEEN_CHUNKS] = enabled }
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

    fun exportBackup(destination: Uri) {
        if (_dataBackup.value.isWorking) return
        viewModelScope.launch(Dispatchers.IO) {
            _dataBackup.value = DataBackupUiState(isWorking = true, message = "Exporting backup...")
            val result = OptimalXBackupManager.export(
                context = ctx,
                destination = destination,
                appVersionName = appVersionName,
            )
            _dataBackup.value = DataBackupUiState(
                isWorking = false,
                message = result.message,
            )
        }
    }

    fun importBackup(source: Uri) {
        if (_dataBackup.value.isWorking) return
        viewModelScope.launch(Dispatchers.IO) {
            _dataBackup.value = DataBackupUiState(isWorking = true, message = "Importing backup...")
            val result = OptimalXBackupManager.import(context = ctx, source = source)
            if (result.success) {
                appRef.semanticSyncService.requestSync("backup_import")
            }
            _dataBackup.value = DataBackupUiState(
                isWorking = false,
                message = result.message,
            )
        }
    }
}
