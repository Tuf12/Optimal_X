package com.example.optimalx.data.imagestudio

enum class ImageTier {
    DRAFT,
    QUALITY,
    ;

    val wireValue: String
        get() = when (this) {
            DRAFT -> "draft"
            QUALITY -> "quality"
        }

    companion object {
        fun fromWire(value: String?): ImageTier? = when (value?.lowercase()) {
            "draft", "4b" -> DRAFT
            "quality", "9b" -> QUALITY
            else -> null
        }
    }
}

enum class ImageAspectRatio(val wireValue: String) {
    SQUARE("1:1"),
    PORTRAIT_2_3("2:3"),
    PORTRAIT_9_16("9:16"),
    LANDSCAPE_3_2("3:2"),
    LANDSCAPE_16_9("16:9"),
    ;

    companion object {
        val DEFAULT: ImageAspectRatio = SQUARE

        fun fromWire(value: String?): ImageAspectRatio? =
            entries.firstOrNull { it.wireValue == value?.trim() }
    }
}

data class ImageGenerationRequest(
    val prompt: String,
    val negativePrompt: String = "",
    val tier: ImageTier,
    val aspectRatio: ImageAspectRatio,
    val seed: Long? = null,
)

data class ImageGenerationResult(
    val imageBytes: ByteArray,
    val mimeType: String,
    val modelId: String,
    val seed: Long?,
    val width: Int,
    val height: Int,
    val actualCostUsd: Double?,
    val providerRequestId: String?,
) {
    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (other !is ImageGenerationResult) return false
        return imageBytes.contentEquals(other.imageBytes) &&
            mimeType == other.mimeType &&
            modelId == other.modelId &&
            seed == other.seed &&
            width == other.width &&
            height == other.height &&
            actualCostUsd == other.actualCostUsd &&
            providerRequestId == other.providerRequestId
    }

    override fun hashCode(): Int = imageBytes.contentHashCode()
}

data class CostEstimate(
    val usd: Double,
    val label: String,
)

class ImageGenerationException(
    val code: String,
    override val message: String,
    cause: Throwable? = null,
) : Exception(message, cause) {
    companion object {
        const val NO_API_KEY = "no_api_key"
        const val API_AUTH_FAILED = "api_auth_failed"
        const val API_RATE_LIMIT = "api_rate_limit"
        const val API_CONTENT_POLICY = "api_content_policy"
        const val API_TIMEOUT = "api_timeout"
        const val API_ERROR = "api_error"
        const val NETWORK_OFFLINE = "network_offline"
        const val CANCELLED = "cancelled"
        const val DUPLICATE_NAME = "duplicate_name"
        const val INVALID_NAME = "invalid_name"
        const val SAVE_FAILED = "save_failed"
        const val EMPTY_PROMPT = "empty_prompt"
    }
}
