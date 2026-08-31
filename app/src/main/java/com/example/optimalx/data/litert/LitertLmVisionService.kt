package com.example.optimalx.data.litert

import com.example.optimalx.OptimalXApplication
import com.example.optimalx.data.preferences.SettingsDefaults
import com.example.optimalx.data.preferences.SettingsKeys
import com.example.optimalx.data.preferences.settingsDataStore
import com.google.ai.edge.litertlm.ConversationConfig
import com.google.ai.edge.litertlm.ExperimentalApi
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.withContext
import java.io.File

class LitertLmVisionService(private val app: OptimalXApplication) {

    suspend fun describeImage(
        file: File,
        prompt: String = DEFAULT_PROMPT,
    ): Result<String> = withContext(Dispatchers.IO) {
        app.litertLmEngineHolder.beginUse()
        try {
            runCatching {
                if (!file.isFile) error("Image file not found: ${file.absolutePath}")
                val prefs = app.settingsDataStore.data.first()
                val modelPath = LitertLmDefaults.resolveModelPath(
                    app,
                    prefs[SettingsKeys.LITERT_MODEL_PATH].orEmpty(),
                )
                val backend = LitertLmBackend.fromWire(
                    prefs[SettingsKeys.LITERT_BACKEND] ?: SettingsDefaults.LITERT_BACKEND,
                )
                val prepare = app.litertLmEngineHolder.prepare(
                    modelPath,
                    backend,
                    includeVision = true,
                )
                if (prepare.isFailure) {
                    error(prepare.exceptionOrNull()?.message ?: "Local Gemma engine could not load")
                }
                val engine = app.litertLmEngineHolder.getEngine()
                    ?: error("Local Gemma engine is not available after load")

                engine.createConversation(
                    ConversationConfig(
                        tools = emptyList(),
                        automaticToolCalling = false,
                    ),
                ).use { conversation ->
                    @OptIn(ExperimentalApi::class)
                    val response = conversation.sendMessage(
                        LitertLmMultimodal.visionPromptContents(file.absolutePath, prompt),
                    )
                    val text = LitertLmMessageText.finalDisplayText(response)
                    text.ifBlank { "No description returned from local Gemma vision." }
                }
            }
        } finally {
            app.litertLmEngineHolder.endUse()
        }
    }

    private companion object {
        const val DEFAULT_PROMPT =
            "Describe this image in concise detail, including key objects, text visible in the image, and likely context."
    }
}
