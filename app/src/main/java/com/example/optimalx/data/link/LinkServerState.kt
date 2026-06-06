package com.example.optimalx.data.link

// Spec: app/docs/architecture/OPTIMALX_LINK.md §phone-link-screen.
//
// State surfaced from [LinkServer] to the OptimalX Link Compose screen. Kept as
// a plain data class with StateFlow inside the server so the UI can collect
// updates without depending on Ktor types directly.

import com.example.optimalx.data.backup.BackupManifest

/** Numeric phase the server is in. Drives the UI status row text + dot color. */
enum class LinkServerStatus {
    /** No server started yet. Initial state. */
    OFF,

    /** Server is in the middle of starting. */
    STARTING,

    /** Bound to [LinkServerSnapshot.boundAddress]:[LinkServerSnapshot.port] and serving. */
    LISTENING,

    /** Server failed to start or crashed. See [LinkServerSnapshot.lastError]. */
    ERROR,
}

/**
 * One activity-log line shown in the Link screen. The list is bounded so the
 * server never leaks memory if the user leaves the screen up indefinitely.
 */
data class LinkActivityEntry(
    val epochMs: Long,
    val message: String,
)

/**
 * Snapshot consumed by the UI. Replaced atomically on every state change. The
 * token is included verbatim — the UI decides whether to mask it.
 */
data class LinkServerSnapshot(
    val status: LinkServerStatus = LinkServerStatus.OFF,
    /** "0.0.0.0" while listening on all interfaces, or "" when stopped. */
    val boundAddress: String = "",
    val port: Int = 0,
    /** Bearer token clients must send in `Authorization: Bearer ...`. */
    val token: String = "",
    /** Last error message when [status] == [LinkServerStatus.ERROR]. */
    val lastError: String? = null,
    /** Most recent activity log lines, newest first. */
    val activity: List<LinkActivityEntry> = emptyList(),
    /** Snapshot manifest version this server speaks. Pulled from [BackupManifest.CURRENT_FORMAT_VERSION]. */
    val formatVersion: Int = BackupManifest.CURRENT_FORMAT_VERSION,
)
