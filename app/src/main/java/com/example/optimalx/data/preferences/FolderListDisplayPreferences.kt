package com.example.optimalx.data.preferences

import android.content.Context
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import com.example.optimalx.ui.folders.FolderLayoutMode
import com.example.optimalx.ui.folders.SortOrder
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map

/** Identifies a folder list screen whose sort and layout choices are stored independently. */
sealed class FolderListScope(val storageKey: String) {
    data object ParentHome : FolderListScope("parent_home")

    data class Subfolder(val parentFolderId: Long) : FolderListScope("subfolder_$parentFolderId")

    data object PanelGallery : FolderListScope("panel_gallery")
}

object FolderListDisplayPreferences {
    private fun sortKey(scope: FolderListScope): Preferences.Key<String> =
        stringPreferencesKey("folder_list_sort_${scope.storageKey}")

    private fun layoutKey(scope: FolderListScope): Preferences.Key<String> =
        stringPreferencesKey("folder_list_layout_${scope.storageKey}")

    fun sortOrderFlow(context: Context, scope: FolderListScope): Flow<SortOrder> =
        context.settingsDataStore.data.map { prefs ->
            SortOrder.fromKey(prefs[sortKey(scope)]) ?: SortOrder.NAME_ASC
        }

    fun layoutModeFlow(context: Context, scope: FolderListScope): Flow<FolderLayoutMode> =
        context.settingsDataStore.data.map { prefs ->
            val scoped = prefs[layoutKey(scope)]
            val legacy = prefs[SettingsKeys.FOLDER_LAYOUT]
            FolderLayoutMode.fromKey(scoped ?: legacy ?: SettingsDefaults.FOLDER_LAYOUT)
        }

    suspend fun setSortOrder(context: Context, scope: FolderListScope, order: SortOrder) {
        context.settingsDataStore.edit { prefs ->
            prefs[sortKey(scope)] = order.key
        }
    }

    suspend fun setLayoutMode(context: Context, scope: FolderListScope, mode: FolderLayoutMode) {
        context.settingsDataStore.edit { prefs ->
            prefs[layoutKey(scope)] = mode.key
        }
    }
}
