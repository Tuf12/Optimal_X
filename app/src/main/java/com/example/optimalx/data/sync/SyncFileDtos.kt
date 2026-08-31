package com.example.optimalx.data.sync

import kotlinx.serialization.Serializable

@Serializable
data class SyncFilePushResponse(
    val ok: Boolean = true,
    val kind: String? = null,
    val globalId: String? = null,
    val path: String? = null,
    val bytesWritten: Long = 0,
)

data class WorkshopBackupProgress(
    val totalFiles: Int,
    val uploadedFiles: Int,
    val currentPath: String?,
)

data class WorkshopBackupSummary(
    val filesUploaded: Int,
    val bytesUploaded: Long,
    val filesSkipped: Int = 0,
)

data class WorkshopBackupAllProgress(
    val projectsTotal: Int,
    val projectsCompleted: Int,
    val currentProjectName: String?,
    val currentFileTotal: Int,
    val currentFileUploaded: Int,
    val currentPath: String?,
)

data class WorkshopBackupAllSummary(
    val projectsBackedUp: Int,
    val projectsSkippedEmpty: Int,
    val filesUploaded: Int,
    val bytesUploaded: Long,
)
