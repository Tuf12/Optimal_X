package com.example.optimalx.ui.editor

import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.concurrent.atomic.AtomicInteger

class NoteEditorWorkingCopyFlusherTest {

    @Test
    fun flush_persistsImmediately() = runBlocking {
        val persisted = AtomicInteger(0)
        var lastContent = ""
        val flusher = NoteEditorWorkingCopyFlusher(
            onPersist = { content ->
                lastContent = content
                persisted.incrementAndGet()
            },
        )

        flusher.flush("hello", shouldPersist = true)
        assertEquals(1, persisted.get())
        assertEquals("hello", lastContent)
    }

    @Test
    fun flush_skipsWhenShouldPersistFalse() = runBlocking {
        val persisted = AtomicInteger(0)
        val flusher = NoteEditorWorkingCopyFlusher(
            onPersist = { persisted.incrementAndGet() },
        )

        flusher.flush("ignored", shouldPersist = false)
        assertEquals(0, persisted.get())
    }

    @Test
    fun callbacks_fireInOrderForFlush() = runBlocking {
        val events = mutableListOf<String>()
        val flusher = NoteEditorWorkingCopyFlusher(
            onSaving = { events += "saving" },
            onSaved = { events += "saved" },
            onPersist = { },
        )

        flusher.flush("x", shouldPersist = true)
        assertEquals(listOf("saving", "saved"), events)
        assertTrue(events.isNotEmpty())
    }
}
