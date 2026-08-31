package com.example.optimalx.data.eidos.prompt

import java.security.MessageDigest
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

object EidosPromptTrace {

    fun sha256(text: String): String {
        val digest = MessageDigest.getInstance("SHA-256").digest(text.toByteArray(Charsets.UTF_8))
        return digest.joinToString("") { byte -> "%02x".format(byte) }
    }

    fun toolAllowlistHash(toolNames: Collection<String>): String =
        sha256(toolNames.sorted().joinToString(","))

    fun sectionCharCountsJson(counts: Map<String, Int>): String {
        if (counts.isEmpty()) return "{}"
        return counts.entries.joinToString(prefix = "{", postfix = "}") { (key, value) ->
            "\"$key\":$value"
        }
    }
}
