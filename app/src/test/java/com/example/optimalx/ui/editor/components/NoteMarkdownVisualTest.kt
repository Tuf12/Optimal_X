package com.example.optimalx.ui.editor.components

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class NoteMarkdownVisualTest {

    @Test
    fun headingHidesHashPrefix() {
        val transformed = NoteMarkdownVisual.transform("# Title")
        assertEquals("Title", transformed.text.text)
        assertFalse(transformed.text.text.contains("#"))
        assertEquals(2, transformed.offsetMapping.transformedToOriginal(0))
        assertEquals(0, transformed.offsetMapping.originalToTransformed(0))
    }

    @Test
    fun boldHidesMarkers() {
        val transformed = NoteMarkdownVisual.transform("Hello **world**")
        assertEquals("Hello world", transformed.text.text)
        assertFalse(transformed.text.text.contains("**"))
        val worldStart = transformed.text.text.indexOf("world")
        assertEquals(8, transformed.offsetMapping.transformedToOriginal(worldStart))
    }

    @Test
    fun bulletHidesDash() {
        val transformed = NoteMarkdownVisual.transform("- milk")
        assertEquals("• milk", transformed.text.text)
        assertFalse(transformed.text.text.startsWith("-"))
        assertEquals(2, transformed.offsetMapping.transformedToOriginal(2))
    }

    @Test
    fun fenceKeepsCodeHidesTicks() {
        val source = "```\ngit status\n```"
        val transformed = NoteMarkdownVisual.transform(source)
        assertTrue(transformed.text.text.contains("git status"))
        assertFalse(transformed.text.text.contains("```"))
    }

    @Test
    fun eidosNoteRoundTripOffsetsStayInSource() {
        val source = "# Plan\n\nMix **yogurt**\n"
        val transformed = NoteMarkdownVisual.transform(source)
        assertEquals("Plan\n\nMix yogurt\n", transformed.text.text)
        val yogurt = transformed.text.text.indexOf("yogurt")
        val sourceIdx = transformed.offsetMapping.transformedToOriginal(yogurt)
        assertTrue(source.substring(sourceIdx).startsWith("yogurt"))
    }
}
