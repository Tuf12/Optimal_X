package com.example.optimalx.data.litert

import android.content.Context
import com.example.optimalx.OptimalXApplication
import com.example.optimalx.data.preferences.SettingsDefaults
import com.example.optimalx.data.preferences.SettingsKeys
import com.example.optimalx.data.preferences.settingsDataStore
import com.google.ai.edge.litertlm.ConversationConfig
import com.google.ai.edge.litertlm.Engine
import com.google.ai.edge.litertlm.EngineConfig
import com.google.ai.edge.litertlm.ExperimentalApi
import com.google.ai.edge.litertlm.ExperimentalFlags
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.withContext
import java.io.File

/**
 * Phase 1 smoke test: load [gemma-4-E4B-it.litertlm] and run one synchronous prompt.
 * Uses the app warm [LitertLmEngineHolder] when available to avoid loading a second 3.7GB engine (OOM).
 */
class LitertLmSmokeRunner {

    data class SmokeResult(
        val responseText: String,
        val initMillis: Long,
        val inferenceMillis: Long,
        val modelPath: String,
    )

    suspend fun runOneShotPrompt(
        context: Context,
        modelPath: String,
        prompt: String = LitertLmDefaults.DEFAULT_SMOKE_PROMPT,
    ): Result<SmokeResult> = withContext(Dispatchers.IO) {
        val file = File(modelPath.trim())
        if (!file.isFile) {
            return@withContext Result.failure(
                IllegalArgumentException("Model file not found: ${file.absolutePath}"),
            )
        }
        val app = context.applicationContext as? OptimalXApplication
        if (app != null) {
            return@withContext runWithWarmHolder(app, file, prompt)
        }
        runWithColdEngine(context, file, prompt)
    }

    private suspend fun runWithWarmHolder(
        app: OptimalXApplication,
        file: File,
        prompt: String,
    ): Result<SmokeResult> = runCatching {
        val prefs = app.settingsDataStore.data.first()
        val backend = LitertLmBackend.fromWire(
            prefs[SettingsKeys.LITERT_BACKEND] ?: SettingsDefaults.LITERT_BACKEND,
        )
        app.litertLmEngineHolder.beginUse()
        try {
            val initStart = System.currentTimeMillis()
            val prepare = app.litertLmEngineHolder.prepare(file.absolutePath, backend)
            if (prepare.isFailure) {
                error(prepare.exceptionOrNull()?.message ?: "Engine prepare failed")
            }
            val initMillis = System.currentTimeMillis() - initStart
            val engine = app.litertLmEngineHolder.getEngine()
                ?: error("Engine missing after prepare")
            engine.createConversation(
                ConversationConfig(
                    tools = emptyList(),
                    automaticToolCalling = false,
                ),
            ).use { conversation ->
                val inferStart = System.currentTimeMillis()
                val response = conversation.sendMessage(prompt)
                val inferenceMillis = System.currentTimeMillis() - inferStart
                val text = LitertLmMessageText.finalDisplayText(response)
                SmokeResult(
                    responseText = text,
                    initMillis = initMillis,
                    inferenceMillis = inferenceMillis,
                    modelPath = file.absolutePath,
                )
            }
        } finally {
            app.litertLmEngineHolder.endUse()
        }
    }

    private suspend fun runWithColdEngine(
        context: Context,
        file: File,
        prompt: String,
    ): Result<SmokeResult> = runCatching {
        @OptIn(ExperimentalApi::class)
        ExperimentalFlags.enableSpeculativeDecoding = false

        val backends = LitertLmEngineBackends.resolve(LitertLmBackend.GPU)
        val config = EngineConfig(
            modelPath = file.absolutePath,
            backend = backends.main,
            visionBackend = null,
            audioBackend = backends.audio,
            maxNumTokens = GemmaLocalPolicy.MAX_NUM_TOKENS,
            cacheDir = context.cacheDir.path,
        )
        val initStart = System.currentTimeMillis()
        Engine(config).use { engine ->
            engine.initialize()
            val initMillis = System.currentTimeMillis() - initStart
            engine.createConversation(
                ConversationConfig(
                    tools = emptyList(),
                    automaticToolCalling = false,
                ),
            ).use { conversation ->
                val inferStart = System.currentTimeMillis()
                val response = conversation.sendMessage(prompt)
                val inferenceMillis = System.currentTimeMillis() - inferStart
                SmokeResult(
                    responseText = LitertLmMessageText.finalDisplayText(response),
                    initMillis = initMillis,
                    inferenceMillis = inferenceMillis,
                    modelPath = file.absolutePath,
                )
            }
        }
    }
}
