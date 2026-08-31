package com.example.optimalx.data.litert

import android.content.Context
import android.util.Log
import androidx.work.CoroutineWorker
import androidx.work.WorkerParameters
import com.example.optimalx.data.preferences.SettingsKeys
import com.example.optimalx.data.preferences.settingsDataStore
import androidx.datastore.preferences.core.edit

class LitertLmModelDownloadWorker(
    context: Context,
    params: WorkerParameters,
) : CoroutineWorker(context, params) {

    companion object {
        const val UNIQUE_WORK_NAME = "litert_lm_model_download"
        private const val TAG = "OptimalX.LitertLmDownloadWorker"
    }

    override suspend fun doWork(): Result {
        LitertLmModelDownloadTracker.markDownloading(0L, null)
        val destination = LitertLmModelLocator.canonicalInstallFile(applicationContext)
        val downloader = LitertLmModelDownloader()
        val result = downloader.downloadTo(
            destination = destination,
            onProgress = { read, total ->
                LitertLmModelDownloadTracker.markDownloading(read, total)
            },
        )
        return result.fold(
            onSuccess = { file ->
                applicationContext.settingsDataStore.edit {
                    it[SettingsKeys.LITERT_MODEL_PATH] = file.absolutePath
                }
                LitertLmModelDownloadTracker.markComplete(file.absolutePath)
                applyLitertEngineWarmState(applicationContext)
                Log.i(TAG, "Download complete: ${file.absolutePath}")
                Result.success()
            },
            onFailure = { error ->
                LitertLmModelDownloadTracker.markFailed(
                    error.message ?: "Model download failed",
                )
                Log.e(TAG, "Download worker failed", error)
                Result.failure()
            },
        )
    }
}
