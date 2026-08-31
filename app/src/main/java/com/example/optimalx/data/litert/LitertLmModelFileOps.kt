package com.example.optimalx.data.litert

import android.content.Context
import android.net.Uri
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream

internal object LitertLmModelFileOps {

    suspend fun copyFile(
        source: File,
        destination: File,
        onProgress: (bytesCopied: Long, totalBytes: Long) -> Unit = { _, _ -> },
    ): Result<File> = withContext(Dispatchers.IO) {
        runCatching {
            if (!source.isFile) error("Source file not found: ${source.absolutePath}")
            destination.parentFile?.mkdirs()
            val total = source.length()
            var copied = 0L
            FileInputStream(source).use { input ->
                FileOutputStream(destination).use { output ->
                    val buffer = ByteArray(DEFAULT_BUFFER_SIZE)
                    while (true) {
                        val read = input.read(buffer)
                        if (read <= 0) break
                        output.write(buffer, 0, read)
                        copied += read
                        onProgress(copied, total)
                    }
                }
            }
            destination
        }.mapCatching { file ->
            LitertLmModelValidator.validate(file).getOrThrow()
            file
        }
    }

    suspend fun copyFromUri(
        context: Context,
        uri: Uri,
        destination: File,
        onProgress: (bytesCopied: Long, totalBytes: Long) -> Unit = { _, _ -> },
    ): Result<File> = withContext(Dispatchers.IO) {
        runCatching {
            destination.parentFile?.mkdirs()
            val input = context.contentResolver.openInputStream(uri)
                ?: error("Could not open selected file")
            val total = context.contentResolver.openAssetFileDescriptor(uri, "r")
                ?.use { it.length.takeIf { len -> len > 0 } }
                ?: -1L
            var copied = 0L
            input.use { stream ->
                FileOutputStream(destination).use { output ->
                    val buffer = ByteArray(DEFAULT_BUFFER_SIZE)
                    while (true) {
                        val read = stream.read(buffer)
                        if (read <= 0) break
                        output.write(buffer, 0, read)
                        copied += read
                        onProgress(copied, if (total > 0) total else copied)
                    }
                }
            }
            destination
        }.mapCatching { file ->
            LitertLmModelValidator.validate(file).getOrThrow()
            file
        }
    }
}
