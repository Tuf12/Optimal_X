package com.example.optimalx.data.eidos.provider

import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive

/**
 * Extracts human-readable reasoning from OpenAI/xAI Responses API `output` arrays.
 *
 * Reasoning appears as items with `type: "reasoning"`, containing optional
 * `summary` (`summary_text`) and `content` (`reasoning_text`) blocks.
 */
object ResponsesReasoningParser {

    fun extractReasoningText(output: JsonArray?): String {
        if (output == null) return ""
        val parts = mutableListOf<String>()
        output.forEach { item ->
            val obj = item.jsonObject
            if (obj["type"]?.jsonPrimitive?.contentOrNull != "reasoning") return@forEach
            obj["summary"]?.jsonArray?.forEach { summaryItem ->
                val summaryObj = summaryItem.jsonObject
                if (summaryObj["type"]?.jsonPrimitive?.contentOrNull == "summary_text") {
                    summaryObj["text"]?.jsonPrimitive?.contentOrNull
                        ?.takeIf { it.isNotBlank() }
                        ?.let { parts.add(it) }
                }
            }
            obj["content"]?.jsonArray?.forEach { contentItem ->
                val contentObj = contentItem.jsonObject
                if (contentObj["type"]?.jsonPrimitive?.contentOrNull == "reasoning_text") {
                    contentObj["text"]?.jsonPrimitive?.contentOrNull
                        ?.takeIf { it.isNotBlank() }
                        ?.let { parts.add(it) }
                }
            }
        }
        return parts.joinToString("\n\n").trim()
    }
}
