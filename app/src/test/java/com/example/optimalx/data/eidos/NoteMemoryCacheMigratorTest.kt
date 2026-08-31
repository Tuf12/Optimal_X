package com.example.optimalx.data.eidos

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class NoteMemoryCacheMigratorTest {

    @Test
    fun decodeMemoryCacheMap_parses_subfolder_entries() {
        val map = NoteMemoryCacheMigrator.decodeMemoryCacheMap(
            """{"42":"User prefers short bullets.","99":"Formal tone."}""",
        )
        assertEquals(2, map.size)
        assertEquals("User prefers short bullets.", map[42L])
        assertEquals("Formal tone.", map[99L])
    }

    @Test
    fun decodeMemoryCacheMap_ignores_invalid_json() {
        assertTrue(NoteMemoryCacheMigrator.decodeMemoryCacheMap("not json").isEmpty())
        assertTrue(NoteMemoryCacheMigrator.decodeMemoryCacheMap("").isEmpty())
    }
}
