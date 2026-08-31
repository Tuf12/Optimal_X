package com.example.optimalx.data.eidos

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class NoteWriteMergeTest {

    @Test
    fun merge_emptyNote_setsInitialContent() {
        val result = NoteWriteMerge.merge(currentMarkdown = "", additionMarkdown = "# Title\n\nBody")
        assertEquals("# Title\n\nBody", result.mergedMarkdown)
        assertEquals("Note created", result.successMessage)
        assertFalse(result.appended)
    }

    @Test
    fun merge_nonEmptyNote_appendsWithBlankLineSeparator() {
        val result = NoteWriteMerge.merge(
            currentMarkdown = "Existing paragraph.",
            additionMarkdown = "New block.",
        )
        assertEquals("Existing paragraph.\n\nNew block.", result.mergedMarkdown)
        assertEquals("Content appended", result.successMessage)
        assertTrue(result.appended)
    }
}
