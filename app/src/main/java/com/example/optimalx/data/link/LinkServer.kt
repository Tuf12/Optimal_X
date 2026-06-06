package com.example.optimalx.data.link

// Spec: app/docs/architecture/OPTIMALX_LINK.md §http-endpoint-surface.
//
// Phone-side embedded HTTP server. Hosts `/v1/status` and `/v1/bundles/full`
// from the OptimalX Link screen. The server is **scoped to that screen's
// lifecycle** — no foreground service, no notification. Constructed once per
// `LinkScreen` entry; [start] and [stop] are idempotent.
//
// All routing logic is installed via [installRoutes] so that JVM tests can
// drive the same module without spinning up a real socket (see
// `app/src/test/java/.../LinkServerRoutesTest.kt`).

import android.content.Context
import com.example.optimalx.data.backup.BackupManifest
import com.example.optimalx.data.backup.OptimalXBackupManager
import com.example.optimalx.data.backup.SnapshotStats
import io.ktor.http.HttpStatusCode
import io.ktor.serialization.kotlinx.json.json
import io.ktor.server.application.Application
import io.ktor.server.application.install
import io.ktor.server.auth.Authentication
import io.ktor.server.auth.UserIdPrincipal
import io.ktor.server.auth.authenticate
import io.ktor.server.auth.bearer
import io.ktor.server.cio.CIO
import io.ktor.server.engine.EmbeddedServer
import io.ktor.server.engine.embeddedServer
import io.ktor.server.plugins.contentnegotiation.ContentNegotiation
import io.ktor.server.plugins.statuspages.StatusPages
import io.ktor.server.request.receiveChannel
import io.ktor.server.response.header
import io.ktor.server.response.respond
import io.ktor.server.response.respondOutputStream
import io.ktor.server.routing.get
import io.ktor.server.routing.post
import io.ktor.server.routing.routing
import io.ktor.utils.io.jvm.javaio.copyTo
import java.io.File
import java.io.OutputStream
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.withContext
import kotlinx.serialization.Serializable

/**
 * Default listen port. Picked from the IANA dynamic range; avoid 8080/3000 so
 * collisions with other dev servers on the user's network are unlikely.
 */
const val LINK_DEFAULT_PORT: Int = 17832

/** Bound on the in-memory activity log so the server never grows without limit. */
private const val ACTIVITY_LOG_LIMIT: Int = 50

/**
 * Bearer realm string. Returned to clients in `WWW-Authenticate` headers when
 * they hit a protected endpoint without credentials.
 */
const val LINK_BEARER_REALM: String = "OptimalX Link"

class LinkServer(
    private val context: Context,
    private val appVersionName: String,
    private val port: Int = LINK_DEFAULT_PORT,
    private val tokenProvider: () -> String = LinkAuthToken::generate,
) {
    private val _state = MutableStateFlow(
        LinkServerSnapshot(
            status = LinkServerStatus.OFF,
            port = port,
        )
    )
    val state: StateFlow<LinkServerSnapshot> = _state.asStateFlow()

    /**
     * In-process registry of inbound restore jobs. Exposed so the Link
     * screen can observe pending-confirm prompts and so JVM tests can
     * inject a deterministic clock.
     */
    val restoreJobs: RestoreJobRegistry = RestoreJobRegistry()

    /**
     * Root under [Context.getCacheDir] where each inbound restore stages
     * its files. Held as a property so we can wipe the whole tree on
     * server stop and so tests can poke at it.
     */
    private val restoreRoot: File by lazy {
        File(context.cacheDir, "optimalx-link/restore-jobs")
    }

    @Volatile
    private var engine: EmbeddedServer<*, *>? = null

    /**
     * Starts the embedded CIO server. Idempotent — if already running, this is
     * a no-op. The bearer token is regenerated on every successful start.
     */
    fun start() {
        if (engine != null) return
        val token = tokenProvider()
        _state.value = _state.value.copy(
            status = LinkServerStatus.STARTING,
            boundAddress = "0.0.0.0",
            port = port,
            token = token,
            lastError = null,
        )
        appendActivity("Starting OptimalX Link server on port $port")

        // Fresh start: wipe any leftover staging dirs from a crashed prior
        // session. A staging zip we can't link back to a live job is dead
        // weight that the user can't audit.
        runCatching { restoreRoot.deleteRecursively() }
        restoreRoot.mkdirs()

        try {
            val server = embeddedServer(CIO, port = port, host = "0.0.0.0") {
                installRoutes(
                    appVersionName = appVersionName,
                    tokenSupplier = { _state.value.token },
                    onActivity = { msg -> appendActivity(msg) },
                    statsProvider = { OptimalXBackupManager.snapshotStats(context) },
                    snapshotWriter = { out ->
                        OptimalXBackupManager.writeSnapshotTo(
                            context = context,
                            appVersionName = appVersionName,
                            out = out,
                        )
                    },
                    restoreJobs = restoreJobs,
                    restoreRoot = restoreRoot,
                    restoreValidator = { input, stagingDir ->
                        OptimalXBackupManager.validateZipForRestore(input, stagingDir)
                    },
                    restoreApplier = { plan ->
                        OptimalXBackupManager.applyValidatedRestore(context, plan)
                    },
                )
            }.also { it.start(wait = false) }
            engine = server
            _state.value = _state.value.copy(status = LinkServerStatus.LISTENING)
            appendActivity("Server listening on 0.0.0.0:$port")
        } catch (t: Throwable) {
            engine = null
            _state.value = _state.value.copy(
                status = LinkServerStatus.ERROR,
                lastError = t.message ?: t::class.java.simpleName,
            )
            appendActivity("Server failed to start: ${t.message}")
        }
    }

    /**
     * Stops the embedded server if it is running. Idempotent. After this
     * returns, [state] reports [LinkServerStatus.OFF] and the token is
     * cleared so any leftover screenshot of the UI is no longer useful.
     *
     * Also tears down the restore-job registry: any pending-confirm
     * dialogs vanish and their staging directories are deleted. A user
     * leaving the Link screen mid-restore is a deliberate reject.
     */
    fun stop() {
        val server = engine ?: run {
            _state.value = _state.value.copy(
                status = LinkServerStatus.OFF,
                boundAddress = "",
                token = "",
            )
            return
        }
        engine = null
        appendActivity("Stopping server")
        runCatching { server.stop(gracePeriodMillis = 200, timeoutMillis = 1_000) }
        kotlinx.coroutines.runBlocking { restoreJobs.shutdown() }
        runCatching { restoreRoot.deleteRecursively() }
        _state.value = _state.value.copy(
            status = LinkServerStatus.OFF,
            boundAddress = "",
            token = "",
        )
    }

    /**
     * Regenerates the bearer token without restarting the engine. Existing
     * clients holding the old token will start getting 401s on their next
     * request.
     */
    fun regenerateToken() {
        if (engine == null) return
        _state.value = _state.value.copy(token = tokenProvider())
        appendActivity("Bearer token regenerated")
    }

    /**
     * Forwarded from the Link screen's "Accept" tap. Applies the validated
     * restore plan and transitions the job to [RestoreJobStatus.OK] or
     * [RestoreJobStatus.FAILED]. Safe to call when the server is stopped
     * — the registry already swallowed the staging on shutdown so this
     * becomes a no-op.
     */
    suspend fun acceptRestore(jobId: String): RestoreJob? {
        val plan = restoreJobs.beginRunning(jobId) ?: return restoreJobs.get(jobId)
        val result = withContext(Dispatchers.IO) {
            runCatching {
                OptimalXBackupManager.applyValidatedRestore(context, plan)
            }
        }
        val (success, message) = if (result.isSuccess) {
            val backupResult = result.getOrNull()
            (backupResult?.success ?: false) to (backupResult?.message ?: "Restore failed.")
        } else {
            false to (result.exceptionOrNull()?.message ?: "Restore crashed.")
        }
        appendActivity(
            if (success) "Restore $jobId applied: $message" else "Restore $jobId failed: $message"
        )
        return restoreJobs.completeRunning(jobId, success = success, message = message)
    }

    /** Forwarded from the Link screen's "Reject" tap. */
    suspend fun rejectRestore(jobId: String): RestoreJob? {
        appendActivity("Restore $jobId rejected by user")
        return restoreJobs.reject(jobId)
    }

    private fun appendActivity(message: String) {
        val entry = LinkActivityEntry(epochMs = System.currentTimeMillis(), message = message)
        val current = _state.value.activity
        val next = (listOf(entry) + current).take(ACTIVITY_LOG_LIMIT)
        _state.value = _state.value.copy(activity = next)
    }
}

/**
 * Wire all OptimalX Link routes onto [this] Ktor application. Pure Ktor
 * surface — no Android types referenced — so JVM tests can drive this with
 * `testApplication { }` and supply fake providers backed by
 * `ByteArrayOutputStream` / `tempDir`.
 *
 * @param tokenSupplier returns the current expected bearer token. Reading
 *        each request lets the server's `regenerateToken()` take effect
 *        without restarting the engine.
 * @param onActivity receives one-line activity log messages.
 * @param statsProvider produces the JSON body for `/v1/status`.
 * @param snapshotWriter streams the snapshot archive to the supplied
 *        [OutputStream]. Implementations should not close the stream.
 * @param restoreJobs registry that owns the lifecycle of inbound restore
 *        jobs. Required because POST /v1/restore/full and GET /v1/jobs/{id}
 *        share state with the Link screen's confirm dialog.
 * @param restoreRoot per-job staging directory under which the handler
 *        writes `incoming.zip` and a `staging/` extract folder.
 * @param restoreValidator extracts + validates an incoming zip; the
 *        production implementation is
 *        [com.example.optimalx.data.backup.OptimalXBackupManager.validateZipForRestore].
 * @param restoreApplier applies a validated plan; the production
 *        implementation is `applyValidatedRestore`.
 */
fun Application.installRoutes(
    appVersionName: String,
    tokenSupplier: () -> String,
    onActivity: (String) -> Unit,
    statsProvider: () -> SnapshotStats,
    snapshotWriter: (OutputStream) -> Unit,
    restoreJobs: RestoreJobRegistry,
    restoreRoot: File,
    restoreValidator: (java.io.InputStream, File) -> com.example.optimalx.data.backup.RestoreValidation,
    restoreApplier: (com.example.optimalx.data.backup.RestorePlan.Ok) -> com.example.optimalx.data.backup.BackupResult,
) {
    install(ContentNegotiation) { json() }

    install(StatusPages) {
        exception<Throwable> { call, cause ->
            onActivity("Unhandled error on ${call.request.local.uri}: ${cause.message}")
            call.respond(
                HttpStatusCode.InternalServerError,
                ErrorResponse(error = cause.message ?: cause::class.java.simpleName),
            )
        }
    }

    install(Authentication) {
        bearer("link-bearer") {
            realm = LINK_BEARER_REALM
            authenticate { credential ->
                val expected = tokenSupplier()
                if (expected.isNotEmpty() && credential.token == expected) {
                    UserIdPrincipal("link-client")
                } else {
                    null
                }
            }
        }
    }

    routing {
        authenticate("link-bearer") {
            get("/v1/status") {
                val stats: SnapshotStats = statsProvider()
                onActivity("GET /v1/status")
                call.respond(
                    StatusResponse(
                        appVersion = appVersionName,
                        dbVersion = com.example.optimalx.data.db.AppDatabase.SCHEMA_VERSION,
                        formatVersion = BackupManifest.CURRENT_FORMAT_VERSION,
                        linkVersion = LINK_PROTOCOL_VERSION,
                        dbFileSize = stats.dbFileSize,
                        workshopProjectCount = stats.workshopProjectCount,
                        attachmentCount = stats.attachmentCount,
                    )
                )
            }

            get("/v1/bundles/full") {
                onActivity("GET /v1/bundles/full")
                call.response.header(
                    io.ktor.http.HttpHeaders.ContentDisposition,
                    "attachment; filename=\"${OptimalXBackupManager.suggestedExportFileName()}\"",
                )
                call.respondOutputStream(
                    contentType = io.ktor.http.ContentType.Application.Zip,
                ) {
                    snapshotWriter(this)
                }
            }

            post("/v1/restore/full") {
                val caller = call.request.local.remoteHost
                // Layout: `<root>/<jobId>/{incoming.zip, staging/}`. The
                // registry mints the id, the closure here turns it into
                // the canonical path so cleanup later wipes the exact
                // directory we wrote into.
                val job = restoreJobs.beginValidating(
                    callerAddress = caller,
                    rootForId = { id -> File(restoreRoot, id) },
                )
                val finalRoot = File(restoreRoot, job.id).apply { mkdirs() }
                val incoming = File(finalRoot, "incoming.zip")
                val staging = File(finalRoot, "staging").apply { mkdirs() }

                onActivity("POST /v1/restore/full from $caller (jobId=${job.id})")

                try {
                    incoming.outputStream().use { sink ->
                        call.receiveChannel().copyTo(sink)
                    }
                } catch (t: Throwable) {
                    onActivity("Restore ${job.id} body stream failed: ${t.message}")
                    restoreJobs.markFailed(job.id, t.message ?: "request stream failed")
                    call.respond(
                        HttpStatusCode.Accepted,
                        RestoreAcceptedResponse(jobId = job.id, status = RestoreJobStatus.FAILED.name),
                    )
                    return@post
                }

                val validation = withContext(Dispatchers.IO) {
                    incoming.inputStream().use { stream ->
                        restoreValidator(stream, staging)
                    }
                }
                val updated = restoreJobs.completeValidation(job.id, validation)
                val status = updated?.status ?: RestoreJobStatus.FAILED
                onActivity("Restore ${job.id} validation: $status")
                call.respond(
                    HttpStatusCode.Accepted,
                    RestoreAcceptedResponse(jobId = job.id, status = status.name),
                )
            }

            get("/v1/jobs/{id}") {
                val id = call.parameters["id"].orEmpty()
                val job = restoreJobs.get(id)
                if (job == null) {
                    call.respond(HttpStatusCode.NotFound, ErrorResponse(error = "unknown job"))
                    return@get
                }
                call.respond(
                    JobStatusResponse(
                        id = job.id,
                        status = job.status.name,
                        message = job.message,
                        callerAddress = job.callerAddress,
                        createdAtMs = job.createdAtMs,
                        snapshotEpochMs = job.snapshotEpochMs,
                        appVersion = job.manifest?.appVersionName ?: "",
                        dbVersion = job.manifest?.dbVersion ?: 0,
                    )
                )
            }
        }
    }

    // Silence "unused parameter" lint — `restoreApplier` is reached from
    // the LinkServer companion path (acceptRestore) rather than through a
    // route directly, but we still want the lambda in the signature so
    // tests can dependency-inject it.
    @Suppress("UNUSED_VARIABLE")
    val keepApplierReachable = restoreApplier
}

/**
 * Bump when the wire protocol changes (new endpoint shape, new auth scheme).
 * Independent from [BackupManifest.CURRENT_FORMAT_VERSION] so we can iterate
 * the HTTP surface without changing the on-disk archive layout.
 */
const val LINK_PROTOCOL_VERSION: Int = 1

@Serializable
data class StatusResponse(
    val appVersion: String,
    val dbVersion: Int,
    val formatVersion: Int,
    val linkVersion: Int,
    val dbFileSize: Long,
    val workshopProjectCount: Int,
    val attachmentCount: Int,
)

@Serializable
data class ErrorResponse(val error: String)

/**
 * Body returned from `POST /v1/restore/full`. `status` is the registry
 * state immediately after validation — either `PENDING_CONFIRM` (the user
 * still has to tap) or `FAILED` (the body wasn't a usable snapshot). The
 * caller polls `GET /v1/jobs/{jobId}` for further transitions.
 */
@Serializable
data class RestoreAcceptedResponse(
    val jobId: String,
    val status: String,
)

/**
 * Body of `GET /v1/jobs/{id}`. Carries everything the desktop polls for —
 * including the manifest's app+db versions so the desktop can show "phone
 * accepted restore from snapshot 2026-05-21 (app v1.4.2)" in its activity
 * log without having to remember which snapshot it pushed.
 */
@Serializable
data class JobStatusResponse(
    val id: String,
    val status: String,
    val message: String,
    val callerAddress: String,
    val createdAtMs: Long,
    val snapshotEpochMs: Long,
    val appVersion: String,
    val dbVersion: Int,
)
