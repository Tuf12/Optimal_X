package com.example.optimalx.data.litert

import android.content.Context
import com.example.optimalx.OptimalXApplication
import com.example.optimalx.data.preferences.EncryptedSettingKeys
import com.example.optimalx.data.preferences.SettingsDefaults
import com.example.optimalx.data.preferences.SettingsKeys
import com.example.optimalx.data.preferences.getEncryptedPrefs
import com.example.optimalx.data.preferences.settingsDataStore
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

private val warmStateMutex = Mutex()

suspend fun shouldKeepLitertEngineWarm(context: Context): Boolean {
    val prefs = context.settingsDataStore.data.first()
    val localScribeOn = prefs[SettingsKeys.MIC_USE_LOCAL_GEMMA_SCRIBE]
        ?: SettingsDefaults.MIC_USE_LOCAL_GEMMA_SCRIBE
    if (localScribeOn) return true
    val provider = getEncryptedPrefs(context).getString(
        EncryptedSettingKeys.ACTIVE_PROVIDER,
        null,
    ) ?: prefs[SettingsKeys.ACTIVE_PROVIDER] ?: SettingsDefaults.ACTIVE_PROVIDER
    return provider == LitertLmDefaults.PROVIDER_ID
}

suspend fun applyLitertEngineWarmState(context: Context) = warmStateMutex.withLock {
    val app = context.applicationContext as OptimalXApplication
    if (shouldKeepLitertEngineWarm(context)) {
        val prefs = context.settingsDataStore.data.first()
        val modelPath = LitertLmDefaults.resolveModelPath(
            app,
            prefs[SettingsKeys.LITERT_MODEL_PATH].orEmpty(),
        )
        val backend = LitertLmBackend.fromWire(
            prefs[SettingsKeys.LITERT_BACKEND] ?: SettingsDefaults.LITERT_BACKEND,
        )
        app.litertLmEngineHolder.prepare(modelPath, backend)
    } else {
        app.litertLmEngineHolder.release()
    }
}

suspend fun applyLitertEngineForActiveProvider(context: Context, @Suppress("UNUSED_PARAMETER") provider: String) {
    applyLitertEngineWarmState(context)
}
