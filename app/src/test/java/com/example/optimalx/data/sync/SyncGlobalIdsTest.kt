package com.example.optimalx.data.sync

import com.example.optimalx.data.db.SystemFolderNames
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Test

class SyncGlobalIdsTest {

    @Test
    fun systemParentGlobalIds_matchDesktopConstants() {
        assertEquals(
            "a1000007-0007-4007-8007-000000000007",
            SyncGlobalIds.systemParentGlobalId(SystemFolderNames.QUICK_NOTES),
        )
        assertEquals(
            "a1000008-0008-4008-8008-000000000008",
            SyncGlobalIds.systemParentGlobalId(SystemFolderNames.PANEL_WORKSHOP),
        )
        assertEquals(
            "a1000009-0009-4009-8009-000000000009",
            SyncGlobalIds.systemParentGlobalId(SystemFolderNames.IMAGE_STUDIO),
        )
        assertEquals(
            "b2000001-0001-4000-8001-000000000001",
            SyncGlobalIds.IMAGE_STUDIO_GENERAL_SUBFOLDER,
        )
        assertEquals(
            "b2000000-0000-4000-8000-dumpedit00001",
            SyncGlobalIds.DUMP_EDIT,
        )
    }

    @Test
    fun systemParentGlobalIdOrNew_usesStableIdForSystemFolders() {
        val id = SyncGlobalIds.systemParentGlobalIdOrNew(
            SystemFolderNames.EIDOS_JOURNAL,
            isSystemFolder = true,
        )
        assertEquals(
            "a1000001-0001-4001-8001-000000000001",
            id,
        )
    }

    @Test
    fun contentHash_isDeterministic() {
        val hash = SyncContentHash.noteContentHash("test")
        assertEquals(hash, SyncContentHash.noteContentHash("test"))
        assertEquals(64, hash.length)
    }

    @Test
    fun allSystemFoldersHaveConstants() {
        listOf(
            SystemFolderNames.EIDOS_JOURNAL,
            SystemFolderNames.EIDOS_LOG,
            SystemFolderNames.EIDOS_CHATS,
            SystemFolderNames.EIDOS_DAILY,
            SystemFolderNames.EIDOS_MEMORY,
            SystemFolderNames.EIDOS_REASONING,
            SystemFolderNames.QUICK_NOTES,
            SystemFolderNames.PANEL_WORKSHOP,
            SystemFolderNames.IMAGE_STUDIO,
        ).forEach { name ->
            assertNotNull(SyncGlobalIds.systemParentGlobalId(name))
        }
    }
}
