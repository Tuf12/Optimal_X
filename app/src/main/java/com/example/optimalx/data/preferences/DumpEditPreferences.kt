package com.example.optimalx.data.preferences

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.longPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import com.example.optimalx.data.sync.SyncContentHash
import com.example.optimalx.data.sync.SyncGlobalIds
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map

val Context.dumpEditDataStore: DataStore<Preferences> by preferencesDataStore(name = "dump_edit")

object DumpEditKeys {
    val CONTENT = stringPreferencesKey("dump_edit_content")
    val AI_LOCKED = booleanPreferencesKey("dump_edit_ai_locked")
    val AI_BLIND = booleanPreferencesKey("dump_edit_ai_blind")
    val GLOBAL_ID = stringPreferencesKey("dump_edit_global_id")
    val UPDATED_AT = longPreferencesKey("dump_edit_updated_at")
    val CONTENT_HASH = stringPreferencesKey("dump_edit_content_hash")
}

data class DumpEditState(
    val content: String = "",
    val aiLocked: Boolean = false,
    val aiBlind: Boolean = false,
    val globalId: String = SyncGlobalIds.DUMP_EDIT,
    val updatedAt: Long = 0L,
    val contentHash: String = SyncContentHash.sha256Hex(""),
)

object DumpEditPreferences {

    fun observeState(context: Context): Flow<DumpEditState> =
        context.dumpEditDataStore.data.map { prefs -> prefs.toDumpEditState() }

    suspend fun readState(context: Context): DumpEditState {
        ensureSyncMetadata(context)
        return observeState(context).first()
    }

    suspend fun saveContent(context: Context, content: String) {
        val now = System.currentTimeMillis()
        context.dumpEditDataStore.edit { prefs ->
            prefs[DumpEditKeys.CONTENT] = content
            prefs[DumpEditKeys.UPDATED_AT] = now
            prefs[DumpEditKeys.CONTENT_HASH] = SyncContentHash.sha256Hex(content)
            if (prefs[DumpEditKeys.GLOBAL_ID] == null) {
                prefs[DumpEditKeys.GLOBAL_ID] = SyncGlobalIds.DUMP_EDIT
            }
        }
    }

    suspend fun setAiLocked(context: Context, locked: Boolean) {
        touchUpdatedAt(context)
        context.dumpEditDataStore.edit { prefs ->
            prefs[DumpEditKeys.AI_LOCKED] = locked
        }
    }

    suspend fun setAiBlind(context: Context, blind: Boolean) {
        touchUpdatedAt(context)
        context.dumpEditDataStore.edit { prefs ->
            prefs[DumpEditKeys.AI_BLIND] = blind
        }
    }

    suspend fun clearContent(context: Context) {
        saveContent(context, "")
    }

    suspend fun restoreState(context: Context, state: DumpEditState) {
        val now = System.currentTimeMillis()
        context.dumpEditDataStore.edit { prefs ->
            prefs[DumpEditKeys.CONTENT] = state.content
            prefs[DumpEditKeys.AI_LOCKED] = state.aiLocked
            prefs[DumpEditKeys.AI_BLIND] = state.aiBlind
            prefs[DumpEditKeys.GLOBAL_ID] = state.globalId.ifBlank { SyncGlobalIds.DUMP_EDIT }
            prefs[DumpEditKeys.UPDATED_AT] = if (state.updatedAt > 0L) state.updatedAt else now
            prefs[DumpEditKeys.CONTENT_HASH] = state.contentHash.ifBlank {
                SyncContentHash.sha256Hex(state.content)
            }
        }
    }

    /** One-time backfill of sync fields for installs that predated v28. */
    suspend fun ensureSyncMetadata(context: Context) {
        context.dumpEditDataStore.edit { prefs ->
            if (prefs[DumpEditKeys.GLOBAL_ID] == null) {
                prefs[DumpEditKeys.GLOBAL_ID] = SyncGlobalIds.DUMP_EDIT
            }
            val content = prefs[DumpEditKeys.CONTENT].orEmpty()
            if (prefs[DumpEditKeys.CONTENT_HASH] == null) {
                prefs[DumpEditKeys.CONTENT_HASH] = SyncContentHash.sha256Hex(content)
            }
            if (prefs[DumpEditKeys.UPDATED_AT] == null) {
                prefs[DumpEditKeys.UPDATED_AT] = 0L
            }
        }
    }

    private suspend fun touchUpdatedAt(context: Context) {
        context.dumpEditDataStore.edit { prefs ->
            prefs[DumpEditKeys.UPDATED_AT] = System.currentTimeMillis()
        }
    }

    private fun Preferences.toDumpEditState(): DumpEditState {
        val content = this[DumpEditKeys.CONTENT].orEmpty()
        return DumpEditState(
            content = content,
            aiLocked = this[DumpEditKeys.AI_LOCKED] ?: false,
            aiBlind = this[DumpEditKeys.AI_BLIND] ?: false,
            globalId = this[DumpEditKeys.GLOBAL_ID] ?: SyncGlobalIds.DUMP_EDIT,
            updatedAt = this[DumpEditKeys.UPDATED_AT] ?: 0L,
            contentHash = this[DumpEditKeys.CONTENT_HASH]
                ?: SyncContentHash.sha256Hex(content),
        )
    }
}
