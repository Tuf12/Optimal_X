package com.example.optimalx.data.litert

import android.content.Context
import android.net.Uri
import android.os.Environment
import java.io.File

data class LitertLmDiscoveredModel(
    val absolutePath: String,
    val label: String,
    val sizeBytes: Long,
)

object LitertLmModelLocator {

    fun canonicalInstallFile(context: Context): File {
        val baseDir = context.getExternalFilesDir("models")
            ?: File(context.filesDir, "models")
        if (!baseDir.exists()) baseDir.mkdirs()
        return File(baseDir, LitertLmDefaults.MODEL_FILE_NAME)
    }

    /**
     * Path used for inference: explicit user path if valid, else canonical install file if present,
     * else first discovered copy, else canonical path (for download target / error messages).
     */
    fun resolveEffectivePath(context: Context, userPath: String): String {
        val trimmed = userPath.trim()
        if (trimmed.isNotEmpty()) {
            val userFile = File(trimmed)
            if (userFile.isFile) return userFile.absolutePath
            return trimmed
        }
        val canonical = canonicalInstallFile(context)
        if (canonical.isFile) return canonical.absolutePath
        return discoverKnownModels(context).firstOrNull()?.absolutePath ?: canonical.absolutePath
    }

    fun discoverKnownModels(context: Context): List<LitertLmDiscoveredModel> {
        val seen = mutableSetOf<String>()
        val results = mutableListOf<LitertLmDiscoveredModel>()

        fun add(file: File, label: String) {
            val path = file.absolutePath
            if (!file.isFile || path in seen) return
            seen += path
            results += LitertLmDiscoveredModel(
                absolutePath = path,
                label = label,
                sizeBytes = file.length(),
            )
        }

        add(canonicalInstallFile(context), "OptimalX (recommended)")
        add(File(context.filesDir, "models/${LitertLmDefaults.MODEL_FILE_NAME}"), "OptimalX internal")

        val downloadDir = Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS)
        if (downloadDir != null) {
            add(File(downloadDir, LitertLmDefaults.MODEL_FILE_NAME), "Downloads")
            val downloadFiles = downloadDir.listFiles()
            if (downloadFiles != null) {
                downloadFiles
                    .filter { it.isFile && it.name.endsWith(".litertlm", ignoreCase = true) }
                    .forEach { add(it, "Downloads (${it.name})") }
            }
        }

        LitertLmDefaults.MODEL_PATH_CANDIDATES.forEach { path ->
            val file = File(path)
            val label = when {
                path.contains("edge.gallery") -> "Google AI Edge Gallery"
                path.contains("/data/local/tmp") -> "Dev tmp (adb)"
                else -> file.parent?.substringAfterLast('/') ?: "Other"
            }
            add(file, label)
        }

        return results.sortedByDescending { it.sizeBytes }
    }

    suspend fun copyToCanonical(
        context: Context,
        sourcePath: String,
        onProgress: (bytesCopied: Long, totalBytes: Long) -> Unit = { _, _ -> },
    ): Result<File> = LitertLmModelFileOps.copyFile(
        source = File(sourcePath),
        destination = canonicalInstallFile(context),
        onProgress = onProgress,
    )

    suspend fun importFromUri(
        context: Context,
        uri: Uri,
        onProgress: (bytesCopied: Long, totalBytes: Long) -> Unit = { _, _ -> },
    ): Result<File> = LitertLmModelFileOps.copyFromUri(
        context = context,
        uri = uri,
        destination = canonicalInstallFile(context),
        onProgress = onProgress,
    )
}
