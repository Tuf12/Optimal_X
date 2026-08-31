package com.example.optimalx.data.litert

import android.content.Context
import java.io.File

data class LitertLmModelAvailability(
    val resolvedPath: String,
    val exists: Boolean,
    val canonicalPath: String,
    val validationError: String? = null,
) {
    val statusLabel: String = when {
        validationError != null -> validationError
        exists -> "Model ready (${LitertLmModelValidator.formatBytes(File(resolvedPath).length())})"
        resolvedPath.isBlank() -> "No model installed — download below (~3.7 GB)"
        else -> "Not found at configured path — download or choose a file"
    }
}

fun litertModelAvailability(context: Context, userPath: String): LitertLmModelAvailability {
    val canonical = LitertLmModelLocator.canonicalInstallFile(context).absolutePath
    val resolved = LitertLmDefaults.resolveModelPath(context, userPath)
    val file = File(resolved)
    val validation = if (file.isFile) LitertLmModelValidator.validate(file) else null
    val validationError = validation?.exceptionOrNull()?.message
    return LitertLmModelAvailability(
        resolvedPath = resolved,
        exists = file.isFile && validation?.isSuccess == true,
        canonicalPath = canonical,
        validationError = validationError,
    )
}
