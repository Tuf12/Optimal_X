package com.example.optimalx.data.semantic

import android.content.Context
import com.google.mediapipe.tasks.core.BaseOptions
import com.google.mediapipe.tasks.text.textembedder.TextEmbedder
import com.google.mediapipe.tasks.text.textembedder.TextEmbedder.TextEmbedderOptions
import com.google.mediapipe.tasks.text.textembedder.TextEmbedderResult
import java.util.concurrent.Callable
import java.util.concurrent.Executors
import kotlin.math.sqrt

class EmbeddingEngine(private val context: Context) {

  /**
   * MediaPipe TextEmbedder must be created and used on one thread; internal TFLite workers
   * are not safe when [embed] is invoked from different pool threads (e.g. startup + rollover).
   */
  private val embedderExecutor = Executors.newSingleThreadExecutor { runnable ->
    Thread(runnable, EMBEDDER_THREAD_NAME).apply { isDaemon = true }
  }

  private var initError: String? = null
  private var loadedModelPath: String? = null
  private var textEmbedder: TextEmbedder? = null
  private var embedderLoadAttempted = false
  private var cachedVectorSize: Int? = null

  fun embed(text: String): FloatArray = runOnEmbedderThread {
    val normalizedInput = text.trim().take(MAX_INPUT_CHARS)
    if (normalizedInput.isBlank()) return@runOnEmbedderThread FloatArray(vectorSizeOnThread())

    val modelOutput = runCatching { embedWithModelOnThread(normalizedInput) }.getOrNull()
    if (modelOutput != null) return@runOnEmbedderThread modelOutput

    embedFallback(normalizedInput, vectorSizeOnThread())
  }

  fun diagnostics(): EmbeddingDiagnostics = runOnEmbedderThread {
    val loaded = ensureEmbedderOnThread() != null
    val inferenceOk = loaded && runCatching {
      embedWithModelOnThread("semantic-healthcheck").isNotEmpty()
    }.getOrDefault(false)
    EmbeddingDiagnostics(
      modelPath = loadedModelPath,
      modelLoaded = loaded,
      modelInferenceOk = inferenceOk,
      fallbackOnly = !inferenceOk,
      initError = initError,
    )
  }

  private fun vectorSizeOnThread(): Int {
    cachedVectorSize?.let { return it }
    val size = ensureEmbedderOnThread()?.let { embedder ->
      runCatching {
        val result: TextEmbedderResult = embedder.embed("probe")
        result.embeddingResult().embeddings()[0].floatEmbedding().size
      }.getOrDefault(DEFAULT_DIM)
    } ?: DEFAULT_DIM
    cachedVectorSize = size
    return size
  }

  private fun ensureEmbedderOnThread(): TextEmbedder? {
    if (embedderLoadAttempted) return textEmbedder
    embedderLoadAttempted = true
    textEmbedder = loadEmbedderOnThread()
    return textEmbedder
  }

  private fun embedWithModelOnThread(text: String): FloatArray {
    val embedder = ensureEmbedderOnThread() ?: throw IllegalStateException("TextEmbedder not available")
    val result: TextEmbedderResult = embedder.embed(text)
    val floatVec: FloatArray = result.embeddingResult().embeddings()[0].floatEmbedding()
    return normalize(floatVec.copyOf())
  }

  private fun loadEmbedderOnThread(): TextEmbedder? {
    synchronized(MODEL_LOAD_LOCK) {
      MODEL_ASSET_CANDIDATES.forEach { path ->
        val embedder: TextEmbedder? = try {
          val options = TextEmbedderOptions.builder()
            .setBaseOptions(BaseOptions.builder().setModelAssetPath(path).build())
            .build()
          TextEmbedder.createFromOptions(context, options).also { loadedModelPath = path }
        } catch (t: Throwable) {
          initError = t.message
          null
        }
        if (embedder != null) return embedder
      }
      return null
    }
  }

  private fun embedFallback(text: String, size: Int): FloatArray {
    val vec = FloatArray(size)
    val tokens = text.lowercase()
      .split(Regex("[^a-z0-9_]+"))
      .filter { it.isNotBlank() }

    if (tokens.isEmpty()) return vec

    tokens.forEach { token ->
      val h = token.hashCode()
      val idx = (h and Int.MAX_VALUE) % size
      val sign = if ((h and 1) == 0) 1f else -1f
      vec[idx] += sign
    }
    return normalize(vec)
  }

  private fun normalize(values: FloatArray): FloatArray {
    val norm = sqrt(values.sumOf { (it * it).toDouble() }).toFloat()
    if (norm <= 1e-6f) return values
    for (i in values.indices) values[i] /= norm
    return values
  }

  private fun <T> runOnEmbedderThread(block: () -> T): T {
    if (Thread.currentThread().name == EMBEDDER_THREAD_NAME) return block()
    return embedderExecutor.submit(Callable { block() }).get()
  }

  companion object {
    /** MediaPipe TFLite init is process-global; never load the model from two threads at once. */
    private val MODEL_LOAD_LOCK = Any()
    private const val EMBEDDER_THREAD_NAME = "OptimalX-Embedding"
    private const val DEFAULT_DIM = 512
    private const val MAX_INPUT_CHARS = 6000
    private val MODEL_ASSET_CANDIDATES = listOf(
      "models/universal_sentence_encoder.tflite",
      "universal_sentence_encoder.tflite",
      "models/use.tflite",
      "use.tflite",
    )
  }
}

data class EmbeddingDiagnostics(
  val modelPath: String?,
  val modelLoaded: Boolean,
  val modelInferenceOk: Boolean,
  val fallbackOnly: Boolean,
  val initError: String?,
) {
  fun toLogMessage(): String =
    "path=${modelPath ?: "none"}, loaded=$modelLoaded, inferenceOk=$modelInferenceOk, " +
      "fallbackOnly=$fallbackOnly, initError=${initError ?: "none"}"
}
