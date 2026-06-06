package com.example.optimalx.data.link

// Spec: app/docs/architecture/OPTIMALX_LINK.md §desktop-client (the phone's
// view of the desktop).
//
// HTTP client the phone uses to push / pull snapshots against the desktop
// app's own embedded server (default port 17833, exposed only on
// 127.0.0.1 from the desktop side and on the LAN once the desktop is
// reachable from the phone's Wi-Fi).
//
// The desktop and phone protocols are intentionally asymmetric: the phone
// streams snapshots out via `/v1/bundles/full`, while the desktop accepts
// snapshots via `POST /v1/snapshots` and serves them back via
// `GET /v1/snapshots/{id}/bundle`. This client wraps the desktop side so
// the LinkScreen can call it without knowing about Java HTTP plumbing.

import android.util.Log
import com.example.optimalx.data.backup.OptimalXBackupManager
import java.io.File
import java.io.FileOutputStream
import java.net.HttpURLConnection
import java.net.URL
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

/**
 * Connection coordinates the phone uses to reach the desktop. All three
 * fields are user-provided on the LinkScreen "Connect to desktop" form;
 * none are persisted yet (Phase 5 / settings polish will store the most
 * recent successful triple).
 */
data class DesktopEndpoint(
    val host: String,
    val port: Int,
    val token: String,
)

/** Outcome of a desktop call. UI surfaces [message] directly. */
sealed interface DesktopCallResult {
    data class Success(val message: String) : DesktopCallResult
    data class Failure(val message: String) : DesktopCallResult
}

/**
 * Wraps `HttpURLConnection` to keep the dependency surface tiny — Ktor
 * client would also work but the phone already pays for `HttpURLConnection`
 * via Android's runtime. Kept package-private-friendly with a single
 * dependency on [OptimalXBackupManager.writeSnapshotTo] so this class is
 * straightforward to unit-test against a local desktop fake.
 */
class DesktopClient(
    private val context: android.content.Context,
    private val appVersionName: String,
) {

    /**
     * Stream the phone's current full snapshot to the desktop's
     * `POST /v1/snapshots` endpoint. Body is `application/zip` produced
     * on the fly by [OptimalXBackupManager.writeSnapshotTo].
     */
    fun pushSnapshot(endpoint: DesktopEndpoint): DesktopCallResult {
        val url = endpoint.buildUrl("/v1/snapshots")
        return try {
            val conn = url.openConnectionWithTimeouts()
            conn.requestMethod = "POST"
            conn.doOutput = true
            conn.setRequestProperty("Authorization", "Bearer ${endpoint.token}")
            conn.setRequestProperty("Content-Type", "application/zip")
            conn.setChunkedStreamingMode(64 * 1024)
            conn.outputStream.use { sink ->
                OptimalXBackupManager.writeSnapshotTo(
                    context = context,
                    appVersionName = appVersionName,
                    out = sink,
                )
            }
            val code = conn.responseCode
            if (code in 200..299) {
                val body = conn.inputStream.bufferedReader().use { it.readText() }
                DesktopCallResult.Success("Pushed snapshot: $body")
            } else {
                val err = conn.errorStream?.bufferedReader()?.use { it.readText() } ?: ""
                DesktopCallResult.Failure("HTTP $code: $err")
            }
        } catch (t: Throwable) {
            Log.w(TAG, "pushSnapshot failed", t)
            DesktopCallResult.Failure(t.message ?: t::class.java.simpleName)
        }
    }

    /**
     * Fetch the desktop's snapshot index. The Link screen uses this to
     * populate a picker so the user can choose which snapshot to import
     * back to the phone. The desktop returns a top-level JSON array; we
     * decode straight into [DesktopSnapshotEntry] list without a wrapper.
     */
    fun listSnapshots(endpoint: DesktopEndpoint): DesktopListResult {
        val url = endpoint.buildUrl("/v1/snapshots")
        return try {
            val conn = url.openConnectionWithTimeouts()
            conn.requestMethod = "GET"
            conn.setRequestProperty("Authorization", "Bearer ${endpoint.token}")
            val code = conn.responseCode
            if (code in 200..299) {
                val body = conn.inputStream.bufferedReader().use { it.readText() }
                val items = json.decodeFromString<List<DesktopSnapshotEntry>>(body)
                DesktopListResult.Success(items)
            } else {
                val err = conn.errorStream?.bufferedReader()?.use { it.readText() } ?: ""
                DesktopListResult.Failure("HTTP $code: $err")
            }
        } catch (t: Throwable) {
            Log.w(TAG, "listSnapshots failed", t)
            DesktopListResult.Failure(t.message ?: t::class.java.simpleName)
        }
    }

    /**
     * Download a specific snapshot bundle from the desktop into the
     * phone's cache. The caller (LinkScreen) hands the file to
     * [com.example.optimalx.data.backup.OptimalXBackupManager.validateZipForRestore]
     * and routes through the standard confirm flow, so the user explicitly
     * approves the swap even when they themselves initiated the import.
     *
     * @return the cached file path on success.
     */
    fun downloadSnapshot(
        endpoint: DesktopEndpoint,
        snapshotId: String,
        targetFile: File,
    ): DesktopCallResult {
        val url = endpoint.buildUrl("/v1/snapshots/$snapshotId/bundle")
        return try {
            val conn = url.openConnectionWithTimeouts()
            conn.requestMethod = "GET"
            conn.setRequestProperty("Authorization", "Bearer ${endpoint.token}")
            val code = conn.responseCode
            if (code !in 200..299) {
                val err = conn.errorStream?.bufferedReader()?.use { it.readText() } ?: ""
                return DesktopCallResult.Failure("HTTP $code: $err")
            }
            targetFile.parentFile?.mkdirs()
            conn.inputStream.use { src ->
                FileOutputStream(targetFile).use { dst ->
                    src.copyTo(dst, bufferSize = 64 * 1024)
                }
            }
            DesktopCallResult.Success("Downloaded ${targetFile.length()} bytes to ${targetFile.absolutePath}")
        } catch (t: Throwable) {
            Log.w(TAG, "downloadSnapshot failed", t)
            DesktopCallResult.Failure(t.message ?: t::class.java.simpleName)
        }
    }

    /**
     * Cheap reachability check used by the LinkScreen "Test connection"
     * button. Hits `GET /v1/status` and returns the parsed body.
     */
    fun ping(endpoint: DesktopEndpoint): DesktopPingResult {
        val url = endpoint.buildUrl("/v1/status")
        return try {
            val conn = url.openConnectionWithTimeouts()
            conn.requestMethod = "GET"
            conn.setRequestProperty("Authorization", "Bearer ${endpoint.token}")
            val code = conn.responseCode
            if (code in 200..299) {
                val body = conn.inputStream.bufferedReader().use { it.readText() }
                DesktopPingResult.Success(json.decodeFromString(body))
            } else {
                DesktopPingResult.Failure("HTTP $code")
            }
        } catch (t: Throwable) {
            DesktopPingResult.Failure(t.message ?: t::class.java.simpleName)
        }
    }

    private fun URL.openConnectionWithTimeouts(): HttpURLConnection {
        val conn = openConnection() as HttpURLConnection
        conn.connectTimeout = CONNECT_TIMEOUT_MS
        conn.readTimeout = READ_TIMEOUT_MS
        return conn
    }

    private fun DesktopEndpoint.buildUrl(path: String): URL {
        val safeHost = host.ifBlank { "127.0.0.1" }
        return URL("http", safeHost, port, path)
    }

    companion object {
        private const val TAG = "DesktopClient"
        private const val CONNECT_TIMEOUT_MS = 5_000
        private const val READ_TIMEOUT_MS = 30_000
        private val json = Json {
            ignoreUnknownKeys = true
            isLenient = true
        }
    }
}

/**
 * JSON shape returned by the desktop's `GET /v1/snapshots`. Mirrors
 * `desktop/optimalx_link/snapshot_lib.py::SnapshotEntry.to_json`. Unknown
 * keys are ignored so adding fields desktop-side never breaks the phone.
 */
@Serializable
data class DesktopSnapshotEntry(
    val id: String,
    val epochMs: Long = 0L,
    val appVersion: String = "",
    val dbVersion: Int = 0,
    val formatVersion: Int = 0,
    val sizeBytes: Long = 0L,
    val workshopProjectCount: Int = 0,
    val attachmentCount: Int = 0,
)

sealed interface DesktopListResult {
    data class Success(val snapshots: List<DesktopSnapshotEntry>) : DesktopListResult
    data class Failure(val message: String) : DesktopListResult
}

sealed interface DesktopPingResult {
    data class Success(val status: DesktopStatusPayload) : DesktopPingResult
    data class Failure(val message: String) : DesktopPingResult
}

/**
 * Shape of the desktop's `/v1/status` body. Mirrors
 * `desktop/optimalx_link/server.py::handle_status`.
 */
@Serializable
data class DesktopStatusPayload(
    val linkVersion: Int = 0,
    val snapshotCount: Int = 0,
    val latestSnapshotEpochMs: Long = 0L,
)
