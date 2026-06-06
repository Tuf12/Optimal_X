package com.example.optimalx.data.eidos

import org.json.JSONArray
import org.json.JSONObject

data class ContentSummaryChunk(
    val anchor: String,
    val text: String,
)

object ContentSummaryChunksCodec {

    fun encode(chunks: List<ContentSummaryChunk>): String? {
        if (chunks.isEmpty()) return null
        val arr = JSONArray()
        chunks.forEach { chunk ->
            arr.put(
                JSONObject()
                    .put("anchor", chunk.anchor)
                    .put("text", chunk.text),
            )
        }
        return arr.toString()
    }

    fun decode(raw: String?): List<ContentSummaryChunk> {
        if (raw.isNullOrBlank()) return emptyList()
        return runCatching {
            val arr = JSONArray(raw)
            buildList(arr.length()) {
                for (i in 0 until arr.length()) {
                    val obj = arr.getJSONObject(i)
                    add(
                        ContentSummaryChunk(
                            anchor = obj.optString("anchor", "Section"),
                            text = obj.optString("text", ""),
                        ),
                    )
                }
            }.filter { it.text.isNotBlank() }
        }.getOrDefault(emptyList())
    }
}
