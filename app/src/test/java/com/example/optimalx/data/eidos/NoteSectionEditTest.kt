package com.example.optimalx.data.eidos

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class NoteSectionEditTest {

    @Test
    fun lineMode_splicesRange() {
        val text = "# Title\n\nOld body.\n\nTail."
        val result = NoteSectionEdit.buildProposedContent(
            current = text,
            newContent = "New body.",
            startLine = 3,
            endLine = 3,
            oldString = null,
            expectedContent = null,
        )
        assertTrue(result is NoteSectionEdit.Result.Ok)
        val ok = result as NoteSectionEdit.Result.Ok
        assertTrue(ok.content.contains("New body."))
        assertTrue(!ok.content.contains("Old body."))
    }

    @Test
    fun stringMode_requiresUniqueMatch() {
        val ambiguous = NoteSectionEdit.buildProposedContent(
            current = "repeat\nrepeat\nrepeat\n",
            newContent = "once",
            startLine = null,
            endLine = null,
            oldString = "repeat",
            expectedContent = null,
        )
        assertTrue(ambiguous is NoteSectionEdit.Result.Error)
        assertTrue((ambiguous as NoteSectionEdit.Result.Error).message.contains("occurrences"))
    }

    @Test
    fun expectedContent_mismatchFails() {
        val result = NoteSectionEdit.buildProposedContent(
            current = "line one\nline two",
            newContent = "changed",
            startLine = 2,
            endLine = 2,
            oldString = null,
            expectedContent = "wrong",
        )
        assertTrue(result is NoteSectionEdit.Result.Error)
        assertTrue((result as NoteSectionEdit.Result.Error).message.contains("expectedContent"))
    }
}
