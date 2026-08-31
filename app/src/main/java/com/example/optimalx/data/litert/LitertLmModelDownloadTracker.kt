package com.example.optimalx.data.litert

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

sealed interface LitertLmModelDownloadState {
    data object Idle : LitertLmModelDownloadState

    data class Downloading(
        val bytesRead: Long,
        val totalBytes: Long?,
    ) : LitertLmModelDownloadState {
        val percent: Int? = totalBytes?.takeIf { it > 0 }?.let { total ->
            ((bytesRead * 100) / total).toInt().coerceIn(0, 100)
        }
    }

    data class Complete(val installedPath: String) : LitertLmModelDownloadState

    data class Failed(val message: String) : LitertLmModelDownloadState
}

object LitertLmModelDownloadTracker {
    private val _state = MutableStateFlow<LitertLmModelDownloadState>(LitertLmModelDownloadState.Idle)
    val state: StateFlow<LitertLmModelDownloadState> = _state.asStateFlow()

    fun markDownloading(bytesRead: Long, totalBytes: Long?) {
        _state.value = LitertLmModelDownloadState.Downloading(bytesRead, totalBytes)
    }

    fun markComplete(installedPath: String) {
        _state.value = LitertLmModelDownloadState.Complete(installedPath)
    }

    fun markFailed(message: String) {
        _state.value = LitertLmModelDownloadState.Failed(message)
    }

    fun markIdle() {
        _state.value = LitertLmModelDownloadState.Idle
    }
}
