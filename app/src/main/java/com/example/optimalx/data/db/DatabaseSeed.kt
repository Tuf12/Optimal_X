package com.example.optimalx.data.db

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.preferencesDataStore
import com.example.optimalx.data.model.ParentFolder
import com.example.optimalx.data.sync.SyncGlobalIds
import com.example.optimalx.data.imagestudio.ImageStudioSeed
import kotlinx.coroutines.flow.first

private val Context.seedDataStore: DataStore<Preferences> by preferencesDataStore(name = "seed_preferences")
private val SEED_DONE_KEY = booleanPreferencesKey("seed_done")

// Names of protected system parent folders
object SystemFolderNames {
    const val CHATS_SUBFOLDER = "Chats"
// Legacy name for orphaned system subfolders on upgraded DBs (v25+ no longer seeds).
    const val PARENT_MEMORY_CACHE_SUBFOLDER = "Memory Cache"
    const val PARENT_REASONING_SUBFOLDER = "Reasoning"
    const val EIDOS_JOURNAL = "Eidos Journal"
    const val EIDOS_LOG = "Eidos Log"
    const val EIDOS_CHATS = "Eidos Chats"
    const val EIDOS_DAILY = "Eidos Daily"
    const val EIDOS_MEMORY = "Eidos Memory"
    const val EIDOS_REASONING = "Eidos Reasoning"
    const val QUICK_NOTES = "Quick Notes"
    const val PANEL_WORKSHOP = "Panel Workshop"
    const val IMAGE_STUDIO = "Image Studio"
}

/**
 * Ensures required system-level parent folders exist.
 * Creates any missing system folder with isSystemFolder = true.
 * Journal/Log/Daily/Memory stay menu-only. Chats + Quick Notes + Panel Workshop
 * are reached from the parent-page pinned row. Eidos Chats remains in DB for legacy
 * chat threads but is hidden from folder UI.
 */
suspend fun seedDatabaseIfNeeded(context: Context, db: AppDatabase) {
    val dao = db.parentFolderDao()

    listOf(
        SystemFolderNames.EIDOS_JOURNAL,
        SystemFolderNames.EIDOS_LOG,
        SystemFolderNames.EIDOS_CHATS,
        SystemFolderNames.EIDOS_DAILY,
        SystemFolderNames.EIDOS_MEMORY,
        SystemFolderNames.QUICK_NOTES,
        SystemFolderNames.PANEL_WORKSHOP,
        SystemFolderNames.IMAGE_STUDIO,
    ).forEachIndexed { index, name ->
        if (dao.getSystemFolderByName(name) == null) {
            dao.insert(
                ParentFolder(
                    name = name,
                    sortOrder = index,
                    isSystemFolder = true,
                    globalId = SyncGlobalIds.systemParentGlobalIdOrNew(name, isSystemFolder = true),
                )
            )
        }
    }

    ImageStudioSeed.ensureSeeded(db)

    context.seedDataStore.edit { it[SEED_DONE_KEY] = true }
}
