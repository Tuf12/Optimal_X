package com.example.optimalx.ui.editor.components

import androidx.compose.ui.text.TextRange
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class NoteMarkdownActionsTest {

    @Test
    fun toggleBold_wrapsSelection() {
        val result = NoteMarkdownActions.toggleBold("Hello world", TextRange(0, 5))
        assertEquals("**Hello** world", result.text)
        assertTrue(NoteMarkdownActions.isBold(result.text, result.selection))
    }

    @Test
    fun toggleBold_unwrapsExistingMarkers() {
        val result = NoteMarkdownActions.toggleBold("**Hello** world", TextRange(0, 9))
        assertEquals("Hello world", result.text)
        assertFalse(NoteMarkdownActions.isBold(result.text, result.selection))
    }

    @Test
    fun cycleHeading_titleThenSectionThenBody() {
        var text = "Recipe title"
        var selection = TextRange(0, 12)
        val title = NoteMarkdownActions.cycleHeading(text, selection)
        assertTrue(title.text.startsWith("# Recipe title"))
        text = title.text
        selection = title.selection
        val section = NoteMarkdownActions.cycleHeading(text, selection)
        assertTrue(section.text.startsWith("## Recipe title"))
        text = section.text
        selection = section.selection
        val sub = NoteMarkdownActions.cycleHeading(text, selection)
        assertTrue(sub.text.startsWith("### Recipe title"))
        val body = NoteMarkdownActions.cycleHeading(sub.text, sub.selection)
        assertEquals("Recipe title", body.text)
    }

    @Test
    fun toggleUnorderedList_addsAndRemovesPrefix() {
        val added = NoteMarkdownActions.toggleUnorderedList("milk\neggs", TextRange(0, 9))
        assertTrue(added.text.contains("- milk"))
        assertTrue(added.text.contains("- eggs"))
        val removed = NoteMarkdownActions.toggleUnorderedList(added.text, added.selection)
        assertEquals("milk\neggs", removed.text)
    }

    @Test
    fun applyLink_wrapsSelection() {
        val result = NoteMarkdownActions.applyLink("Visit site", TextRange(0, 10), "https://example.com")
        assertEquals("[Visit site](https://example.com)", result.text)
    }

    @Test
    fun insertMarkdown_replacesSelection() {
        val result = NoteMarkdownActions.insertMarkdown("alpha omega", TextRange(6, 11), "**mid**")
        assertEquals("alpha **mid**", result.text)
    }

    @Test
    fun headingDoesNotSwallowFollowingParagraph() {
        val source = "Peanut Butter Reeses cup\nHow to make it\nMix yogurt"
        val result = NoteMarkdownActions.applyHeading(
            source,
            TextRange(0, "Peanut Butter Reeses cup".length),
            NoteHeadingLevel.TITLE,
        )
        assertTrue(result.text.startsWith("# Peanut Butter Reeses cup\n"))
        assertTrue(result.text.contains("How to make it"))
        assertTrue(result.text.contains("Mix yogurt"))
    }
}
