package com.example.optimalx.data.eidos

import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.example.optimalx.data.db.AppDatabase
import com.example.optimalx.data.eidos.agentbyte.TagHintNotifier
import com.example.optimalx.data.eidos.model.ToolExecutionResult
import com.example.optimalx.data.model.Note
import com.example.optimalx.data.model.ParentFolder
import com.example.optimalx.data.model.Subfolder
import com.example.optimalx.data.semantic.EmbeddingEngine
import com.example.optimalx.data.semantic.SemanticChunkBuilder
import com.example.optimalx.data.semantic.SemanticIndexer
import com.example.optimalx.data.semantic.SemanticObjectType
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.After
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class RoomToolExecutorSemanticRetrievalTest {

    private lateinit var db: AppDatabase
    private lateinit var executor: RoomToolExecutor
    private lateinit var semanticIndexer: SemanticIndexer
    private lateinit var chunkBuilder: SemanticChunkBuilder
    private val json = Json { ignoreUnknownKeys = true }

    @Before
    fun setup() {
        val context = ApplicationProvider.getApplicationContext<android.content.Context>()
        db = Room.inMemoryDatabaseBuilder(context, AppDatabase::class.java)
            .allowMainThreadQueries()
            .build()
        semanticIndexer = SemanticIndexer(db, EmbeddingEngine(context))
        chunkBuilder = SemanticChunkBuilder(
            noteDao = db.noteDao(),
            subfolderDao = db.subfolderDao(),
            parentFolderDao = db.parentFolderDao(),
            conversationDao = db.conversationDao(),
            chatMessageDao = db.chatMessageDao(),
        )
        executor = RoomToolExecutor(
            context = context,
            db = db,
            semanticIndexer = semanticIndexer,
            tagHintNotifier = TagHintNotifier.NoOp,
        )
    }

    @After
    fun teardown() {
        db.close()
    }

    @Test
    fun blind_note_excluded_from_search_semantic() = runBlocking {
        val parentId = db.parentFolderDao().insert(
            ParentFolder(name = "Private", sortOrder = 0, isSystemFolder = false),
        )
        val subfolderId = db.subfolderDao().insert(
            Subfolder(parentFolderId = parentId, name = "Secret", sortOrder = 0),
        )
        db.noteDao().insert(
            Note(subfolderId = subfolderId, content = "secret tile grout formula", aiBlind = true),
        )
        chunkBuilder.indexNote(semanticIndexer, subfolderId)

        val result = executor.execute("search_semantic", """{"query":"secret tile grout","limit":"10"}""")
        val content = (result as ToolExecutionResult.Success).content
        assertTrue(content == "[]" || !content.contains("chunk_text"))
    }

    @Test
    fun search_semantic_returns_chunk_text() = runBlocking {
        val parentId = db.parentFolderDao().insert(
            ParentFolder(name = "Work", sortOrder = 0, isSystemFolder = false),
        )
        val subfolderId = db.subfolderDao().insert(
            Subfolder(parentFolderId = parentId, name = "Specs", sortOrder = 0),
        )
        db.noteDao().insert(
            Note(
                subfolderId = subfolderId,
                content = "Intro filler.\n\nTARGET SECTION: unique backsplash grout color is slate gray.\n\nMore filler.",
            ),
        )
        chunkBuilder.indexNote(semanticIndexer, subfolderId)

        val result = executor.execute(
            "search_semantic",
            """{"query":"backsplash grout color","limit":"5"}""",
        )
        val arr = json.parseToJsonElement((result as ToolExecutionResult.Success).content).jsonArray
        assertTrue(arr.isNotEmpty())
        val first = arr.first().jsonObject
        assertTrue(first["chunk_text"]?.jsonPrimitive?.content?.contains("slate gray") == true)
        assertTrue(first["object_type"]?.jsonPrimitive?.content == SemanticObjectType.NOTE)
    }

    @Test
    fun read_note_large_without_query_returns_truncated_hint() = runBlocking {
        val parentId = db.parentFolderDao().insert(
            ParentFolder(name = "Work", sortOrder = 0, isSystemFolder = false),
        )
        val subfolderId = db.subfolderDao().insert(
            Subfolder(parentFolderId = parentId, name = "Big", sortOrder = 0),
        )
        db.noteDao().insert(
            Note(
                subfolderId = subfolderId,
                content = "x".repeat(3_000),
                summary = "Overview of big note.",
            ),
        )

        val result = executor.execute("read_note", """{"subfolderId":"$subfolderId"}""")
        val payload = json.parseToJsonElement((result as ToolExecutionResult.Success).content).jsonObject
        assertTrue(payload["truncated"]?.jsonPrimitive?.content == "true")
        assertTrue(payload["content"]?.jsonPrimitive?.content?.isNotBlank() == true)
        assertTrue(payload["hint"]?.jsonPrimitive?.content?.contains("search_semantic") == true)
    }
}
