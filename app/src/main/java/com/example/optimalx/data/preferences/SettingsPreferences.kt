package com.example.optimalx.data.preferences

import android.content.Context
import android.content.SharedPreferences
import android.util.Log
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import androidx.security.crypto.EncryptedSharedPreferences
import androidx.security.crypto.MasterKey
import java.io.IOException
import java.security.GeneralSecurityException
import java.security.KeyStore

val Context.settingsDataStore: DataStore<Preferences> by preferencesDataStore(name = "settings")

private const val ENCRYPTED_PREFS_NAME = "optimalx_api_keys"
private const val TAG = "OptimalX.EncryptedPrefs"

object SettingsKeys {
    val ACTIVE_PROVIDER = stringPreferencesKey("active_provider")
    /** xAI Responses API model id (e.g. grok-4.3). */
    val XAI_MODEL = stringPreferencesKey("xai_model")
    val WAKE_WORD = stringPreferencesKey("wake_word")
    val READ_ALOUD = booleanPreferencesKey("read_aloud")
    /** When true with read aloud, mic re-opens after TTS (chat + widget hands-free loop). */
    val READ_ALOUD_MIC_PASSBACK = booleanPreferencesKey("read_aloud_mic_passback")
    /** Home widget Quick Ask / Quick Notes: TTS + optional mic loop after reply. */
    val WIDGET_VOICE_HANDS_FREE = booleanPreferencesKey("widget_voice_hands_free")
    /** When true and an OpenAI key is saved, chat/notes mics use Whisper API. */
    val MIC_USE_WHISPER_API = booleanPreferencesKey("mic_use_whisper_api")
    val FOLDER_LAYOUT = stringPreferencesKey("folder_layout")
    /** Legacy key — migrated to [SettingsDefaults.STT_BACKEND] on startup. */
    val STT_BACKEND = stringPreferencesKey("stt_backend")
    /** Legacy key — removed on startup after Whisper uninstall. */
    val WHISPER_MODEL_FILE_NAME = stringPreferencesKey("whisper_model_file_name")
    /** Eidos in-chat memory: low | medium | high (see EidosContextLimits). */
    val CONVERSATION_MEMORY_DEPTH = stringPreferencesKey("conversation_memory_depth")
    /** True after the user has seen and dismissed the Read Aloud info dialog. */
    val READ_ALOUD_INFO_DISMISSED = booleanPreferencesKey("read_aloud_info_dismissed")
    /** Developer: capture outbound LLM API payloads for the API Trace inspector. */
    val EIDOS_API_TRACE_ENABLED = booleanPreferencesKey("eidos_api_trace_enabled")
}

object SettingsDefaults {
    const val ACTIVE_PROVIDER = "xai"
    /** Default Grok model; keep in sync with DEFAULT_GROK_MODEL in XAIProvider. */
    const val XAI_MODEL = "grok-4.3"
    const val WAKE_WORD = "Hey Eidos"
    const val READ_ALOUD = false
    const val READ_ALOUD_MIC_PASSBACK = true
    const val WIDGET_VOICE_HANDS_FREE = false
    const val MIC_USE_WHISPER_API = false
    const val FOLDER_LAYOUT = "grid2"
    const val STT_BACKEND = "google_recognizer"
    const val CONVERSATION_MEMORY_DEPTH = "low"
    const val READ_ALOUD_INFO_DISMISSED = false
    const val EIDOS_API_TRACE_ENABLED = false
}

object ApiKeyNames {
    const val XAI = "xai_api_key"
    const val OPENAI = "openai_api_key"
    const val ANTHROPIC = "anthropic_api_key"
    const val KIMI = "kimi_api_key"
}

object EncryptedSettingKeys {
    const val ACTIVE_PROVIDER = "active_provider"
}

fun getEncryptedPrefs(context: Context): SharedPreferences {
    val appContext = context.applicationContext
    return try {
        openEncryptedPrefs(appContext)
    } catch (e: GeneralSecurityException) {
        Log.w(TAG, "Encrypted prefs unreadable; clearing and recreating", e)
        resetEncryptedPrefsStorage(appContext)
        openEncryptedPrefs(appContext)
    } catch (e: IOException) {
        Log.w(TAG, "Encrypted prefs I/O failure; clearing and recreating", e)
        resetEncryptedPrefsStorage(appContext)
        openEncryptedPrefs(appContext)
    }
}

private fun resetEncryptedPrefsStorage(context: Context) {
    context.deleteSharedPreferences(ENCRYPTED_PREFS_NAME)
    runCatching {
        val keyStore = KeyStore.getInstance("AndroidKeyStore").apply { load(null) }
        val alias = MasterKey.DEFAULT_MASTER_KEY_ALIAS
        if (keyStore.containsAlias(alias)) {
            keyStore.deleteEntry(alias)
            Log.i(TAG, "Removed stale MasterKey alias after encrypted prefs reset")
        }
    }.onFailure { Log.w(TAG, "Could not reset MasterKey alias", it) }
}

private fun openEncryptedPrefs(context: Context): SharedPreferences =
    EncryptedSharedPreferences.create(
        context,
        ENCRYPTED_PREFS_NAME,
        MasterKey.Builder(context)
            .setKeyScheme(MasterKey.KeyScheme.AES256_GCM)
            .build(),
        EncryptedSharedPreferences.PrefKeyEncryptionScheme.AES256_SIV,
        EncryptedSharedPreferences.PrefValueEncryptionScheme.AES256_GCM,
    )
