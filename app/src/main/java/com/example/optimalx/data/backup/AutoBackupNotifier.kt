package com.example.optimalx.data.backup

import android.app.backup.BackupManager
import android.content.Context
import android.util.Log
import androidx.lifecycle.DefaultLifecycleObserver
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.ProcessLifecycleOwner

/**
 * Tells Android Auto Backup that app data changed so the next scheduled pass includes
 * OptimalX (idle + Wi‑Fi, typically within ~24h). Does not force an immediate upload.
 */
object AutoBackupNotifier {

    private const val TAG = "OptimalX.AutoBackup"

    fun register(applicationContext: Context) {
        val appContext = applicationContext.applicationContext
        ProcessLifecycleOwner.get().lifecycle.addObserver(object : DefaultLifecycleObserver {
            override fun onStop(owner: LifecycleOwner) {
                notifyDataChanged(appContext)
            }
        })
    }

    fun notifyDataChanged(context: Context) {
        runCatching {
            BackupManager(context.applicationContext).dataChanged()
            Log.d(TAG, "Requested inclusion in next auto-backup pass")
        }.onFailure { e ->
            Log.w(TAG, "dataChanged() failed", e)
        }
    }
}
