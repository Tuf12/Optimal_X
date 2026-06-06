package com.example.optimalx.data.preferences

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map

val Context.dumpEditDataStore: DataStore<Preferences> by preferencesDataStore(name = "dump_edit")

object DumpEditKeys {
    val CONTENT = stringPreferencesKey("dump_edit_content")
    val AI_LOCKED = booleanPreferencesKey("dump_edit_ai_locked")
    val AI_BLIND = booleanPreferencesKey("dump_edit_ai_blind")
}

data class DumpEditState(
    val content: String = "",
    val aiLocked: Boolean = false,
    val aiBlind: Boolean = false,
)

object DumpEditPreferences {

    fun observeState(context: Context): Flow<DumpEditState> =
        context.dumpEditDataStore.data.map { prefs ->
            DumpEditState(
                content = prefs[DumpEditKeys.CONTENT].orEmpty(),
                aiLocked = prefs[DumpEditKeys.AI_LOCKED] ?: false,
                aiBlind = prefs[DumpEditKeys.AI_BLIND] ?: false,
            )
        }

    suspend fun readState(context: Context): DumpEditState =
        observeState(context).first()

    suspend fun saveContent(context: Context, content: String) {
        context.dumpEditDataStore.edit { prefs ->
            prefs[DumpEditKeys.CONTENT] = content
        }
    }

    suspend fun setAiLocked(context: Context, locked: Boolean) {
        context.dumpEditDataStore.edit { prefs ->
            prefs[DumpEditKeys.AI_LOCKED] = locked
        }
    }

    suspend fun setAiBlind(context: Context, blind: Boolean) {
        context.dumpEditDataStore.edit { prefs ->
            prefs[DumpEditKeys.AI_BLIND] = blind
        }
    }

    suspend fun clearContent(context: Context) {
        context.dumpEditDataStore.edit { prefs ->
            prefs[DumpEditKeys.CONTENT] = ""
        }
    }

    suspend fun restoreState(context: Context, state: DumpEditState) {
        context.dumpEditDataStore.edit { prefs ->
            prefs[DumpEditKeys.CONTENT] = state.content
            prefs[DumpEditKeys.AI_LOCKED] = state.aiLocked
            prefs[DumpEditKeys.AI_BLIND] = state.aiBlind
        }
    }
}
