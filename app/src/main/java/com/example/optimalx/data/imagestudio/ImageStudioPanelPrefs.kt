package com.example.optimalx.data.imagestudio

import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json

object ImageStudioPanelState {
    const val SCOPE_KEY: String = "image_studio"
}

@Serializable
data class ImageStudioPanelPrefs(
    val fileName: String = "",
    val prompt: String = "",
    val negativePrompt: String = "",
    val tier: String = ImageTier.DRAFT.wireValue,
    val aspectRatio: String = ImageAspectRatio.DEFAULT.wireValue,
) {
    fun tierEnum(): ImageTier = ImageTier.fromWire(tier) ?: ImageTier.DRAFT

    fun aspectEnum(): ImageAspectRatio =
        ImageAspectRatio.fromWire(aspectRatio) ?: ImageAspectRatio.DEFAULT
}

object ImageStudioPanelPrefsCodec {
    private val json = Json {
        ignoreUnknownKeys = true
        encodeDefaults = true
    }

    fun encode(prefs: ImageStudioPanelPrefs): String = json.encodeToString(prefs)

    fun decode(raw: String?): ImageStudioPanelPrefs {
        if (raw.isNullOrBlank()) return ImageStudioPanelPrefs()
        return runCatching { json.decodeFromString<ImageStudioPanelPrefs>(raw.trim()) }
            .getOrDefault(ImageStudioPanelPrefs())
    }
}
