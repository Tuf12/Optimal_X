package com.example.optimalx.data.model

import androidx.room.Entity
import androidx.room.ForeignKey
import androidx.room.Index
import androidx.room.PrimaryKey

/** One user send (may include multiple provider HTTP rounds / tool loops). */
@Entity(
    tableName = "eidos_api_trace_runs",
    indices = [
        Index(value = ["directoryKey", "startedAtMillis"]),
        Index(value = ["conversationId"]),
    ],
)
data class EidosApiTraceRun(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val conversationId: Long?,
    val conversationTitle: String,
    val directoryKey: String,
    val directoryLabel: String,
    val scopeType: String,
    val provider: String,
    val userMessagePreview: String,
    val resolvedProfileId: String = "",
    val entrySurface: String = "",
    val toolAllowlistHash: String = "",
    val stablePrefixSha256: String = "",
    val sectionCharCountsJson: String = "{}",
    val startedAtMillis: Long,
    val finishedAtMillis: Long? = null,
    val roundCount: Int = 0,
    val status: String,
)

/** One HTTP call to the LLM provider within a [EidosApiTraceRun]. */
@Entity(
    tableName = "eidos_api_trace_rounds",
    foreignKeys = [
        ForeignKey(
            entity = EidosApiTraceRun::class,
            parentColumns = ["id"],
            childColumns = ["runId"],
            onDelete = ForeignKey.CASCADE,
        ),
    ],
    indices = [Index(value = ["runId", "roundIndex"])],
)
data class EidosApiTraceRound(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val runId: Long,
    val roundIndex: Int,
    val phase: String,
    val requestJson: String,
    val responseJson: String,
    val recordedAtMillis: Long,
)

data class EidosApiTraceDirectorySummary(
    val directoryKey: String,
    val directoryLabel: String,
    val runCount: Int,
    val lastStartedAtMillis: Long,
)
