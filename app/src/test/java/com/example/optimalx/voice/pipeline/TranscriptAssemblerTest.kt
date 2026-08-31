package com.example.optimalx.voice.pipeline

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class TranscriptAssemblerTest {

    @Test
    fun partial_does_not_commit_until_final() {
        val a = TranscriptAssembler()
        a.setPartial("hello wor")
        assertEquals("hello wor", a.displayText())
        assertEquals("", a.committedText)
    }

    @Test
    fun final_appends_to_committed_and_clears_partial() {
        val a = TranscriptAssembler()
        a.setPartial("hello wor")
        a.commitFinal("hello world")
        assertEquals("hello world", a.displayText())
        assertEquals("hello world", a.committedText)
        assertEquals("", a.partialText)
    }

    @Test
    fun base_text_seeds_committed_buffer() {
        val a = TranscriptAssembler()
        a.setBase("draft so far")
        a.setPartial("more words")
        assertEquals("draft so far more words", a.displayText())
        a.commitFinal("more words here")
        assertEquals("draft so far more words here", a.displayText())
    }

    @Test
    fun takeAndReset_flushes_partial_and_clears_state() {
        val a = TranscriptAssembler()
        a.setBase("one")
        a.commitFinal("two")
        a.setPartial("three")
        val out = a.takeAndReset()
        assertEquals("one two three", out)
        assertEquals("", a.displayText())
        assertEquals("", a.committedText)
        assertEquals("", a.partialText)
    }

    @Test
    fun empty_final_does_not_grow_committed() {
        val a = TranscriptAssembler()
        a.commitFinal("hello")
        a.commitFinal("   ")
        assertEquals("hello", a.committedText)
    }

    @Test
    fun snapshot_matches_display_text_without_reset() {
        val a = TranscriptAssembler()
        a.setBase("start")
        a.setPartial("mid")
        assertEquals(a.displayText(), a.snapshot())
        assertTrue(a.committedText == "start")
    }
}
