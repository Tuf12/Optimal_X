package com.example.optimalx.data.eidos

import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.example.optimalx.data.eidos.EidosIndexFeature
import com.example.optimalx.data.db.AppDatabase
import com.example.optimalx.data.eidos.agentbyte.TagHintNotifier
import com.example.optimalx.data.eidos.model.ToolExecutionResult
import com.example.optimalx.data.model.TagHintLine
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class RoomToolExecutorTagHintPhase2Test {

    private lateinit var db: AppDatabase
    private lateinit var executor: RoomToolExecutor
    private lateinit var notifier: RecordingNotifier
    private val json = Json { ignoreUnknownKeys = true }

    @Before
    fun setup() {
        EidosIndexFeature.enabledOverride = true
        val context = ApplicationProvider.getApplicationContext<android.content.Context>()
        db = Room.inMemoryDatabaseBuilder(context, AppDatabase::class.java)
            .allowMainThreadQueries()
            .build()
        notifier = RecordingNotifier()
        executor = RoomToolExecutor(context = context, db = db, tagHintNotifier = notifier)
    }

    @After
    fun teardown() {
        EidosIndexFeature.enabledOverride = null
        db.close()
    }

    @Test
    fun upsert_read_remove_roundtrip_and_filters() = runBlocking {
        val upsert = executor.execute(
            "upsert_tag_hint",
            """{"ref":"chat:general:221","tag":"agent training","hint":"agent training path"}""",
        )
        assertTrue(upsert is ToolExecutionResult.Success)

        val readAll = executor.execute("read_tag_hints", """{"limit":"10"}""")
        val readAllContent = (readAll as ToolExecutionResult.Success).content
        val allPayload = json.parseToJsonElement(readAllContent).jsonObject
        assertEquals("tag_hints_read_v2", allPayload["schema"]?.jsonPrimitive?.content)
        val allRows = allPayload["items"]?.jsonArray ?: error("items missing")
        assertEquals(1, allRows.size)
        assertEquals("chat:general:221", allRows.first().jsonObject["ref"]?.jsonPrimitive?.content)

        val readByScope = executor.execute("read_tag_hints", """{"scope":"chats","limit":"10"}""")
        val scopedRows = json.parseToJsonElement((readByScope as ToolExecutionResult.Success).content)
            .jsonObject["items"]?.jsonArray ?: error("items missing")
        assertEquals(1, scopedRows.size)

        val readByRef = executor.execute("read_tag_hints", """{"ref":"chat:general:221"}""")
        val refRows = json.parseToJsonElement((readByRef as ToolExecutionResult.Success).content)
            .jsonObject["items"]?.jsonArray ?: error("items missing")
        assertEquals(1, refRows.size)

        val removed = executor.execute("remove_tag_hint", """{"ref":"chat:general:221"}""")
        assertTrue(removed is ToolExecutionResult.Success)

        val after = executor.execute("read_tag_hints", """{"limit":"10"}""")
        val afterRows = json.parseToJsonElement((after as ToolExecutionResult.Success).content)
            .jsonObject["items"]?.jsonArray ?: error("items missing")
        assertTrue(afterRows.isEmpty())
    }

    @Test
    fun read_tag_hints_returns_v2_schema_with_fixed_keys_and_nullables() = runBlocking {
        executor.execute(
            "upsert_tag_hint",
            """{"ref":"chat:general:99","tag":"schema","hint":"schema check"}""",
        )

        val payload = json.parseToJsonElement(
            (executor.execute("read_tag_hints", """{"ref":"chat:general:99","limit":"1"}""")
                as ToolExecutionResult.Success).content
        ).jsonObject

        assertEquals("tag_hints_read_v2", payload["schema"]?.jsonPrimitive?.content)
        assertEquals("1", payload["count"]?.jsonPrimitive?.content)
        assertEquals("1", payload["limit"]?.jsonPrimitive?.content)

        val item = payload["items"]?.jsonArray?.firstOrNull()?.jsonObject ?: error("missing first item")
        val expectedKeys = setOf(
            "ref", "objectType", "scopeType", "scopeId", "parentRef", "rootBranch",
            "tag", "hint", "objectName", "parentFolderName", "subfolderName",
            "date", "dateKey", "createdAt", "updatedAt", "line",
        )
        assertEquals(expectedKeys, item.keys)
        assertEquals("chat:general:99", item["ref"]?.jsonPrimitive?.content)
        assertEquals("chat", item["objectType"]?.jsonPrimitive?.content)
        assertEquals("general", item["scopeType"]?.jsonPrimitive?.content)
        assertEquals(JsonNull, item["scopeId"])
        assertEquals(JsonNull, item["parentRef"])
        assertTrue(item["line"]?.jsonPrimitive?.content?.contains("ref=chat:general:99") == true)
    }

    @Test
    fun read_tag_hints_matches_v2_golden_payload_shape() = runBlocking {
        val fixedDate = 1735689600000L // 2025-01-01
        val fixedUpdated = 1735689605000L
        db.tagHintLineDao().upsertByRef(
            TagHintLine(
                ref = "chat:general:221",
                objectType = "chat",
                scopeType = "general",
                scopeId = null,
                parentRef = null,
                rootBranch = "chats",
                tag = "agent training",
                hint = "agent training path",
                objectName = "Training chat",
                parentFolderName = "Eidos Chats",
                subfolderName = null,
                date = fixedDate,
                createdAt = fixedDate,
                updatedAt = fixedUpdated,
            )
        )

        val actual = json.parseToJsonElement(
            (executor.execute("read_tag_hints", """{"limit":"10"}""") as ToolExecutionResult.Success).content
        )
        val payload = actual.jsonObject
        assertEquals("tag_hints_read_v2", payload["schema"]?.jsonPrimitive?.content)
        assertEquals("1", payload["count"]?.jsonPrimitive?.content)
        assertEquals("10", payload["limit"]?.jsonPrimitive?.content)
        val item = payload["items"]?.jsonArray?.firstOrNull()?.jsonObject ?: error("missing item")
        assertEquals("chat:general:221", item["ref"]?.jsonPrimitive?.content)
        assertEquals("chat", item["objectType"]?.jsonPrimitive?.content)
        assertEquals("Training chat", item["objectName"]?.jsonPrimitive?.content)
        assertEquals("Eidos Chats", item["parentFolderName"]?.jsonPrimitive?.content)
    }

    @Test
    fun upsert_preserves_single_row_for_same_ref() = runBlocking {
        executor.execute(
            "upsert_tag_hint",
            """{"ref":"note:482","tag":"tile bid","hint":"tile bid numbers"}""",
        )
        executor.execute(
            "upsert_tag_hint",
            """{"ref":"note:482","tag":"dock build","hint":"dock build plan"}""",
        )
        val rows = db.tagHintLineDao().getAll(limit = 100, offset = 0)
        assertEquals(1, rows.size)
        assertEquals("dock build", rows.first().tag)
        assertEquals("dock build plan", rows.first().hint)
    }

    @Test
    fun notify_user_calls_notifier() = runBlocking {
        val notify = executor.execute(
            "notify_user",
            """{"title":"Tag updated","message":"Updated ref","ref":"note:482"}""",
        ) as ToolExecutionResult.Success
        val notifyObj = json.parseToJsonElement(notify.content).jsonObject
        assertEquals("notified", notifyObj["status"]?.jsonPrimitive?.content)
        assertEquals(1, notifier.calls.size)
        assertEquals("Tag updated", notifier.calls.first().title)
        assertEquals("Updated ref", notifier.calls.first().message)
        assertEquals("note:482", notifier.calls.first().ref)
    }

    @Test
    fun notify_user_requires_title_and_message() = runBlocking {
        val missingTitle = executor.execute("notify_user", """{"message":"x"}""")
        val missingMessage = executor.execute("notify_user", """{"title":"x"}""")
        assertTrue(missingTitle is ToolExecutionResult.Failure)
        assertTrue((missingTitle as ToolExecutionResult.Failure).message.contains("title is required"))
        assertTrue(missingMessage is ToolExecutionResult.Failure)
        assertTrue((missingMessage as ToolExecutionResult.Failure).message.contains("message is required"))
        assertTrue(notifier.calls.isEmpty())
    }

    @Test
    fun legacy_tool_names_are_rejected() = runBlocking {
        val oldRead = executor.execute("read_tag_hint_index", "{}")
        val oldAppend = executor.execute("append_tag_hint_lines", """{"lines":"x"}""")
        val chess = executor.execute("chess_taxonomy", "{}")
        assertTrue(oldRead is ToolExecutionResult.Failure)
        assertTrue((oldRead as ToolExecutionResult.Failure).message.contains("Unknown tool"))
        assertTrue(oldAppend is ToolExecutionResult.Failure)
        assertTrue((oldAppend as ToolExecutionResult.Failure).message.contains("Unknown tool"))
        assertTrue(chess is ToolExecutionResult.Failure)
    }

    private data class NotifyCall(
        val title: String,
        val message: String,
        val ref: String?,
    )

    private class RecordingNotifier : TagHintNotifier {
        val calls = mutableListOf<NotifyCall>()
        override fun notify(title: String, message: String, ref: String?) {
            calls += NotifyCall(title, message, ref)
        }
    }
}
