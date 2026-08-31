package com.example.optimalx.ui.settings

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.example.optimalx.OptimalXApplication
import com.example.optimalx.data.sync.SyncConflictDto
import com.example.optimalx.data.sync.SyncFileService
import com.example.optimalx.data.sync.SyncOperationResult
import com.example.optimalx.data.sync.SyncPairingUri
import com.example.optimalx.data.sync.SyncPreferences
import com.example.optimalx.data.sync.SyncService
import com.example.optimalx.data.sync.SyncCallResult
import com.example.optimalx.data.sync.WorkshopBackupAllProgress
import com.example.optimalx.data.sync.WorkshopBackupProgress
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

data class SyncWithDesktopUiState(
    val host: String = "",
    val port: String = SyncPairingUri.DEFAULT_PORT.toString(),
    val token: String = "",
    val lastSyncAt: Long = 0L,
    val deviceId: String = "",
    val busy: Boolean = false,
    val statusMessage: String? = null,
    val errorMessage: String? = null,
    val lastApplied: Map<String, Int> = emptyMap(),
    val lastSkipped: Map<String, Int> = emptyMap(),
    val conflicts: List<SyncConflictDto> = emptyList(),
    val workshopBackupProgress: WorkshopBackupAllProgress? = null,
    val attachmentBackupProgress: WorkshopBackupProgress? = null,
)

class SyncWithDesktopViewModel(application: Application) : AndroidViewModel(application) {

    private val app = application as OptimalXApplication
    private val preferences = SyncPreferences(application)
    private val syncService = SyncService(application, app.database)
    private val syncFileService = SyncFileService(application, app.database)

    private val _uiState = MutableStateFlow(SyncWithDesktopUiState())
    val uiState: StateFlow<SyncWithDesktopUiState> = _uiState.asStateFlow()

    init {
        viewModelScope.launch {
            preferences.ensureDeviceId()
            val withDevice = preferences.readSnapshot()
            _uiState.value = SyncWithDesktopUiState(
                host = withDevice.host,
                port = withDevice.port.toString(),
                token = withDevice.token,
                lastSyncAt = withDevice.lastSyncAt,
                deviceId = withDevice.deviceId,
            )
            preferences.snapshot.collect { snapshot ->
                _uiState.update {
                    it.copy(
                        lastSyncAt = snapshot.lastSyncAt,
                        deviceId = snapshot.deviceId.ifBlank { it.deviceId },
                    )
                }
            }
        }
    }

    fun setHost(value: String) = _uiState.update { it.copy(host = value, errorMessage = null) }

    fun setPort(value: String) = _uiState.update { it.copy(port = value, errorMessage = null) }

    fun setToken(value: String) = _uiState.update { it.copy(token = value, errorMessage = null) }

    fun applyPairingUri(raw: String) {
        viewModelScope.launch {
            if (syncService.applyPairingUri(raw.trim())) {
                val snapshot = preferences.readSnapshot()
                _uiState.update {
                    it.copy(
                        host = snapshot.host,
                        port = snapshot.port.toString(),
                        token = snapshot.token,
                        statusMessage = "Paired from URI",
                        errorMessage = null,
                    )
                }
            } else {
                _uiState.update { it.copy(errorMessage = "Invalid optimalx-sync:// URI") }
            }
        }
    }

    fun testConnection() = runOperation { syncService.testConnection(currentHost(), currentPort(), currentToken()) }

    fun push() {
        viewModelScope.launch {
            _uiState.update {
                it.copy(
                    busy = true,
                    errorMessage = null,
                    statusMessage = null,
                    workshopBackupProgress = null,
                    attachmentBackupProgress = null,
                )
            }
            preferences.saveDraft(currentHost(), currentPort(), currentToken())
            val lastSyncAt = preferences.readSnapshot().lastSyncAt
            val pushResult = try {
                syncService.push()
            } catch (t: Throwable) {
                SyncOperationResult.Failure(t.message ?: t::class.java.simpleName)
            }
            when (pushResult) {
                is SyncOperationResult.Failure ->
                    _uiState.update { it.copy(busy = false, errorMessage = pushResult.message) }
                is SyncOperationResult.Success -> {
                    when (
                        val attachResult = syncFileService.pushAttachmentsToDesktop(lastSyncAt) { progress ->
                            _uiState.update { state -> state.copy(attachmentBackupProgress = progress) }
                        }
                    ) {
                        is SyncCallResult.Failure ->
                            _uiState.update {
                                it.copy(
                                    busy = false,
                                    errorMessage = attachResult.message,
                                    attachmentBackupProgress = null,
                                )
                            }
                        is SyncCallResult.Success -> {
                            val snapshot = preferences.readSnapshot()
                            val attachSummary = attachResult.value
                            val attachNote = buildString {
                                when {
                                    attachSummary.filesUploaded == 0 && attachSummary.filesSkipped > 0 -> {
                                        append(" No attachment bytes uploaded (")
                                        append(attachSummary.filesSkipped)
                                        append(" empty file(s) skipped).")
                                    }
                                    attachSummary.filesUploaded == 0 ->
                                        append(" No local attachment bytes to upload.")
                                    else -> {
                                        append(" Uploaded ")
                                        append(attachSummary.filesUploaded)
                                        append(" attachment(s) to PC.")
                                        if (attachSummary.filesSkipped > 0) {
                                            append(" (")
                                            append(attachSummary.filesSkipped)
                                            append(" empty skipped)")
                                        }
                                    }
                                }
                            }
                            _uiState.update {
                                it.copy(
                                    host = snapshot.host.ifBlank { it.host },
                                    port = snapshot.port.toString(),
                                    token = snapshot.token.ifBlank { it.token },
                                    busy = false,
                                    statusMessage = pushResult.message + attachNote,
                                    errorMessage = null,
                                    lastApplied = pushResult.applied,
                                    lastSkipped = pushResult.skipped,
                                    conflicts = pushResult.conflicts,
                                    lastSyncAt = snapshot.lastSyncAt,
                                    attachmentBackupProgress = null,
                                )
                            }
                        }
                    }
                }
            }
        }
    }

    fun pull() = runOperation { syncService.pull(full = false) }

    fun pullFull() = runOperation { syncService.pull(full = true) }

    fun pushAllAttachments() {
        viewModelScope.launch {
            _uiState.update {
                it.copy(
                    busy = true,
                    errorMessage = null,
                    statusMessage = null,
                    workshopBackupProgress = null,
                    attachmentBackupProgress = null,
                )
            }
            preferences.saveDraft(currentHost(), currentPort(), currentToken())
            when (
                val result = syncFileService.pushAttachmentsToDesktop(since = 0L) { progress ->
                    _uiState.update { state -> state.copy(attachmentBackupProgress = progress) }
                }
            ) {
                is SyncCallResult.Failure ->
                    _uiState.update {
                        it.copy(busy = false, errorMessage = result.message, attachmentBackupProgress = null)
                    }
                is SyncCallResult.Success -> {
                    val summary = result.value
                    val message = when {
                        summary.filesUploaded == 0 && summary.filesSkipped > 0 ->
                            "No attachment bytes uploaded — ${summary.filesSkipped} empty file(s) skipped. Re-import the files on your phone."
                        summary.filesUploaded == 0 ->
                            "No local attachment files to upload. Run Tier 1 Push first if metadata is missing on the PC."
                        else -> buildString {
                            append("Attachment sync complete — ")
                            append(summary.filesUploaded)
                            append(" file(s) uploaded to PC.")
                            if (summary.filesSkipped > 0) {
                                append(" (")
                                append(summary.filesSkipped)
                                append(" empty skipped)")
                            }
                        }
                    }
                    _uiState.update {
                        it.copy(
                            busy = false,
                            statusMessage = message,
                            errorMessage = null,
                            attachmentBackupProgress = null,
                        )
                    }
                }
            }
        }
    }

    fun backupAllWorkshops() {
        viewModelScope.launch {
            _uiState.update {
                it.copy(
                    busy = true,
                    errorMessage = null,
                    statusMessage = null,
                    workshopBackupProgress = null,
                    attachmentBackupProgress = null,
                )
            }
            preferences.saveDraft(currentHost(), currentPort(), currentToken())
            when (
                val result = syncFileService.backupAllMobileWorkshopsToDesktop { progress ->
                    _uiState.update { state -> state.copy(workshopBackupProgress = progress) }
                }
            ) {
                is SyncCallResult.Failure ->
                    _uiState.update {
                        it.copy(busy = false, errorMessage = result.message, workshopBackupProgress = null)
                    }
                is SyncCallResult.Success -> {
                    val summary = result.value
                    val message = when {
                        summary.projectsBackedUp == 0 && summary.projectsSkippedEmpty == 0 ->
                            "No Panel Workshop projects found to back up."
                        summary.filesUploaded == 0 ->
                            "No workshop files on device to back up."
                        else -> buildString {
                            append("Workshop backup complete — ")
                            append(summary.projectsBackedUp)
                            append(" project(s), ")
                            append(summary.filesUploaded)
                            append(" file(s)")
                            if (summary.projectsSkippedEmpty > 0) {
                                append(" (")
                                append(summary.projectsSkippedEmpty)
                                append(" empty skipped)")
                            }
                        }
                    }
                    _uiState.update {
                        it.copy(
                            busy = false,
                            statusMessage = message,
                            errorMessage = null,
                            workshopBackupProgress = null,
                        )
                    }
                }
            }
        }
    }

    fun resolveConflict(conflict: SyncConflictDto, keepLocal: Boolean) {
        runOperation { syncService.resolveConflict(conflict, keepLocal) }
    }

    private fun runOperation(block: suspend () -> SyncOperationResult) {
        viewModelScope.launch {
            _uiState.update { it.copy(busy = true, errorMessage = null, workshopBackupProgress = null, attachmentBackupProgress = null) }
            preferences.saveDraft(currentHost(), currentPort(), currentToken())
            val result = try {
                block()
            } catch (t: Throwable) {
                SyncOperationResult.Failure(t.message ?: t::class.java.simpleName)
            }
            when (result) {
                is SyncOperationResult.Failure ->
                    _uiState.update { it.copy(busy = false, errorMessage = result.message) }
                is SyncOperationResult.Success -> {
                    val snapshot = preferences.readSnapshot()
                    _uiState.update {
                        it.copy(
                            host = snapshot.host.ifBlank { it.host },
                            port = snapshot.port.toString(),
                            token = snapshot.token.ifBlank { it.token },
                            busy = false,
                            statusMessage = result.message,
                            errorMessage = null,
                            lastApplied = result.applied,
                            lastSkipped = result.skipped,
                            conflicts = result.conflicts,
                            lastSyncAt = snapshot.lastSyncAt,
                        )
                    }
                }
            }
        }
    }

    private fun currentHost(): String = _uiState.value.host

    private fun currentPort(): Int =
        _uiState.value.port.toIntOrNull() ?: SyncPairingUri.DEFAULT_PORT

    private fun currentToken(): String = _uiState.value.token
}
