package com.example.optimalx.data.sync

import com.example.optimalx.data.db.SystemFolderNames
import java.util.UUID

/**
 * Stable [globalId] values shared with OptimalX Desktop
 * ([electron/seed/system-folders.js](https://github.com/Tuf12/OptimalXDesktop1.0/blob/main/electron/seed/system-folders.js)).
 */
object SyncGlobalIds {

    const val DUMP_EDIT: String = "b2000000-0000-4000-8000-dumpedit00001"
    const val IMAGE_STUDIO_PARENT: String = "a1000009-0009-4009-8009-000000000009"
    const val IMAGE_STUDIO_GENERAL_SUBFOLDER: String = "b2000001-0001-4000-8001-000000000001"

    private val SYSTEM_PARENT_GLOBAL_IDS: Map<String, String> = mapOf(
        SystemFolderNames.EIDOS_JOURNAL to "a1000001-0001-4001-8001-000000000001",
        SystemFolderNames.EIDOS_LOG to "a1000002-0002-4002-8002-000000000002",
        SystemFolderNames.EIDOS_CHATS to "a1000003-0003-4003-8003-000000000003",
        SystemFolderNames.EIDOS_DAILY to "a1000004-0004-4004-8004-000000000004",
        SystemFolderNames.EIDOS_MEMORY to "a1000005-0005-4005-8005-000000000005",
        SystemFolderNames.EIDOS_REASONING to "a1000006-0006-4006-8006-000000000006",
        SystemFolderNames.QUICK_NOTES to "a1000007-0007-4007-8007-000000000007",
        SystemFolderNames.PANEL_WORKSHOP to "a1000008-0008-4008-8008-000000000008",
        SystemFolderNames.IMAGE_STUDIO to IMAGE_STUDIO_PARENT,
    )

    fun newGlobalId(): String = UUID.randomUUID().toString()

    fun systemParentGlobalId(folderName: String): String? = SYSTEM_PARENT_GLOBAL_IDS[folderName]

    fun systemParentGlobalIdOrNew(folderName: String, isSystemFolder: Boolean): String {
        if (isSystemFolder) {
            systemParentGlobalId(folderName)?.let { return it }
        }
        return newGlobalId()
    }
}
