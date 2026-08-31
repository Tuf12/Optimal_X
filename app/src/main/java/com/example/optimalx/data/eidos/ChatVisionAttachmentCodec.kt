package com.example.optimalx.data.eidos

import kotlinx.serialization.json.Json

object ChatVisionAttachmentCodec {
    private val json = Json {
        ignoreUnknownKeys = true
        encodeDefaults = false
    }

    fun parseAttachmentJson(raw: String?): ChatVisionAttachment? {
        if (raw.isNullOrBlank()) return null
        return runCatching {
            normalize(json.decodeFromString(ChatVisionAttachment.serializer(), raw))
        }.getOrNull()
    }

    fun serializeAttachment(attachment: ChatVisionAttachment?): String? {
        val normalized = normalize(attachment) ?: return null
        return json.encodeToString(ChatVisionAttachment.serializer(), normalized)
    }

    fun displayLabel(attachment: ChatVisionAttachment): String {
        return attachment.fileName.trim().ifEmpty { "Image attached" }
    }

    fun safeStoredName(raw: String?): String? {
        return normalize(ChatVisionAttachment(storedName = raw.orEmpty()))?.storedName
    }

    const val EMPTY_PROMPT = "Describe this image."
    const val MISSING_BYTES_REPLY =
        "The attached image isn't on this phone. Chat-image bytes stay on the device that captured them."

    fun normalize(raw: ChatVisionAttachment?): ChatVisionAttachment? {
        if (raw == null) return null
        val storedName = basename(raw.storedName)
        if (storedName.isEmpty()) return null
        val fileName = raw.fileName.trim().ifEmpty { storedName }
        val mimeType = raw.mimeType.trim().ifEmpty { "image/png" }
        return ChatVisionAttachment(
            fileName = fileName,
            mimeType = mimeType,
            storedName = storedName,
        )
    }

    private fun basename(value: String?): String {
        val trimmed = value?.trim().orEmpty()
        if (trimmed.isEmpty() || trimmed == "." || trimmed == "..") return ""
        val slash = trimmed.lastIndexOfAny(charArrayOf('/', '\\'))
        val name = if (slash >= 0) trimmed.substring(slash + 1) else trimmed
        return name.takeIf { it.isNotEmpty() && it != "." && it != ".." }.orEmpty()
    }
}
