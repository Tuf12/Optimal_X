package com.example.optimalx.data.litert

import android.content.Context
import java.io.File

object LitertLmDefaults {
    const val PROVIDER_ID = "local"

    const val MODEL_FILE_NAME = "gemma-4-E4B-it.litertlm"

    /** Hugging Face repo for Gemma 4 E4B LiteRT-LM (Gallery-compatible). */
    const val MODEL_HF_REPO = "litert-community/gemma-4-E4B-it-litert-lm"

    const val MODEL_DOWNLOAD_URL = "https://huggingface.co/$MODEL_HF_REPO"

    /** Direct resolve URL for in-app download (~3.7 GB). */
    const val MODEL_DIRECT_DOWNLOAD_URL =
        "https://huggingface.co/$MODEL_HF_REPO/resolve/main/$MODEL_FILE_NAME"

    const val MODEL_SIZE_BYTES_APPROX = 3_659_530_240L

    const val DEFAULT_SMOKE_PROMPT = "What is the capital of France?"

    /** Legacy / Gallery paths scanned by [LitertLmModelLocator.discoverKnownModels]. */
    val MODEL_PATH_CANDIDATES: List<String> = listOf(
        "/data/local/tmp/llm/$MODEL_FILE_NAME",
        "/data/user/0/com.google.ai.edge.gallery/files/models/$MODEL_FILE_NAME",
        "/sdcard/Android/data/com.google.ai.edge.gallery/files/models/$MODEL_FILE_NAME",
        "/storage/emulated/0/Android/data/com.google.ai.edge.gallery/files/models/$MODEL_FILE_NAME",
    )

    fun firstExistingModelPath(context: Context): String? =
        LitertLmModelLocator.discoverKnownModels(context).firstOrNull()?.absolutePath

    fun resolveModelPath(context: Context, userPath: String): String =
        LitertLmModelLocator.resolveEffectivePath(context, userPath.trim())

    /** Legacy scan without Context (instrumented tests / smoke UI may use [resolveModelPath] with Context). */
    fun resolveModelPath(userPath: String): String {
        val trimmed = userPath.trim()
        if (trimmed.isNotEmpty()) return trimmed
        return MODEL_PATH_CANDIDATES.firstOrNull { File(it).isFile } ?: MODEL_PATH_CANDIDATES.first()
    }
}
