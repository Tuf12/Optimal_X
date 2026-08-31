package com.example.optimalx.ui.components

import com.mohamedrejeb.richeditor.annotation.ExperimentalRichTextApi
import com.mohamedrejeb.richeditor.model.RichTextState
import org.junit.Assert.assertTrue
import org.junit.Test

@OptIn(ExperimentalRichTextApi::class)
class NoteParagraphPersistTest {

    @Test
    fun doubleNewlineParagraph_roundTrips() {
        val source = "First paragraph.\n\nSecond paragraph."
        val state = RichTextState()
        NoteContentCodec.loadIntoRichText(state, source)
        val persisted = NoteContentCodec.persistFromRichText(state)
        println("PERSISTED: [$persisted]")
        assertTrue("Expected paragraph break in [$persisted]", persisted.contains("First") && persisted.contains("Second"))
        assertTrue("Expected blank line or separate blocks in [$persisted]", persisted.contains("\n\n") || persisted.lines().size >= 2)
    }

    @Test
    fun singleEnter_softBreak_orNewLine() {
        val source = "Line one\nLine two"
        val state = RichTextState()
        NoteContentCodec.loadIntoRichText(state, source)
        val persisted = NoteContentCodec.persistFromRichText(state)
        println("SINGLE ENTER: [$persisted]")
        assertTrue(persisted.contains("Line one") && persisted.contains("Line two"))
    }
}
