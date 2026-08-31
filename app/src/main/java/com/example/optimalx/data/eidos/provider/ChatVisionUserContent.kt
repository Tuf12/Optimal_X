package com.example.optimalx.data.eidos.provider

import com.example.optimalx.data.eidos.ChatVisionAttachmentCodec
import com.example.optimalx.data.eidos.ChatVisionImageStore
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import java.io.File

/** Multimodal user-content parts for this send only (not history). */
object ChatVisionUserContent {

    fun encodePaths(paths: List<String>): List<ChatVisionImageStore.Encoded> {
        if (paths.isEmpty()) return emptyList()
        return paths.mapNotNull { path ->
            ChatVisionImageStore.encodeForProvider(File(path))
        }
    }

    fun userText(text: String): String =
        text.trim().ifBlank { ChatVisionAttachmentCodec.EMPTY_PROMPT }

    fun openAiInputContent(text: String, images: List<ChatVisionImageStore.Encoded>): JsonArray =
        buildJsonArray {
            add(
                buildJsonObject {
                    put("type", JsonPrimitive("input_text"))
                    put("text", JsonPrimitive(userText(text)))
                },
            )
            images.forEach { image ->
                add(
                    buildJsonObject {
                        put("type", JsonPrimitive("input_image"))
                        put("image_url", JsonPrimitive(image.dataUrl))
                    },
                )
            }
        }

    fun anthropicContent(text: String, images: List<ChatVisionImageStore.Encoded>): JsonArray =
        buildJsonArray {
            add(
                buildJsonObject {
                    put("type", JsonPrimitive("text"))
                    put("text", JsonPrimitive(userText(text)))
                },
            )
            images.forEach { image ->
                add(
                    buildJsonObject {
                        put("type", JsonPrimitive("image"))
                        put(
                            "source",
                            buildJsonObject {
                                put("type", JsonPrimitive("base64"))
                                put("media_type", JsonPrimitive(image.mimeType))
                                put("data", JsonPrimitive(image.base64))
                            },
                        )
                    },
                )
            }
        }

    fun chatCompletionsContent(text: String, images: List<ChatVisionImageStore.Encoded>): JsonArray =
        buildJsonArray {
            add(
                buildJsonObject {
                    put("type", JsonPrimitive("text"))
                    put("text", JsonPrimitive(userText(text)))
                },
            )
            images.forEach { image ->
                add(chatCompletionsImagePart(image))
            }
        }

    fun chatCompletionsImagePart(image: ChatVisionImageStore.Encoded): JsonObject = buildJsonObject {
        put("type", JsonPrimitive("image_url"))
        put(
            "image_url",
            buildJsonObject {
                put("url", JsonPrimitive(image.dataUrl))
            },
        )
    }
}
