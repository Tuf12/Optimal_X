package com.example.optimalx.data.litert

import android.content.Context
import android.util.Log
import com.google.ai.edge.litertlm.Engine
import com.google.ai.edge.litertlm.EngineConfig
import com.google.ai.edge.litertlm.ExperimentalApi
import com.google.ai.edge.litertlm.ExperimentalFlags
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import java.io.File

/**
 * Single warm [Engine] for local Eidos chat and local Gemma scribe.
 * Load when local provider or scribe toggle is on; stay loaded while the
 * process is alive (minimize and widget hops included). Release when both
 * features are off, the process dies, or the OS reports critical memory.
 */
class LitertLmEngineHolder(private val context: Context) {

    companion object {
        private const val TAG = "OptimalX.LitertLmEngine"
    }

    private data class LoadedConfig(
        val modelPath: String,
        val backend: LitertLmBackend,
        val maxNumTokens: Int,
        val speculativeDecoding: Boolean,
        val includeVision: Boolean,
    )

    private val mutex = Mutex()
    private var engine: Engine? = null
    private var loadedConfig: LoadedConfig? = null
    private var activeUseCount = 0

    private val _warmState = MutableStateFlow(LitertLmWarmState.Idle)
    val warmState: StateFlow<LitertLmWarmState> = _warmState.asStateFlow()

    private val _lastError = MutableStateFlow<String?>(null)
    val lastError: StateFlow<String?> = _lastError.asStateFlow()

    fun getEngine(): Engine? = engine

    fun beginUse() {
        activeUseCount++
    }

    fun endUse() {
        if (activeUseCount > 0) activeUseCount--
    }

    suspend fun prepare(
        modelPath: String,
        backend: LitertLmBackend,
        includeVision: Boolean = false,
    ): Result<Unit> = mutex.withLock {
        withContext(Dispatchers.IO) {
            val file = File(modelPath.trim())
            LitertLmModelValidator.validate(file).onFailure { error ->
                recordFailure(error.message ?: "Invalid model file")
                return@withContext Result.failure(error)
            }

            val wanted = LoadedConfig(
                modelPath = file.absolutePath,
                backend = backend,
                maxNumTokens = GemmaLocalPolicy.MAX_NUM_TOKENS,
                speculativeDecoding = false,
                includeVision = includeVision,
            )
            val current = engine
            val loaded = loadedConfig
            if (current != null && loaded?.modelPath == wanted.modelPath && current.isInitialized()) {
                when {
                    loaded.satisfies(wanted) -> {
                        if (loaded.backend == LitertLmBackend.CPU && wanted.backend == LitertLmBackend.GPU) {
                            _lastError.value =
                                "Using CPU (GPU failed previously). Switch backend to CPU in Settings."
                        } else {
                            clearError()
                        }
                        _warmState.value = LitertLmWarmState.Ready
                        return@withContext Result.success(Unit)
                    }
                    activeUseCount > 1 -> {
                        val message =
                            "Local Gemma is busy. Stop the mic or wait for the current reply, then retry."
                        return@withContext Result.failure(IllegalStateException(message))
                    }
                    else -> teardownEngineLocked()
                }
            } else if (current != null) {
                if (activeUseCount > 1) {
                    val message =
                        "Local Gemma is busy. Stop the mic or wait for the current reply, then retry."
                    return@withContext Result.failure(IllegalStateException(message))
                }
                teardownEngineLocked()
            }

            _warmState.value = LitertLmWarmState.Loading

            val primaryResult = initializeEngine(file, backend, includeVision)
            if (primaryResult.isSuccess) {
                clearError()
                _warmState.value = LitertLmWarmState.Ready
                return@withContext Result.success(Unit)
            }

            val primaryError = primaryResult.exceptionOrNull()?.message ?: "unknown error"
            if (backend == LitertLmBackend.GPU) {
                Log.w(TAG, "GPU engine init failed, retrying CPU: $primaryError")
                teardownEngineLocked()
                val cpuResult = initializeEngine(file, LitertLmBackend.CPU, includeVision)
                if (cpuResult.isSuccess) {
                    clearError()
                    _warmState.value = LitertLmWarmState.Ready
                    _lastError.value =
                        "Loaded on CPU (GPU failed: $primaryError). Switch backend to CPU in Settings."
                    Log.i(TAG, "Engine loaded on CPU after GPU failure")
                    return@withContext Result.success(Unit)
                }
                val cpuError = cpuResult.exceptionOrNull()?.message ?: "unknown error"
                recordFailure("GPU: $primaryError. CPU: $cpuError")
                return@withContext Result.failure(
                    IllegalStateException("GPU: $primaryError. CPU: $cpuError"),
                )
            }

            recordFailure(primaryError)
            Result.failure(primaryResult.exceptionOrNull() ?: IllegalStateException(primaryError))
        }
    }

    private fun initializeEngine(
        file: File,
        backend: LitertLmBackend,
        includeVision: Boolean,
    ): Result<Unit> = runCatching {
        // MTP / speculative decode needs a GPU sampler (.so). This device falls back to
        // CPU sampling (see logcat sampler_factory), which with MTP on produces garbled
        // repeats ("the the the"). Gallery defaults speculative decode off.
        @OptIn(ExperimentalApi::class)
        ExperimentalFlags.enableSpeculativeDecoding = false

        val backends = LitertLmEngineBackends.resolve(backend)
        // KV window size: change GemmaLocalPolicy.MAX_NUM_TOKENS (not here). Bigger =
        // more RAM at initialize(); 32768 OOMs on phone. See GemmaLocalPolicy KDoc.
        // Vision is omitted unless a caller needs describe-image / chat attach — first
        // Conversation.create() otherwise compiles the 224MB vision encoder on GPU.
        val config = EngineConfig(
            modelPath = file.absolutePath,
            backend = backends.main,
            visionBackend = if (includeVision) backends.vision else null,
            audioBackend = backends.audio,
            maxNumTokens = GemmaLocalPolicy.MAX_NUM_TOKENS,
            cacheDir = context.cacheDir.path,
        )
        Log.i(
            TAG,
            "Initializing LiteRT-LM (${backend.wire}, audio=cpu, vision=$includeVision, " +
                "maxNumTokens=${GemmaLocalPolicy.MAX_NUM_TOKENS}, speculativeDecoding=false) " +
                "path=${file.absolutePath} size=${file.length()}",
        )
        val newEngine = Engine(config)
        newEngine.initialize()
        engine = newEngine
        loadedConfig = LoadedConfig(
            modelPath = file.absolutePath,
            backend = backend,
            maxNumTokens = GemmaLocalPolicy.MAX_NUM_TOKENS,
            speculativeDecoding = false,
            includeVision = includeVision,
        )
        Log.i(
            TAG,
            "LiteRT-LM engine ready (${backend.wire}, vision=$includeVision, " +
                "maxNumTokens=${GemmaLocalPolicy.MAX_NUM_TOKENS})",
        )
    }

    private fun LoadedConfig.satisfies(wanted: LoadedConfig): Boolean {
        if (modelPath != wanted.modelPath) return false
        if (maxNumTokens != wanted.maxNumTokens) return false
        if (speculativeDecoding != wanted.speculativeDecoding) return false
        if (backend != wanted.backend) {
            return backend == LitertLmBackend.CPU && wanted.backend == LitertLmBackend.GPU
        }
        return includeVision || !wanted.includeVision
    }

    private fun recordFailure(message: String) {
        _lastError.value = message
        _warmState.value = LitertLmWarmState.Error
        Log.e(TAG, "LiteRT-LM engine failed: $message")
    }

    private fun clearError() {
        _lastError.value = null
    }

    private fun teardownEngineLocked(reason: String = "unspecified") {
        if (engine != null) {
            Log.i(TAG, "Releasing LiteRT-LM engine ($reason)")
        }
        engine?.close()
        engine = null
        loadedConfig = null
    }

    suspend fun release() = mutex.withLock {
        withContext(Dispatchers.IO) {
            teardownEngineLocked()
            clearError()
            _warmState.value = LitertLmWarmState.Idle
        }
    }

    suspend fun releaseIfNotInUse(): Boolean = mutex.withLock {
        if (activeUseCount > 0) return false
        if (engine == null) return false
        withContext(Dispatchers.IO) {
            teardownEngineLocked()
            clearError()
            _warmState.value = LitertLmWarmState.Idle
        }
        true
    }
}
