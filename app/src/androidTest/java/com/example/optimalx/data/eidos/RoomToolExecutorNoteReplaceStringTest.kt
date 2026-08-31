package com.example.optimalx.data.eidos

import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.example.optimalx.data.db.AppDatabase
import com.example.optimalx.data.eidos.model.ToolExecutionResult
import com.example.optimalx.data.model.Note
import com.example.optimalx.data.model.ParentFolder
import com.example.optimalx.data.model.Subfolder
import com.example.optimalx.data.revision.AcceptResult
import com.example.optimalx.data.revision.CheckpointRepository
import com.example.optimalx.data.revision.DirectWriteApplier
import com.example.optimalx.data.revision.NoteIndexer
import com.example.optimalx.data.revision.PendingChangeService
import com.example.optimalx.data.revision.SCOPE_SUBFOLDER
import com.example.optimalx.data.revision.WorkshopFileIndexer
import com.example.optimalx.data.semantic.EmbeddingEngine
import com.example.optimalx.data.semantic.SemanticChunkBuilder
import com.example.optimalx.data.semantic.SemanticIndexer
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File

@RunWith(AndroidJUnit4::class)
class RoomToolExecutorNoteReplaceStringTest {

    private lateinit var db: AppDatabase
    private lateinit var executor: RoomToolExecutor
    private lateinit var pendingService: PendingChangeService
    private lateinit var semanticIndexer: SemanticIndexer
    private lateinit var chunkBuilder: SemanticChunkBuilder
    private val json = Json { ignoreUnknownKeys = true }
    private var subfolderId: Long = 0L

    @Before
    fun setup() {
        val context = ApplicationProvider.getApplicationContext<android.content.Context>()
        db = Room.inMemoryDatabaseBuilder(context, AppDatabase::class.java)
            .allowMainThreadQueries()
            .build()
        val semanticIndexer = SemanticIndexer(db, EmbeddingEngine(context))
        this.semanticIndexer = semanticIndexer
        val chunkBuilder = SemanticChunkBuilder(
            noteDao = db.noteDao(),
            subfolderDao = db.subfolderDao(),
            parentFolderDao = db.parentFolderDao(),
            conversationDao = db.conversationDao(),
            chatMessageDao = db.chatMessageDao(),
        )
        this.chunkBuilder = chunkBuilder
        val checkpointRepository = CheckpointRepository(
            db.contentCheckpointDao(),
            db.contentPatchDao(),
        )
        val directWriteApplier = DirectWriteApplier(
            fileReferenceDao = db.fileReferenceDao(),
            checkpointRepository = checkpointRepository,
            indexer = WorkshopFileIndexer.NoOp,
            noteDao = db.noteDao(),
            noteIndexer = NoteIndexer { subfolderId ->
                chunkBuilder.indexNote(semanticIndexer, subfolderId)
            },
            workshopFilePath = { _, name -> File(context.cacheDir, name) },
        )
        pendingService = PendingChangeService(
            pendingDao = db.pendingChangeDao(),
            fileReferenceDao = db.fileReferenceDao(),
            noteDao = db.noteDao(),
            checkpointRepository = checkpointRepository,
            directWriteApplier = directWriteApplier,
        )
        executor = RoomToolExecutor(
            context = context,
            db = db,
            semanticIndexer = semanticIndexer,
        )
        runBlocking {
            val parentId = db.parentFolderDao().insert(
                ParentFolder(name = "Notes", sortOrder = 0, isSystemFolder = false),
            )
            subfolderId = db.subfolderDao().insert(
                Subfolder(parentFolderId = parentId, name = "Zebras", sortOrder = 0),
            )
            db.noteDao().insert(
                Note(
                    subfolderId = subfolderId,
                    content = "# Zebras\n\nStripes are **bold**.\n\nThey eat grass.\n",
                ),
            )
            chunkBuilder.indexNote(semanticIndexer, subfolderId)
        }
    }

    @After
    fun teardown() {
        db.close()
    }

    @Test
    fun editNoteSection_lineMode_queuesUntilAccept() = runBlocking {
        val before = db.noteDao().getBySubfolderOnce(subfolderId)!!.content
        val result = executor.execute(
            "edit_note_section",
            """{"subfolderId":"$subfolderId","startLine":"3","endLine":"3","newContent":"Stripes are *italic*."}""",
        )

        assertTrue(result is ToolExecutionResult.Success)
        val success = result as ToolExecutionResult.Success
        assertTrue(success.content.contains("queued for review"))
        assertEquals(before, db.noteDao().getBySubfolderOnce(subfolderId)!!.content)

        val set = db.pendingChangeDao().findOpenSetForScope(SCOPE_SUBFOLDER, subfolderId)
        assertNotNull(set)
        val items = db.pendingChangeDao().listItems(set!!.id)
        assertEquals(1, items.size)
        assertTrue(pendingService.accept(items[0].id) is AcceptResult.Applied)

        val updated = db.noteDao().getBySubfolderOnce(subfolderId)!!.content
        assertTrue(updated.contains("Stripes are *italic*."))
        assertTrue(!updated.contains("**bold**"))
    }

    @Test
    fun readNoteSection_returnsLineRange() = runBlocking {
        val result = executor.execute(
            "read_note_section",
            """{"subfolderId":"$subfolderId","startLine":"3","endLine":"3"}""",
        )
        assertTrue(result is ToolExecutionResult.Success)
        val payload = json.parseToJsonElement((result as ToolExecutionResult.Success).content).jsonObject
        assertTrue(payload["content"]!!.jsonPrimitive.content.contains("Stripes"))
    }

    @Test
    fun replaceString_success_queuesUntilAccept() = runBlocking {
        val before = db.noteDao().getBySubfolderOnce(subfolderId)!!.content
        val result = executor.execute(
            "edit_note_section",
            """{"subfolderId":"$subfolderId","oldString":"Stripes are **bold**.","newContent":"Stripes are *italic*."}""",
        )

        assertTrue(result is ToolExecutionResult.Success)
        val success = result as ToolExecutionResult.Success
        assertTrue(success.content.contains("queued for review"))
        assertEquals(before, db.noteDao().getBySubfolderOnce(subfolderId)!!.content)

        val set = db.pendingChangeDao().findOpenSetForScope(SCOPE_SUBFOLDER, subfolderId)
        assertNotNull(set)
        val items = db.pendingChangeDao().listItems(set!!.id)
        assertEquals(1, items.size)
        assertTrue(pendingService.accept(items[0].id) is AcceptResult.Applied)

        val updated = db.noteDao().getBySubfolderOnce(subfolderId)!!.content
        assertTrue(updated.contains("Stripes are *italic*."))
        assertTrue(!updated.contains("**bold**"))
    }

    @Test
    fun replaceString_oldStringFromSemanticChunkText_succeeds() = runBlocking {
        val searchResult = executor.execute(
            "search_semantic",
            """{"query":"zebra stripes bold","limit":"5"}""",
        )
        val hits = json.parseToJsonElement((searchResult as ToolExecutionResult.Success).content).jsonArray
        assertTrue(hits.isNotEmpty())
        val chunkText = hits.first().jsonObject["chunk_text"]!!.jsonPrimitive.content
        assertTrue(chunkText.contains("Stripes are **bold**."))

        val result = executor.execute(
            "edit_note_section",
            buildJsonObject {
                put("subfolderId", subfolderId.toString())
                put("oldString", chunkText)
                put("newContent", "Stripes are *italic*.")
            }.toString(),
        )
        assertTrue(result is ToolExecutionResult.Success)

        val set = db.pendingChangeDao().findOpenSetForScope(SCOPE_SUBFOLDER, subfolderId)
        assertNotNull(set)
        val items = db.pendingChangeDao().listItems(set!!.id)
        assertEquals(1, items.size)
        assertTrue(pendingService.accept(items[0].id) is AcceptResult.Applied)

        val updated = db.noteDao().getBySubfolderOnce(subfolderId)!!.content
        assertTrue(updated.contains("Stripes are *italic*."))
        assertTrue(!updated.contains("**bold**"))
    }

    @Test
    fun replaceString_oldStringNotFound_returnsFailureWithSnippet() = runBlocking {
        val result = executor.execute(
            "edit_note_section",
            """{"subfolderId":"$subfolderId","oldString":"NOT_PRESENT","newContent":"REPL"}""",
        )

        assertTrue(result is ToolExecutionResult.Failure)
        val failure = result as ToolExecutionResult.Failure
        assertTrue(failure.message.contains("not found"))
        assertTrue(failure.message.contains("Current note (lines"))
    }

    @Test
    fun replaceString_ambiguousMatch_returnsFailureWithCount() = runBlocking {
        db.noteDao().getBySubfolderOnce(subfolderId)?.let { note ->
            db.noteDao().update(note.copy(content = "repeat\nrepeat\nrepeat\n"))
        }

        val result = executor.execute(
            "edit_note_section",
            """{"subfolderId":"$subfolderId","oldString":"repeat","newContent":"once"}""",
        )

        assertTrue(result is ToolExecutionResult.Failure)
        val failure = result as ToolExecutionResult.Failure
        assertTrue(failure.message.contains("occurrences"))
        assertTrue(failure.message.contains("must be unique"))
    }

    @Test
    fun writeNote_emptyNote_storesMarkdown() = runBlocking {
        db.noteDao().getBySubfolderOnce(subfolderId)?.let { note ->
            db.noteDao().update(note.copy(content = ""))
        }

        val result = executor.execute(
            "write_note",
            """{"subfolderId":"$subfolderId","content":"<p>Hello <strong>world</strong></p>"}""",
        )

        assertTrue(result is ToolExecutionResult.Success)
        val success = result as ToolExecutionResult.Success
        assertEquals("Note created", success.content)
        val stored = db.noteDao().getBySubfolderOnce(subfolderId)!!.content
        assertTrue(stored.contains("Hello"))
        assertTrue(stored.contains("world"))
        assertTrue(!stored.contains("<p"))
    }

    @Test
    fun writeNote_nonEmptyNote_queuesAppendUntilAccept() = runBlocking {
        val before = db.noteDao().getBySubfolderOnce(subfolderId)!!.content
        val result = executor.execute(
            "write_note",
            """{"subfolderId":"$subfolderId","content":"Added section."}""",
        )

        assertTrue(result is ToolExecutionResult.Success)
        val success = result as ToolExecutionResult.Success
        assertTrue(success.content.contains("queued for review"))
        assertEquals(before, db.noteDao().getBySubfolderOnce(subfolderId)!!.content)

        val set = db.pendingChangeDao().findOpenSetForScope(SCOPE_SUBFOLDER, subfolderId)
        assertNotNull(set)
        val items = db.pendingChangeDao().listItems(set!!.id)
        assertTrue(pendingService.accept(items[0].id) is AcceptResult.Applied)

        val stored = db.noteDao().getBySubfolderOnce(subfolderId)!!.content
        assertTrue(stored.contains("Stripes are **bold**."))
        assertTrue(stored.contains("Added section."))
    }
}
