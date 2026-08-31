package com.example.optimalx.data.imagestudio

import android.content.Context
import com.example.optimalx.data.preferences.ApiKeyNames
import com.example.optimalx.data.preferences.getEncryptedPrefs

fun interface ImageGenerationApiKeyProvider {
    fun xaiApiKey(): String?
}

fun imageGenerationApiKeyProvider(context: Context): ImageGenerationApiKeyProvider {
    val prefs = getEncryptedPrefs(context.applicationContext)
    return ImageGenerationApiKeyProvider {
        prefs.getString(ApiKeyNames.XAI, null)?.trim()?.takeIf { it.isNotEmpty() }
    }
}
