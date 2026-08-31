package com.example.optimalx.data.eidos

import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.example.optimalx.data.db.AppDatabase
import com.example.optimalx.data.eidos.model.ToolExecutionResult
import com.example.optimalx.data.model.Note
import com.example.optimalx.data.model.ParentFolder
import com.example.optimalx.data.model.Subfolder
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class RoomToolExecutorWriteNoteSummaryTest {

    private lateinit var db: AppDatabase
    private lateinit var executor: RoomToolExecutor
    private var subfolderId: Long = 0L

    @Before
    fun setup() {
        val context = ApplicationProvider.getApplicationContext<android.content.Context>()
        db = Room.inMemoryDatabaseBuilder(context, AppDatabase::class.java)
            .allowMainThreadQueries()
            .build()
        executor = RoomToolExecutor(context = context, db = db)
        runBlocking {
            val parentId = db.parentFolderDao().insert(
                ParentFolder(name = "Notes", sortOrder = 0, isSystemFolder = false),
            )
            subfolderId = db.subfolderDao().insert(
                Subfolder(parentFolderId = parentId, name = "Project", sortOrder = 0),
            )
        }
    }

    @After
    fun teardown() {
        db.close()
    }

    private suspend fun insertNote(summary: String? = null): Note {
        val note = Note(
            subfolderId = subfolderId,
            content = "# Project\n\nBody text.",
            summary = summary,
        )
        db.noteDao().insert(note)
        return note
    }

    private fun contentDigest(): String = "Auto digest of older sections."

    private fun summaryWithContent(memory: List<String> = emptyList()): String =
        NoteSummaryCodec.format(memory, contentDigest())!!

    @Test
    fun append_adds_memory_bullet_and_preserves_content() = runBlocking {
        insertNote(summaryWithContent())
        val before = db.noteDao().getBySubfolderOnce(subfolderId)!!
        assertEquals(null, before.summaryUpdatedAt)

        val result = executor.execute(
            "write_note_summary",
            """{"subfolderId":"$subfolderId","mode":"append","item":"User prefers bullet lists."}""",
        )

        assertTrue(result is ToolExecutionResult.Success)
        val updated = db.noteDao().getBySubfolderOnce(subfolderId)!!
        val parsed = NoteSummaryCodec.parse(updated.summary)
        assertEquals(listOf("User prefers bullet lists."), parsed.memoryBullets)
        assertEquals(contentDigest(), parsed.contentDigest)
        assertNotNull(updated.summaryUpdatedAt)
    }

    @Test
    fun append_rejects_duplicate_bullet() = runBlocking {
        insertNote(summaryWithContent(listOf("Existing fact.")))
        val result = executor.execute(
            "write_note_summary",
            """{"subfolderId":"$subfolderId","mode":"append","item":"Existing fact."}""",
        )
        assertTrue(result is ToolExecutionResult.Failure)
        assertTrue((result as ToolExecutionResult.Failure).message.contains("Duplicate"))
    }

    @Test
    fun replace_updates_matching_bullet() = runBlocking {
        insertNote(summaryWithContent(listOf("Alpha", "Beta")))
        val result = executor.execute(
            "write_note_summary",
            """{"subfolderId":"$subfolderId","mode":"replace","match":"Beta","item":"Bravo"}""",
        )
        assertTrue(result is ToolExecutionResult.Success)
        val parsed = NoteSummaryCodec.parse(db.noteDao().getBySubfolderOnce(subfolderId)!!.summary)
        assertEquals(listOf("Alpha", "Bravo"), parsed.memoryBullets)
        assertEquals(contentDigest(), parsed.contentDigest)
    }

    @Test
    fun remove_deletes_matching_bullet() = runBlocking {
        insertNote(summaryWithContent(listOf("Keep", "Remove me")))
        val result = executor.execute(
            "write_note_summary",
            """{"subfolderId":"$subfolderId","mode":"remove","match":"Remove"}""",
        )
        assertTrue(result is ToolExecutionResult.Success)
        val parsed = NoteSummaryCodec.parse(db.noteDao().getBySubfolderOnce(subfolderId)!!.summary)
        assertEquals(listOf("Keep"), parsed.memoryBullets)
        assertEquals(contentDigest(), parsed.contentDigest)
    }

    @Test
    fun set_replaces_entire_memory_section() = runBlocking {
        insertNote(summaryWithContent(listOf("Old one", "Old two")))
        val result = executor.execute(
            "write_note_summary",
            """{"subfolderId":"$subfolderId","mode":"set","item":"First fact.\n- Second fact."}""",
        )
        assertTrue(result is ToolExecutionResult.Success)
        val parsed = NoteSummaryCodec.parse(db.noteDao().getBySubfolderOnce(subfolderId)!!.summary)
        assertEquals(listOf("First fact.", "Second fact."), parsed.memoryBullets)
        assertEquals(contentDigest(), parsed.contentDigest)
    }

    @Test
    fun append_enforces_bullet_cap() = runBlocking {
        val bullets = (1..NoteSummaryPolicy.MAX_MEMORY_BULLETS).map { "Bullet $it" }
        insertNote(summaryWithContent(bullets))
        val result = executor.execute(
            "write_note_summary",
            """{"subfolderId":"$subfolderId","mode":"append","item":"One too many."}""",
        )
        assertTrue(result is ToolExecutionResult.Failure)
        assertTrue((result as ToolExecutionResult.Failure).message.contains("limit"))
    }

    @Test
    fun blind_note_is_rejected() = runBlocking {
        db.noteDao().insert(
            Note(subfolderId = subfolderId, content = "x", aiBlind = true),
        )
        val result = executor.execute(
            "write_note_summary",
            """{"subfolderId":"$subfolderId","mode":"append","item":"Should fail."}""",
        )
        assertTrue(result is ToolExecutionResult.Failure)
        assertTrue((result as ToolExecutionResult.Failure).message.contains("blind"))
    }
}
