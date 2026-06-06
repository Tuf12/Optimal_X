package com.example.optimalx.data.eidos

import android.content.Context
import android.util.Log
import androidx.work.CoroutineWorker
import androidx.work.WorkerParameters
import com.example.optimalx.OptimalXApplication
import java.time.Instant
import java.time.ZoneId
import java.time.ZonedDateTime

/**
 * Runs [MemoryRolloverService] at (approximately) local midnight, then schedules the next night.
 *
 * Uses a logical timestamp of **last instant of the previous local calendar day** so Daily Memory
 * resolves to the day that just ended when the worker fires right after midnight.
 */
class MemoryRolloverWorker(
    context: Context,
    params: WorkerParameters,
) : CoroutineWorker(context, params) {

    override suspend fun doWork(): Result {
        val app = applicationContext as? OptimalXApplication
        if (app == null) {
            Log.e(MemoryRolloverScheduler.LOG_TAG, "Application context is not OptimalXApplication")
            return Result.failure()
        }
        val zone = ZoneId.systemDefault()
        val rolloverTimestamp = ZonedDateTime.now(zone)
            .toLocalDate()
            .atStartOfDay(zone)
            .toInstant()
            .toEpochMilli() - 1L

        Log.i(
            MemoryRolloverScheduler.LOG_TAG,
            "Worker started at wallClock=${Instant.now()} rolloverTimestamp=$rolloverTimestamp (local day boundary - 1ms)",
        )

        return try {
            val result = app.memoryRolloverService.runMemoryRollover(timestamp = rolloverTimestamp)
            Log.i(
                MemoryRolloverScheduler.LOG_TAG,
                "Rollover finished: status=${result.status} dailyCleared=${result.dailyCleared} message=${result.message}",
            )
            MemoryRolloverScheduler.scheduleFollowingMidnight(applicationContext)
            Result.success()
        } catch (t: Throwable) {
            Log.e(MemoryRolloverScheduler.LOG_TAG, "Rollover worker threw; WorkManager will retry", t)
            Result.retry()
        }
    }
}
