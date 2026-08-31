package com.example.optimalx.data.eidos

import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json

@Serializable
data class ContentSummaryChunk(
    val anchor: String,
    val text: String,
)

object ContentSummaryChunksCodec {

    private val json = Json { ignoreUnknownKeys = true }

    fun encode(chunks: List<ContentSummaryChunk>): String? {
        if (chunks.isEmpty()) return null
        return json.encodeToString(chunks)
    }

    fun decode(raw: String?): List<ContentSummaryChunk> {
        if (raw.isNullOrBlank()) return emptyList()
        return runCatching {
            json.decodeFromString<List<ContentSummaryChunk>>(raw)
        }.getOrDefault(emptyList()).filter { it.text.isNotBlank() }
    }
}
