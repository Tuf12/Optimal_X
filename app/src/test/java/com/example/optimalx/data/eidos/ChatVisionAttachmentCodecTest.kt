package com.example.optimalx.data.eidos

import com.example.optimalx.data.sync.SyncChatMessageRow
import kotlinx.serialization.json.Json
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class ChatVisionAttachmentCodecTest {

    private val json = Json { ignoreUnknownKeys = true }

    @Test
    fun parseAttachmentJson_readsDesktopShape() {
        val raw = """{"fileName":"shot.png","mimeType":"image/png","storedName":"abc-uuid.png"}"""
        val parsed = ChatVisionAttachmentCodec.parseAttachmentJson(raw)
        assertEquals("shot.png", parsed?.fileName)
        assertEquals("image/png", parsed?.mimeType)
        assertEquals("abc-uuid.png", parsed?.storedName)
        assertEquals("shot.png", ChatVisionAttachmentCodec.displayLabel(parsed!!))
    }

    @Test
    fun parseAttachmentJson_requiresStoredName() {
        assertNull(ChatVisionAttachmentCodec.parseAttachmentJson("""{"fileName":"shot.png"}"""))
        assertNull(ChatVisionAttachmentCodec.parseAttachmentJson(""))
        assertNull(ChatVisionAttachmentCodec.parseAttachmentJson(null))
        assertNull(ChatVisionAttachmentCodec.parseAttachmentJson("{not json"))
    }

    @Test
    fun parseAttachmentJson_fallsBackFileNameAndMime() {
        val parsed = ChatVisionAttachmentCodec.parseAttachmentJson(
            """{"storedName":"abc.png"}""",
        )
        assertEquals("abc.png", parsed?.fileName)
        assertEquals("image/png", parsed?.mimeType)
        assertEquals("Image attached", ChatVisionAttachmentCodec.displayLabel(
            ChatVisionAttachment(fileName = "  ", storedName = "abc.png"),
        ))
    }

    @Test
    fun serializeAndParse_roundTrips() {
        val original = ChatVisionAttachment(
            fileName = "photo.jpg",
            mimeType = "image/jpeg",
            storedName = "uuid.jpg",
        )
        val encoded = ChatVisionAttachmentCodec.serializeAttachment(original)
        val parsed = ChatVisionAttachmentCodec.parseAttachmentJson(encoded)
        assertEquals(original, parsed)
    }

    @Test
    fun syncChatMessageRow_deserializesDesktopImageAttachmentJson() {
        val row = json.decodeFromString(
            SyncChatMessageRow.serializer(),
            """
            {
              "globalId": "msg-1",
              "conversationGlobalId": "conv-1",
              "role": "user",
              "content": "Describe this image.",
              "imageAttachmentJson": "{\"fileName\":\"shot.png\",\"mimeType\":\"image/png\",\"storedName\":\"abc.png\"}",
              "createdAt": 1
            }
            """.trimIndent(),
        )
        val parsed = ChatVisionAttachmentCodec.parseAttachmentJson(row.imageAttachmentJson)
        assertEquals("shot.png", parsed?.fileName)
        assertEquals("abc.png", parsed?.storedName)
    }
}
