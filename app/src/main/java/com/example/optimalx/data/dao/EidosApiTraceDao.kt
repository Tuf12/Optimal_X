package com.example.optimalx.data.dao

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.Query
import androidx.room.Transaction
import com.example.optimalx.data.model.EidosApiTraceDirectorySummary
import com.example.optimalx.data.model.EidosApiTraceRound
import com.example.optimalx.data.model.EidosApiTraceRun

@Dao
interface EidosApiTraceDao {

    @Insert
    suspend fun insertRun(run: EidosApiTraceRun): Long

    @Insert
    suspend fun insertRound(round: EidosApiTraceRound): Long

    @Query("UPDATE eidos_api_trace_runs SET roundCount = :count WHERE id = :runId")
    suspend fun updateRunRoundCount(runId: Long, count: Int)

    @Query(
        """
        UPDATE eidos_api_trace_runs
        SET finishedAtMillis = :finishedAt, status = :status, roundCount = :roundCount
        WHERE id = :runId
        """,
    )
    suspend fun finishRun(runId: Long, finishedAt: Long, status: String, roundCount: Int)

    @Query(
        """
        SELECT directoryKey AS directoryKey,
               directoryLabel AS directoryLabel,
               COUNT(*) AS runCount,
               MAX(startedAtMillis) AS lastStartedAtMillis
        FROM eidos_api_trace_runs
        GROUP BY directoryKey, directoryLabel
        ORDER BY lastStartedAtMillis DESC
        """,
    )
    suspend fun listDirectorySummaries(): List<EidosApiTraceDirectorySummary>

    @Query(
        """
        SELECT * FROM eidos_api_trace_runs
        WHERE directoryKey = :directoryKey
        ORDER BY startedAtMillis DESC
        LIMIT :limit
        """,
    )
    suspend fun listRunsForDirectory(directoryKey: String, limit: Int = 200): List<EidosApiTraceRun>

    @Query("SELECT * FROM eidos_api_trace_runs WHERE id = :runId LIMIT 1")
    suspend fun getRun(runId: Long): EidosApiTraceRun?

    @Query(
        """
        SELECT * FROM eidos_api_trace_rounds
        WHERE runId = :runId
        ORDER BY roundIndex ASC
        """,
    )
    suspend fun listRoundsForRun(runId: Long): List<EidosApiTraceRound>

    @Query("DELETE FROM eidos_api_trace_rounds WHERE id = :roundId")
    suspend fun deleteRound(roundId: Long)

    @Query("DELETE FROM eidos_api_trace_runs WHERE id = :runId")
    suspend fun deleteRun(runId: Long)

    @Query("DELETE FROM eidos_api_trace_runs WHERE directoryKey = :directoryKey")
    suspend fun deleteRunsInDirectory(directoryKey: String)

    @Query("DELETE FROM eidos_api_trace_runs")
    suspend fun deleteAllRuns()

    @Query("SELECT COUNT(*) FROM eidos_api_trace_runs")
    suspend fun countRuns(): Int

    @Query(
        """
        SELECT id FROM eidos_api_trace_runs
        ORDER BY startedAtMillis ASC
        LIMIT :count
        """,
    )
    suspend fun oldestRunIds(count: Int): List<Long>

    @Transaction
    suspend fun deleteRunsByIds(ids: List<Long>) {
        ids.forEach { deleteRun(it) }
    }
}
