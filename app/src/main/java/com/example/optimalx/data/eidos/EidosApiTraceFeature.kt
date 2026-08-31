package com.example.optimalx.data.eidos

import android.content.Context
import com.example.optimalx.data.preferences.SettingsKeys
import com.example.optimalx.data.preferences.settingsDataStore
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map

/**
 * Developer-only API trace inspector (Eidos section → API Trace).
 *
 * Disabled by default. Toggle in Settings → Developer to capture and view outbound LLM payloads.
 * When off: no DB writes, no Eidos section entry, zero runtime overhead on the hot path beyond
 * a single preference read per send.
 */
object EidosApiTraceFeature {

    /** Shipping default — inspector hidden until user opts in. */
    const val ENABLED_BY_DEFAULT = false

    /** Test override; null uses DataStore / [ENABLED_BY_DEFAULT]. */
    @Volatile
    var enabledOverride: Boolean? = null

    fun isEnabledFlow(context: Context): Flow<Boolean> =
        context.applicationContext.settingsDataStore.data.map { prefs ->
            enabledOverride ?: prefs[SettingsKeys.EIDOS_API_TRACE_ENABLED] ?: ENABLED_BY_DEFAULT
        }

    suspend fun isEnabled(context: Context): Boolean =
        enabledOverride ?: context.applicationContext.settingsDataStore.data.first()
            .let { it[SettingsKeys.EIDOS_API_TRACE_ENABLED] ?: ENABLED_BY_DEFAULT }
}
