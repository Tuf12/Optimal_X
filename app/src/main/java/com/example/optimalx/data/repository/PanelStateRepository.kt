package com.example.optimalx.data.repository

import com.example.optimalx.data.dao.PanelStateDao
import com.example.optimalx.data.db.AppDatabase
import com.example.optimalx.data.model.PanelState
import com.example.optimalx.data.sync.SyncContentHash

class PanelStateRepository(
    private val panelStateDao: PanelStateDao,
) {
    constructor(db: AppDatabase) : this(panelStateDao = db.panelStateDao())

    suspend fun loadStateJson(workshopSubfolderId: Long, scopeKey: String): String {
        return panelStateDao.getStateJson(workshopSubfolderId, scopeKey)?.trim().orEmpty()
            .ifBlank { "{}" }
    }

    suspend fun saveStateJson(workshopSubfolderId: Long, scopeKey: String, stateJson: String) {
        val trimmed = stateJson.trim().ifBlank { "{}" }
        val now = System.currentTimeMillis()
        val updated = panelStateDao.updateState(
            workshopSubfolderId = workshopSubfolderId,
            scopeKey = scopeKey,
            stateJson = trimmed,
            updatedAt = now,
            contentHash = SyncContentHash.panelStateContentHash(trimmed),
        )
        if (updated == 0) {
            panelStateDao.insert(
                PanelState(
                    workshopSubfolderId = workshopSubfolderId,
                    scopeKey = scopeKey,
                    stateJson = trimmed,
                    updatedAt = now,
                    contentHash = SyncContentHash.panelStateContentHash(trimmed),
                ),
            )
        }
    }

    suspend fun deleteForWorkshopProject(workshopSubfolderId: Long) {
        panelStateDao.deleteForWorkshopProject(workshopSubfolderId)
    }

    suspend fun deleteScope(workshopSubfolderId: Long, scopeKey: String) {
        panelStateDao.deleteScope(workshopSubfolderId, scopeKey)
    }
}
