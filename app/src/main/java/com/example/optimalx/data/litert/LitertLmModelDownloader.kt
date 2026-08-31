package com.example.optimalx.data.litert

import android.util.Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.File
import java.io.FileOutputStream
import java.util.concurrent.TimeUnit

class LitertLmModelDownloader(
    private val client: OkHttpClient = OkHttpClient.Builder()
        .connectTimeout(60, TimeUnit.SECONDS)
        .readTimeout(10, TimeUnit.MINUTES)
        .writeTimeout(10, TimeUnit.MINUTES)
        .callTimeout(0, TimeUnit.MINUTES)
        .build(),
) {
    companion object {
        private const val TAG = "OptimalX.LitertLmDownload"
    }

    suspend fun downloadTo(
        destination: File,
        onProgress: (bytesRead: Long, totalBytes: Long?) -> Unit,
    ): Result<File> = withContext(Dispatchers.IO) {
        runCatching {
            destination.parentFile?.mkdirs()
            val temp = File(destination.parent, "${destination.name}.download")
            if (temp.exists()) temp.delete()

            val request = Request.Builder()
                .url(LitertLmDefaults.MODEL_DIRECT_DOWNLOAD_URL)
                .header("Accept", "application/octet-stream")
                .build()

            client.newCall(request).execute().use { response ->
                if (!response.isSuccessful) {
                    error("Download failed (${response.code}): ${response.message}")
                }
                val body = response.body ?: error("Empty download response")
                val total = body.contentLength().takeIf { it > 0 }
                var readTotal = 0L
                body.byteStream().use { input ->
                    FileOutputStream(temp).use { output ->
                        val buffer = ByteArray(DEFAULT_BUFFER_SIZE)
                        while (true) {
                            val read = input.read(buffer)
                            if (read <= 0) break
                            output.write(buffer, 0, read)
                            readTotal += read
                            onProgress(readTotal, total)
                        }
                    }
                }
            }

            if (destination.exists()) destination.delete()
            if (!temp.renameTo(destination)) {
                temp.copyTo(destination, overwrite = true)
                temp.delete()
            }
            LitertLmModelValidator.validate(destination).getOrElse { error ->
                destination.delete()
                temp.delete()
                throw error
            }
            Log.i(TAG, "Model installed at ${destination.absolutePath} (${destination.length()} bytes)")
            destination
        }.onFailure { error ->
            Log.e(TAG, "Model download failed", error)
            runCatching {
                val partial = File(destination.parent, "${destination.name}.download")
                if (partial.exists()) partial.delete()
                if (destination.exists() && destination.length() < LitertLmModelValidator.MIN_BYTES) {
                    destination.delete()
                }
            }
        }
    }
}
