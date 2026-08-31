package com.example.optimalx.data.eidos.provider

import com.example.optimalx.data.eidos.ChatVisionAttachmentCodec
import com.example.optimalx.data.eidos.ChatVisionImageStore
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class ChatVisionUserContentTest {

    private val image = ChatVisionImageStore.Encoded(
        mimeType = "image/png",
        base64 = "abc123",
    )

    @Test
    fun userText_fallsBackToDescribePrompt() {
        assertEquals(ChatVisionAttachmentCodec.EMPTY_PROMPT, ChatVisionUserContent.userText("  "))
        assertEquals("what's in this picture?", ChatVisionUserContent.userText("what's in this picture?"))
    }

    @Test
    fun openAiInputContent_includesInputImage() {
        val parts = ChatVisionUserContent.openAiInputContent("Look", listOf(image))
        assertEquals(2, parts.size)
        assertEquals("input_text", parts[0].jsonObject["type"]?.jsonPrimitive?.content)
        assertEquals("Look", parts[0].jsonObject["text"]?.jsonPrimitive?.content)
        assertEquals("input_image", parts[1].jsonObject["type"]?.jsonPrimitive?.content)
        assertEquals(image.dataUrl, parts[1].jsonObject["image_url"]?.jsonPrimitive?.content)
    }

    @Test
    fun anthropicContent_includesBase64Source() {
        val parts = ChatVisionUserContent.anthropicContent("Look", listOf(image))
        val imagePart = parts[1].jsonObject
        assertEquals("image", imagePart["type"]?.jsonPrimitive?.content)
        val source = imagePart["source"]?.jsonObject
        assertEquals("base64", source?.get("type")?.jsonPrimitive?.content)
        assertEquals("image/png", source?.get("media_type")?.jsonPrimitive?.content)
        assertEquals("abc123", source?.get("data")?.jsonPrimitive?.content)
    }

    @Test
    fun chatCompletionsContent_includesImageUrl() {
        val parts = ChatVisionUserContent.chatCompletionsContent("Look", listOf(image))
        val imagePart = parts[1].jsonObject
        assertEquals("image_url", imagePart["type"]?.jsonPrimitive?.content)
        assertEquals(
            image.dataUrl,
            imagePart["image_url"]?.jsonObject?.get("url")?.jsonPrimitive?.content,
        )
        assertTrue(parts[0].jsonObject.containsKey("text"))
    }
}
