package com.example.optimalx.data.sync

import com.example.optimalx.data.model.Subfolder
import org.junit.Assert.assertEquals
import org.junit.Test

class SyncFingerprintsTest {

    @Test
    fun subfolder_localAndDto_fingerprintsMatch() {
        val local = Subfolder(
            parentFolderId = 1L,
            name = "Inbox",
            sortOrder = 2,
            deletedAt = null,
            targetPlatform = "mobile",
            globalId = "abc",
        )
        val dto = SyncSubfolderRow(
            globalId = "abc",
            parentFolderGlobalId = "parent-uuid-should-not-affect-fingerprint",
            name = "Inbox",
            createdAt = 1L,
            updatedAt = 1L,
            sortOrder = 2,
            deletedAt = null,
            targetPlatform = "mobile",
        )
        assertEquals(SyncFingerprints.subfolder(local), SyncFingerprints.subfolder(dto))
    }

    @Test
    fun subfolder_blankTargetPlatform_normalizesToMobile() {
        val dto = SyncSubfolderRow(
            globalId = "x",
            parentFolderGlobalId = "p",
            name = "Workshop",
            createdAt = 1L,
            updatedAt = 1L,
            targetPlatform = "",
        )
        val mobile = SyncSubfolderRow(
            globalId = "x",
            parentFolderGlobalId = "p",
            name = "Workshop",
            createdAt = 1L,
            updatedAt = 1L,
            targetPlatform = "mobile",
        )
        assertEquals(SyncFingerprints.subfolder(dto), SyncFingerprints.subfolder(mobile))
    }

    @Test
    fun noteContentHash_fillsFromBodyWhenWireHashMissing() {
        val content = "Hello **world**"
        val hash = SyncContentHash.noteContentHash(content)
        assertEquals(hash, SyncFingerprints.noteContentHash("", content))
        assertEquals(hash, SyncFingerprints.noteContentHash(hash, content))
    }
}
