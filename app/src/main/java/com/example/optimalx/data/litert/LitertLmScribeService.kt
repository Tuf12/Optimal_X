package com.example.optimalx.data.litert

import android.util.Log
import com.example.optimalx.OptimalXApplication
import com.example.optimalx.data.preferences.SettingsDefaults
import com.example.optimalx.data.preferences.SettingsKeys
import com.example.optimalx.data.preferences.settingsDataStore
import com.google.ai.edge.litertlm.Contents
import com.google.ai.edge.litertlm.Conversation
import com.google.ai.edge.litertlm.ConversationConfig
import com.google.ai.edge.litertlm.ExperimentalApi
import com.google.ai.edge.litertlm.SamplerConfig
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext

/**
 * On-device Gemma STT. One LiteRT [Conversation] is reused for a recording session so
 * rolling slices do not allocate a new Gemma4DataProcessor each window (that native
 * create is what killed the process after ~3 slices).
 */
class LitertLmScribeService(private val app: OptimalXApplication) {

    companion object {
        private const val TAG = "OptimalX.GemmaScribe"
        private const val RECYCLE_TOKEN_FRACTION = 0.55f
        private val SCRIBE_SAMPLER = SamplerConfig(
            topK = 1,
            topP = 0.0,
            temperature = 0.0,
            seed = 0,
        )
        private val SCRIBE_SYSTEM = Contents.of(
            "Transcribe the user's speech. Reply with only the transcript, no quotes or preamble.",
        )
    }

    private val mutex = Mutex()
    private var sessionHeld = false
    private var conversation: Conversation? = null

    suspend fun beginSession() = mutex.withLock {
        if (!sessionHeld) {
            app.litertLmEngineHolder.beginUse()
            sessionHeld = true
        }
    }

    suspend fun endSession() = mutex.withLock {
        closeConversationLocked()
        if (sessionHeld) {
            app.litertLmEngineHolder.endUse()
            sessionHeld = false
        }
    }

    /** [audioWav] must be a RIFF WAVE blob — LiteRT miniaudio rejects raw PCM. */
    suspend fun transcribe(audioWav: ByteArray): Result<String> = withContext(Dispatchers.IO) {
        mutex.withLock {
            runCatching {
                if (audioWav.isEmpty()) return@runCatching ""
                ensureSessionLocked()
                val engine = prepareEngineLocked()
                recycleConversationIfNeededLocked()
                val session = conversation ?: engine.createConversation(scribeConversationConfig()).also {
                    conversation = it
                    Log.i(TAG, "Opened scribe conversation")
                }
                val tokensBefore = runCatching { session.getTokenCount() }.getOrDefault(-1)
                Log.i(TAG, "scribe slice wavBytes=${audioWav.size} tokensBefore=$tokensBefore")
                @OptIn(ExperimentalApi::class)
                val response = session.sendMessage(
                    LitertLmMultimodal.scribePromptContents(audioWav),
                )
                val text = LitertLmMessageText.finalDisplayText(response)
                val tokensAfter = runCatching { session.getTokenCount() }.getOrDefault(-1)
                Log.i(TAG, "scribe slice done tokensAfter=$tokensAfter chars=${text.length}")
                text
            }
        }
    }

    private fun ensureSessionLocked() {
        if (sessionHeld) return
        app.litertLmEngineHolder.beginUse()
        sessionHeld = true
    }

    private suspend fun prepareEngineLocked() =
        withContext(Dispatchers.IO) {
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
                includeVision = false,
            )
            if (prepare.isFailure) {
                error(prepare.exceptionOrNull()?.message ?: "Local Gemma engine could not load")
            }
            app.litertLmEngineHolder.getEngine()
                ?: error("Local Gemma engine is not available after load")
        }

    private fun recycleConversationIfNeededLocked() {
        val current = conversation ?: return
        val tokens = runCatching { current.getTokenCount() }.getOrDefault(0)
        val limit = (GemmaLocalPolicy.MAX_NUM_TOKENS * RECYCLE_TOKEN_FRACTION).toInt()
        if (tokens < limit) return
        Log.i(TAG, "Recycling scribe conversation tokens=$tokens limit=$limit")
        closeConversationLocked()
    }

    private fun closeConversationLocked() {
        val current = conversation ?: return
        conversation = null
        runCatching { current.close() }
            .onFailure { error -> Log.w(TAG, "Failed to close scribe conversation", error) }
    }

    private fun scribeConversationConfig(): ConversationConfig =
        ConversationConfig(
            systemInstruction = SCRIBE_SYSTEM,
            tools = emptyList(),
            samplerConfig = SCRIBE_SAMPLER,
            automaticToolCalling = false,
        )
}
