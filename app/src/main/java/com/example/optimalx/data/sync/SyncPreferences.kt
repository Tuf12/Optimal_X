package com.example.optimalx.data.sync

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.longPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import com.example.optimalx.data.preferences.getEncryptedPrefs
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import java.util.UUID

private val Context.syncDataStore: DataStore<Preferences> by preferencesDataStore(name = "desktop_sync")

object SyncPreferenceKeys {
    val SERVER_HOST = stringPreferencesKey("sync_server_host")
    val SERVER_PORT = stringPreferencesKey("sync_server_port")
    val LAST_SYNC_AT = longPreferencesKey("sync_last_sync_at")
    val DEVICE_ID = stringPreferencesKey("sync_device_id")
}

private const val ENCRYPTED_TOKEN_KEY = "sync_bearer_token"

data class SyncPreferencesSnapshot(
    val host: String = "",
    val port: Int = SyncPairingUri.DEFAULT_PORT,
    val token: String = "",
    val lastSyncAt: Long = 0L,
    val deviceId: String = "",
)

class SyncPreferences(private val context: Context) {

    private val encrypted = getEncryptedPrefs(context)

    val snapshot: Flow<SyncPreferencesSnapshot> =
        context.syncDataStore.data.map { prefs ->
            SyncPreferencesSnapshot(
                host = prefs[SyncPreferenceKeys.SERVER_HOST].orEmpty(),
                port = prefs[SyncPreferenceKeys.SERVER_PORT]?.toIntOrNull()
                    ?: SyncPairingUri.DEFAULT_PORT,
                token = encrypted.getString(ENCRYPTED_TOKEN_KEY, "").orEmpty(),
                lastSyncAt = prefs[SyncPreferenceKeys.LAST_SYNC_AT] ?: 0L,
                deviceId = prefs[SyncPreferenceKeys.DEVICE_ID].orEmpty(),
            )
        }

    suspend fun readSnapshot(): SyncPreferencesSnapshot = snapshot.first()

    suspend fun ensureDeviceId(): String {
        val existing = readSnapshot().deviceId
        if (existing.isNotBlank()) return existing
        val created = "mobile_${UUID.randomUUID()}"
        context.syncDataStore.edit { it[SyncPreferenceKeys.DEVICE_ID] = created }
        return created
    }

    suspend fun saveEndpointAfterSuccessfulTest(endpoint: SyncEndpoint) {
        context.syncDataStore.edit { prefs ->
            prefs[SyncPreferenceKeys.SERVER_HOST] = endpoint.host
            prefs[SyncPreferenceKeys.SERVER_PORT] = endpoint.port.toString()
        }
        encrypted.edit()
            .putString(ENCRYPTED_TOKEN_KEY, endpoint.token)
            .apply()
    }

    suspend fun saveDraft(host: String, port: Int, token: String) {
        context.syncDataStore.edit { prefs ->
            prefs[SyncPreferenceKeys.SERVER_HOST] = host.trim()
            prefs[SyncPreferenceKeys.SERVER_PORT] = port.toString()
        }
        if (token.isNotBlank()) {
            encrypted.edit().putString(ENCRYPTED_TOKEN_KEY, token.trim()).apply()
        }
    }

    suspend fun updateLastSyncAt(epochMs: Long) {
        context.syncDataStore.edit { it[SyncPreferenceKeys.LAST_SYNC_AT] = epochMs }
    }

    fun buildEndpoint(snapshot: SyncPreferencesSnapshot): SyncEndpoint? {
        val host = snapshot.host.trim()
        val token = snapshot.token.trim()
        if (host.isEmpty() || token.isEmpty()) return null
        return SyncEndpoint(host = host, port = snapshot.port, token = token)
    }
}
