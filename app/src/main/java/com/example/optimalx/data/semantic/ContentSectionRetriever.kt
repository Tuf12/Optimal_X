package com.example.optimalx.data.semantic

import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.addJsonObject
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

enum class SegmentMode {
    NOTE,
    FILE,
}

data class ScopedReadResult(
    val json: String,
)

class ContentSectionRetriever(
    private val embeddingEngine: EmbeddingEngine,
) {

    /** Full-file read envelope for `workshop_read_file` / `read_file` parser consistency. */
    fun fullFileJson(fullText: String): ScopedReadResult {
        val totalLines = fullText.lines().size
        return ScopedReadResult(
            buildJsonObject {
                put("content", JsonPrimitive(fullText))
                put("truncated", JsonPrimitive(false))
                put("totalLines", JsonPrimitive(totalLines))
            }.toString(),
        )
    }

    fun retrieveByQuery(
        fullText: String,
        query: String,
        mode: SegmentMode,
        maxSegments: Int = 3,
        maxTotalChars: Int = 4_000,
    ): ScopedReadResult {
        val segments = when (mode) {
            SegmentMode.NOTE -> ContentSegmentation.splitContentSegments(fullText)
            SegmentMode.FILE -> ContentSegmentation.splitFileLineWindows(fullText)
        }
        if (segments.isEmpty()) {
            return ScopedReadResult(buildJsonObject {
                put("content", JsonPrimitive(""))
                put("truncated", JsonPrimitive(false))
                put("totalLines", JsonPrimitive(0))
                put("sections", buildJsonArray {})
            }.toString())
        }

        val queryVector = embeddingEngine.embed(query)
        val ranked = segments.map { segment ->
            val score = cosine(queryVector, embeddingEngine.embed(segment.text))
            segment to score
        }
            .sortedByDescending { it.second }
            .take(maxSegments)

        var charBudget = maxTotalChars
        val excerptParts = mutableListOf<String>()
        val sectionJson = buildJsonArray {
            ranked.forEach { (segment, score) ->
                if (charBudget <= 0) return@forEach
                val excerpt = segment.text.take(charBudget)
                charBudget -= excerpt.length
                excerptParts += excerpt
                addJsonObject {
                    put("excerpt", JsonPrimitive(excerpt))
                    put("startLine", JsonPrimitive(segment.startLine))
                    put("endLine", JsonPrimitive(segment.endLine))
                    put("anchor", JsonPrimitive(segment.anchor))
                    put("score", JsonPrimitive(score))
                }
            }
        }

        val totalLines = fullText.lines().size
        val content = excerptParts.joinToString("\n\n---\n\n")
        return ScopedReadResult(
            buildJsonObject {
                put("content", JsonPrimitive(content))
                put("truncated", JsonPrimitive(fullText.length > maxTotalChars || segments.size > maxSegments))
                put("totalLines", JsonPrimitive(totalLines))
                put("sections", sectionJson)
            }.toString(),
        )
    }

    fun lineRangeJson(fullText: String, startLine: Int, endLine: Int): ScopedReadResult {
        val excerpt = ContentSegmentation.extractLineRange(fullText, startLine, endLine)
        val totalLines = fullText.lines().size
        return ScopedReadResult(
            buildJsonObject {
                put("content", JsonPrimitive(excerpt))
                put("truncated", JsonPrimitive(excerpt.length < fullText.length))
                put("totalLines", JsonPrimitive(totalLines))
                put("sections", buildJsonArray {
                    addJsonObject {
                        put("excerpt", JsonPrimitive(excerpt))
                        put("startLine", JsonPrimitive(startLine))
                        put("endLine", JsonPrimitive(endLine))
                        put("anchor", JsonPrimitive("lines $startLine–$endLine"))
                    }
                })
            }.toString(),
        )
    }

    fun truncatedHintJson(
        fullText: String,
        summary: String?,
        hint: String,
    ): ScopedReadResult {
        val preview = summary?.trim()?.takeIf { it.isNotEmpty() }
            ?: fullText.take(400)
        return ScopedReadResult(
            buildJsonObject {
                put("content", JsonPrimitive(preview))
                put("truncated", JsonPrimitive(true))
                put("totalLines", JsonPrimitive(fullText.lines().size))
                put("hint", JsonPrimitive(hint))
                if (!summary.isNullOrBlank()) {
                    put("summary", JsonPrimitive(summary.trim()))
                }
                put("sections", buildJsonArray {})
            }.toString(),
        )
    }

    private fun cosine(a: FloatArray, b: FloatArray): Float {
        if (a.isEmpty() || b.isEmpty() || a.size != b.size) return 0f
        var dot = 0f
        var normA = 0f
        var normB = 0f
        for (i in a.indices) {
            dot += a[i] * b[i]
            normA += a[i] * a[i]
            normB += b[i] * b[i]
        }
        if (normA <= 1e-6f || normB <= 1e-6f) return 0f
        return (dot / (kotlin.math.sqrt(normA) * kotlin.math.sqrt(normB))).coerceIn(-1f, 1f)
    }
}
