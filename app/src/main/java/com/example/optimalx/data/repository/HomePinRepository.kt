package com.example.optimalx.data.repository

import com.example.optimalx.data.dao.HomePinDao
import com.example.optimalx.data.dao.ParentFolderDao
import com.example.optimalx.data.dao.SubfolderDao
import com.example.optimalx.data.db.AppDatabase
import com.example.optimalx.data.db.SystemFolderNames
import com.example.optimalx.data.model.HomePin
import com.example.optimalx.ui.folders.HomePinType
import com.example.optimalx.ui.folders.PinnedRowItem
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.ExperimentalCoroutinesApi

@OptIn(ExperimentalCoroutinesApi::class)
class HomePinRepository(
    private val homePinDao: HomePinDao,
    private val parentFolderDao: ParentFolderDao,
    private val subfolderDao: SubfolderDao,
) {
    constructor(db: AppDatabase) : this(
        homePinDao = db.homePinDao(),
        parentFolderDao = db.parentFolderDao(),
        subfolderDao = db.subfolderDao(),
    )

    fun observeUserPins(): Flow<List<PinnedRowItem.UserPin>> =
        homePinDao.observeAll().flatMapLatest { pins ->
            flow { emit(pruneAndResolve(pins)) }
        }

    suspend fun pin(type: HomePinType, targetId: Long, displayName: String) {
        if (homePinDao.exists(type.storageKey(), targetId)) return
        val sortOrder = homePinDao.maxSortOrder() + 1
        homePinDao.insert(
            HomePin(
                pinType = type.storageKey(),
                targetId = targetId,
                displayName = displayName.trim(),
                sortOrder = sortOrder,
            ),
        )
    }

    suspend fun unpin(type: HomePinType, targetId: Long) {
        homePinDao.deleteByTarget(type.storageKey(), targetId)
    }

    suspend fun unpinById(pinId: Long) {
        homePinDao.deleteById(pinId)
    }

    suspend fun isPinned(type: HomePinType, targetId: Long): Boolean =
        homePinDao.exists(type.storageKey(), targetId)

    suspend fun onParentFolderDeleted(parentFolderId: Long) {
        homePinDao.deleteForParentFolder(parentFolderId)
        homePinDao.deleteSubfolderPinsUnderParent(parentFolderId)
    }

    suspend fun onSubfolderDeleted(subfolderId: Long) {
        homePinDao.deleteForSubfolder(subfolderId)
        homePinDao.deleteForPanel(subfolderId)
    }

    private suspend fun pruneAndResolve(pins: List<HomePin>): List<PinnedRowItem.UserPin> {
        val workshopParentId = parentFolderDao
            .getSystemFolderByName(SystemFolderNames.PANEL_WORKSHOP)
            ?.id
        val resolved = mutableListOf<PinnedRowItem.UserPin>()
        for (pin in pins) {
            val userPin = resolvePin(pin, workshopParentId)
            if (userPin != null) {
                resolved.add(userPin)
            } else {
                homePinDao.deleteById(pin.id)
            }
        }
        return resolved
    }

    private suspend fun resolvePin(
        pin: HomePin,
        workshopParentId: Long?,
    ): PinnedRowItem.UserPin? {
        return when (HomePinType.fromStorageKey(pin.pinType)) {
            HomePinType.PARENT -> {
                val folder = parentFolderDao.getById(pin.targetId) ?: return null
                if (folder.deletedAt != null || folder.isSystemFolder) return null
                PinnedRowItem.UserPin(
                    pinId = pin.id,
                    label = folder.name,
                    pinType = HomePinType.PARENT,
                    targetId = folder.id,
                )
            }
            HomePinType.SUBFOLDER -> {
                val subfolder = subfolderDao.getById(pin.targetId) ?: return null
                if (subfolder.deletedAt != null || subfolder.isSystemSubfolder) return null
                PinnedRowItem.UserPin(
                    pinId = pin.id,
                    label = subfolder.name,
                    pinType = HomePinType.SUBFOLDER,
                    targetId = subfolder.id,
                )
            }
            HomePinType.PANEL -> {
                val subfolder = subfolderDao.getById(pin.targetId) ?: return null
                if (subfolder.deletedAt != null) return null
                if (workshopParentId == null || subfolder.parentFolderId != workshopParentId) return null
                PinnedRowItem.UserPin(
                    pinId = pin.id,
                    label = subfolder.name,
                    pinType = HomePinType.PANEL,
                    targetId = subfolder.id,
                )
            }
            null -> null
        }
    }
}

fun HomePinType.storageKey(): String = when (this) {
    HomePinType.PARENT -> "parent"
    HomePinType.SUBFOLDER -> "subfolder"
    HomePinType.PANEL -> "panel"
}
