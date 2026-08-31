package com.example.optimalx.data.litert

import android.content.Context
import androidx.work.Constraints
import androidx.work.ExistingWorkPolicy
import androidx.work.NetworkType
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkInfo

object LitertLmModelDownloadScheduler {

    fun enqueueDownload(context: Context) {
        val constraints = Constraints.Builder()
            .setRequiredNetworkType(NetworkType.CONNECTED)
            .build()
        val request = OneTimeWorkRequestBuilder<LitertLmModelDownloadWorker>()
            .setConstraints(constraints)
            .build()
        WorkManager.getInstance(context).enqueueUniqueWork(
            LitertLmModelDownloadWorker.UNIQUE_WORK_NAME,
            ExistingWorkPolicy.KEEP,
            request,
        )
    }

    fun isDownloadRunning(context: Context): Boolean {
        val info = WorkManager.getInstance(context)
            .getWorkInfosForUniqueWork(LitertLmModelDownloadWorker.UNIQUE_WORK_NAME)
            .get()
        return info.any { it.state == WorkInfo.State.RUNNING || it.state == WorkInfo.State.ENQUEUED }
    }
}
