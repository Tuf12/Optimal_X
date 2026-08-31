package com.example.optimalx.data.litert

import java.io.File

object LitertLmModelValidator {

    /** Reject obviously truncated downloads (HF LFS pointer ≈ 135 bytes). */
    const val MIN_BYTES = 3_000_000_000L

    fun validate(file: File): Result<Unit> {
        if (!file.isFile) {
            return Result.failure(IllegalArgumentException("Model file not found: ${file.absolutePath}"))
        }
        if (file.name.endsWith(".download", ignoreCase = true)) {
            return Result.failure(
                IllegalStateException("Model download is incomplete (${file.name}). Delete it and download again."),
            )
        }
        if (file.name.contains("-web.", ignoreCase = true)) {
            return Result.failure(
                IllegalArgumentException(
                    "Wrong model variant: ${file.name}. OptimalX needs ${LitertLmDefaults.MODEL_FILE_NAME} " +
                        "(not gemma-4-E4B-it-web.litertlm).",
                ),
            )
        }
        val size = file.length()
        if (size < MIN_BYTES) {
            return Result.failure(
                IllegalStateException(
                    "Model file looks incomplete (${formatBytes(size)}). " +
                        "Expected about ${formatBytes(LitertLmDefaults.MODEL_SIZE_BYTES_APPROX)}. " +
                        "Re-download from Settings → Local Gemma.",
                ),
            )
        }
        return Result.success(Unit)
    }

    fun formatBytes(bytes: Long): String = when {
        bytes >= 1_000_000_000 -> "%.2f GB".format(bytes.toDouble() / 1_000_000_000.0)
        bytes >= 1_000_000 -> "%.1f MB".format(bytes.toDouble() / 1_000_000.0)
        else -> "$bytes B"
    }
}
