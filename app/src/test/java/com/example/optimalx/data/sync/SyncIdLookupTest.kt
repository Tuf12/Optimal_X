package com.example.optimalx.data.sync

import org.junit.Assert.assertEquals
import org.junit.Test

class SyncIdLookupTest {

    @Test
    fun workshopNewFile_mapsToSubfolderGlobalId() {
        val lookup = SyncIdLookup.forTest(
            subfolderGlobal = mapOf(42L to "subfolder-global-42"),
        )
        assertEquals(
            "subfolder-global-42",
            lookup.sourceGlobalId("workshop_new_file", 42L),
        )
    }

    @Test
    fun workshopFile_mapsToFileGlobalId() {
        val lookup = SyncIdLookup.forTest(
            fileGlobal = mapOf(7L to "file-global-7"),
        )
        assertEquals(
            "file-global-7",
            lookup.sourceGlobalId("workshop_file", 7L),
        )
    }

    @Test
    fun optionalCheckpointGlobalId_returnsNullWhenMissing() {
        val lookup = SyncIdLookup.forTest(checkpointGlobal = mapOf(1L to "cp-1"))
        assertEquals("cp-1", lookup.optionalCheckpointGlobalId(1L))
        assertEquals(null, lookup.optionalCheckpointGlobalId(177L))
        assertEquals(null, lookup.optionalCheckpointGlobalId(null))
    }

    @Test
    fun hasCheckpointId_reflectsLookupMap() {
        val lookup = SyncIdLookup.forTest(checkpointGlobal = mapOf(177L to "cp-177"))
        assertEquals(true, lookup.hasCheckpointId(177L))
        assertEquals(false, lookup.hasCheckpointId(999L))
    }
}
