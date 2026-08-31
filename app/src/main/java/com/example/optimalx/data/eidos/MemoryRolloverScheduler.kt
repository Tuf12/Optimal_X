package com.example.optimalx.data.eidos

import android.content.Context
import android.util.Log
import androidx.work.Constraints
import androidx.work.ExistingWorkPolicy
import androidx.work.NetworkType
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkManager
import java.time.Duration
import java.time.Instant
import java.time.ZoneId
import java.time.ZonedDateTime
import java.util.concurrent.TimeUnit

/**
 * Schedules [MemoryRolloverWorker] for the next local midnight (device timezone).
 * Uses WorkManager unique work so only one pending run exists; the worker re-enqueues the following night.
 */
object MemoryRolloverScheduler {

    const val UNIQUE_WORK_NAME = "com.example.optimalx.memory_rollover_midnight"
    const val LOG_TAG = "OptimalX.MemoryRollover"

    /**
     * Milliseconds from [nowMillis] in [zone] until the next local date boundary (00:00),
     * with a small floor so the job is never scheduled in the past.
     */
    fun delayMillisUntilNextLocalMidnight(
        nowMillis: Long = System.currentTimeMillis(),
        zone: ZoneId = ZoneId.systemDefault(),
    ): Long {
        val now = ZonedDateTime.ofInstant(Instant.ofEpochMilli(nowMillis), zone)
        val nextMidnight = now.toLocalDate().plusDays(1).atStartOfDay(zone)
        val diff = Duration.between(now, nextMidnight).toMillis()
        return diff.coerceAtLeast(60_000L)
    }

    /**
     * Ensures a midnight rollover is queued. Uses [ExistingWorkPolicy.KEEP] so opening the app
     * does not reset an already-scheduled run.
     */
    fun ensureScheduled(context: Context) {
        if (!EidosSystemFeatureFlags.MEMORY_ROLLOVER_ENABLED) {
            cancelScheduled(context)
            return
        }
        enqueue(context, ExistingWorkPolicy.KEEP)
    }

    /**
     * Called after a rollover attempt to queue the next local midnight.
     */
    fun scheduleFollowingMidnight(context: Context) {
        if (!EidosSystemFeatureFlags.MEMORY_ROLLOVER_ENABLED) {
            cancelScheduled(context)
            return
        }
        enqueue(context, ExistingWorkPolicy.REPLACE)
    }

    fun cancelScheduled(context: Context) {
        WorkManager.getInstance(context).cancelUniqueWork(UNIQUE_WORK_NAME)
        Log.i(LOG_TAG, "Cancelled scheduled rollover work (rollover paused or disabled)")
    }

    private fun enqueue(context: Context, policy: ExistingWorkPolicy) {
        val delay = delayMillisUntilNextLocalMidnight()
        val work = OneTimeWorkRequestBuilder<MemoryRolloverWorker>()
            .setInitialDelay(delay, TimeUnit.MILLISECONDS)
            .setConstraints(
                Constraints.Builder()
                    .setRequiredNetworkType(NetworkType.CONNECTED)
                    .build(),
            )
            .addTag("memory_rollover")
            .build()
        WorkManager.getInstance(context).enqueueUniqueWork(UNIQUE_WORK_NAME, policy, work)
        Log.i(
            LOG_TAG,
            "Enqueued rollover work (policy=$policy): initialDelayMs=$delay (~${delay / 3_600_000}h)",
        )
    }
}
