package com.example.optimalx.data.imagestudio

import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json

/**
 * Generation metadata stored on [com.example.optimalx.data.model.FileReference.metadataJson].
 * Wire shape matches desktop [metadata.js](https://github.com/Tuf12/OptimalXDesktop1.0/blob/main/electron/image-studio/metadata.js).
 */
@Serializable
data class ImageStudioMetadata(
    val source: String = SOURCE,
    val backend: String,
    val prompt: String,
    val negativePrompt: String = "",
    val modelId: String,
    val tier: String,
    val aspectRatio: String,
    val seed: Long? = null,
    val width: Int? = null,
    val height: Int? = null,
    val createdAt: Long = System.currentTimeMillis(),
    val estimatedCostUsd: Double? = null,
    val actualCostUsd: Double? = null,
    val providerRequestId: String? = null,
) {
    companion object {
        const val SOURCE: String = "image_studio"

        private val json = Json {
            ignoreUnknownKeys = true
            encodeDefaults = true
        }

        fun parse(raw: String?): ImageStudioMetadata? {
            if (raw.isNullOrBlank()) return null
            return runCatching { json.decodeFromString<ImageStudioMetadata>(raw.trim()) }.getOrNull()
        }

        fun stringify(value: ImageStudioMetadata?): String? {
            if (value == null) return null
            return json.encodeToString(value)
        }

        fun isImageStudioMetadata(raw: String?): Boolean = parse(raw)?.source == SOURCE

        fun build(
            prompt: String,
            backend: String,
            modelId: String,
            tier: String,
            aspectRatio: String,
            negativePrompt: String = "",
            seed: Long? = null,
            width: Int? = null,
            height: Int? = null,
            createdAt: Long = System.currentTimeMillis(),
            estimatedCostUsd: Double? = null,
            actualCostUsd: Double? = null,
            providerRequestId: String? = null,
        ): ImageStudioMetadata = ImageStudioMetadata(
            source = SOURCE,
            backend = backend,
            prompt = prompt.trim(),
            negativePrompt = negativePrompt.trim(),
            modelId = modelId,
            tier = tier,
            aspectRatio = aspectRatio,
            seed = seed,
            width = width,
            height = height,
            createdAt = createdAt,
            estimatedCostUsd = estimatedCostUsd,
            actualCostUsd = actualCostUsd,
            providerRequestId = providerRequestId,
        )
    }
}
