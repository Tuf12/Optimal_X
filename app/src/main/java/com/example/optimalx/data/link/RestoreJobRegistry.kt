package com.example.optimalx.data.link

// Spec: app/docs/architecture/OPTIMALX_LINK.md §recovery-the-workshop-wipe-case.
//
// Thread-safe registry that owns the full lifecycle of inbound restore jobs.
// All public methods are non-suspending and can be called from any thread
// (request handlers run on Ktor I/O threads; the UI calls confirm/reject
// from the Main dispatcher). Mutations funnel through a [Mutex] inside a
// CoroutineScope so updates publish atomically to [jobs].

import com.example.optimalx.data.backup.RestorePlan
import com.example.optimalx.data.backup.RestoreValidation
import java.io.File
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/**
 * Per-job mutable state held inside the registry. Carries the lifecycle
 * resources (staging directory + validated plan) that the public
 * [RestoreJob] snapshot intentionally hides.
 */
private data class JobEntry(
    val public: RestoreJob,
    /** Root directory the request handler wrote the incoming zip + staging into. */
    val jobRoot: File,
    val plan: RestorePlan.Ok?,
    val timeoutJob: Job?,
)

/**
 * Coordinates the lifecycle of inbound `POST /v1/restore/full` calls.
 *
 * Designed to be constructed once per [LinkServer]. The server passes
 * inbound bodies through [beginValidating] / [completeValidation], the UI
 * watches [jobs] for any entry in [RestoreJobStatus.PENDING_CONFIRM], and
 * the user's accept / reject taps land at [accept] / [reject].
 */
class RestoreJobRegistry(
    private val scope: CoroutineScope = CoroutineScope(SupervisorJob() + Dispatchers.IO),
    private val confirmTimeoutMs: Long = DEFAULT_CONFIRM_TIMEOUT_MS,
    private val clock: () -> Long = System::currentTimeMillis,
) {

    private val mutex = Mutex()

    /** Internal storage. Keyed by job id. */
    private val entries: MutableMap<String, JobEntry> = mutableMapOf()

    /** Observable snapshot for the UI. Updated atomically on every state change. */
    private val _jobs = MutableStateFlow<Map<String, RestoreJob>>(emptyMap())
    val jobs: StateFlow<Map<String, RestoreJob>> = _jobs.asStateFlow()

    /**
     * Returns the most recent job that is awaiting a confirm tap, or null
     * when no such job exists. Used by the Link screen to decide whether
     * to show the confirm dialog.
     */
    val pendingConfirm: StateFlow<RestoreJob?> get() = _pendingConfirm
    private val _pendingConfirm = MutableStateFlow<RestoreJob?>(null)

    /**
     * Register a new job in [RestoreJobStatus.VALIDATING] state. The caller
     * (the HTTP handler) is expected to follow up with [completeValidation]
     * — or [markFailed] if streaming the request body itself failed.
     *
     * The job's id is minted **before** [rootForId] is invoked, so the
     * caller can derive a deterministic staging path from it (typically
     * `cacheDir/optimalx-link/restore-jobs/<jobId>/`) and the registry
     * always holds the exact path it'll later wipe.
     *
     * @param rootForId builds the per-job working directory from the
     *        registry's freshly minted id. The directory does not need to
     *        exist yet — [JobEntry.jobRoot] will be deleted on any
     *        terminal transition regardless.
     */
    suspend fun beginValidating(
        callerAddress: String,
        rootForId: (jobId: String) -> File,
    ): RestoreJob {
        val id = nextJobId()
        val jobRoot = rootForId(id)
        val job = RestoreJob(
            id = id,
            callerAddress = callerAddress,
            createdAtMs = clock(),
            status = RestoreJobStatus.VALIDATING,
        )
        mutex.withLock {
            entries[id] = JobEntry(public = job, jobRoot = jobRoot, plan = null, timeoutJob = null)
            publish()
        }
        return job
    }

    /**
     * Transition a [RestoreJobStatus.VALIDATING] job to its post-validation
     * state. On [RestoreValidation.Ready] the job becomes
     * [RestoreJobStatus.PENDING_CONFIRM] and a confirm-timeout coroutine
     * starts. On [RestoreValidation.Failed] the job becomes
     * [RestoreJobStatus.FAILED] and its [JobEntry.jobRoot] is deleted.
     */
    suspend fun completeValidation(id: String, result: RestoreValidation): RestoreJob? {
        val nextPublic: RestoreJob
        mutex.withLock {
            val entry = entries[id] ?: return null
            nextPublic = when (result) {
                is RestoreValidation.Ready -> {
                    val (epoch, manifest) = result.toReadyFields()
                    entry.public.copy(
                        status = RestoreJobStatus.PENDING_CONFIRM,
                        snapshotEpochMs = epoch,
                        manifest = manifest,
                    )
                }
                is RestoreValidation.Failed -> entry.public.copy(
                    status = RestoreJobStatus.FAILED,
                    message = result.message,
                )
            }
            val nextPlan = (result as? RestoreValidation.Ready)?.plan
            val timeoutJob = if (nextPublic.status == RestoreJobStatus.PENDING_CONFIRM) {
                startConfirmTimeout(id)
            } else {
                null
            }
            entries[id] = entry.copy(
                public = nextPublic,
                plan = nextPlan,
                timeoutJob = timeoutJob,
            )
            if (nextPublic.status == RestoreJobStatus.FAILED) {
                cleanupRoot(entry.jobRoot)
            }
            publish()
        }
        return nextPublic
    }

    /**
     * Force a job to [RestoreJobStatus.FAILED]. Called when the handler
     * couldn't even reach validation — e.g., the request body stopped
     * mid-stream or the staging dir couldn't be created.
     */
    suspend fun markFailed(id: String, message: String): RestoreJob? {
        val updated: RestoreJob?
        mutex.withLock {
            val entry = entries[id] ?: return null
            entry.timeoutJob?.cancel()
            val next = entry.public.copy(
                status = RestoreJobStatus.FAILED,
                message = message,
            )
            entries[id] = entry.copy(public = next, plan = null, timeoutJob = null)
            updated = next
            cleanupRoot(entry.jobRoot)
            publish()
        }
        return updated
    }

    /**
     * Accept the user's confirm tap. Transitions the job from
     * [RestoreJobStatus.PENDING_CONFIRM] to [RestoreJobStatus.RUNNING] and
     * returns the [RestorePlan.Ok] the caller should hand to
     * `OptimalXBackupManager.applyValidatedRestore`. Returns null when the
     * job no longer exists or isn't in the right state (the timeout may
     * have rejected it already).
     */
    suspend fun beginRunning(id: String): RestorePlan.Ok? {
        return mutex.withLock {
            val entry = entries[id] ?: return@withLock null
            if (entry.public.status != RestoreJobStatus.PENDING_CONFIRM) return@withLock null
            val plan = entry.plan ?: return@withLock null
            entry.timeoutJob?.cancel()
            val next = entry.public.copy(status = RestoreJobStatus.RUNNING)
            entries[id] = entry.copy(public = next, timeoutJob = null)
            publish()
            plan
        }
    }

    /**
     * Mark a running job as OK or FAILED and clean up its working directory.
     * The caller passes the [com.example.optimalx.data.backup.BackupResult]
     * straight from the apply step.
     */
    suspend fun completeRunning(id: String, success: Boolean, message: String): RestoreJob? {
        val next: RestoreJob?
        mutex.withLock {
            val entry = entries[id] ?: return null
            val updated = entry.public.copy(
                status = if (success) RestoreJobStatus.OK else RestoreJobStatus.FAILED,
                message = message,
            )
            entries[id] = entry.copy(public = updated, plan = null, timeoutJob = null)
            next = updated
            cleanupRoot(entry.jobRoot)
            publish()
        }
        return next
    }

    /**
     * Reject the job from the UI side. Transitions any non-terminal status
     * (notably [RestoreJobStatus.PENDING_CONFIRM]) to
     * [RestoreJobStatus.REJECTED] and cleans up. Idempotent.
     */
    suspend fun reject(id: String, reason: String = "Restore rejected by user."): RestoreJob? {
        val next: RestoreJob?
        mutex.withLock {
            val entry = entries[id] ?: return null
            if (entry.public.status.isTerminal()) return entry.public
            entry.timeoutJob?.cancel()
            val updated = entry.public.copy(
                status = RestoreJobStatus.REJECTED,
                message = reason,
            )
            entries[id] = entry.copy(public = updated, plan = null, timeoutJob = null)
            next = updated
            cleanupRoot(entry.jobRoot)
            publish()
        }
        return next
    }

    /** Returns the current snapshot of a single job, or null when unknown. */
    fun get(id: String): RestoreJob? = _jobs.value[id]

    /**
     * Cancel any in-flight timeouts and drop all jobs. Called when the
     * Link screen exits — the staging dirs are wiped so a half-finished
     * restore can't be resumed without a fresh handshake.
     */
    suspend fun shutdown() {
        mutex.withLock {
            for (entry in entries.values) {
                entry.timeoutJob?.cancel()
                cleanupRoot(entry.jobRoot)
            }
            entries.clear()
            publish()
        }
    }

    // ------------------------------------------------------------------
    // Internal helpers (must hold `mutex` when called)
    // ------------------------------------------------------------------

    private fun publish() {
        val snapshot: Map<String, RestoreJob> = entries.mapValues { it.value.public }
        _jobs.value = snapshot
        // The Link screen only ever cares about the newest pending-confirm
        // job — there should be at most one at a time, but if the user
        // somehow stacked two we surface the most recent.
        _pendingConfirm.value = entries.values
            .map { it.public }
            .filter { it.status == RestoreJobStatus.PENDING_CONFIRM }
            .maxByOrNull { it.createdAtMs }
    }

    private fun cleanupRoot(root: File) {
        runCatching { root.deleteRecursively() }
    }

    private fun startConfirmTimeout(id: String): Job {
        return scope.launch {
            delay(confirmTimeoutMs)
            // reject() is a no-op if the job already terminated (accept,
            // explicit reject, etc.) — exactly the behavior we want here.
            reject(id, reason = "Restore timed out (no confirm within ${confirmTimeoutMs / 1000}s).")
        }
    }

    private fun nextJobId(): String {
        // Short, URL-safe random id. Collisions are vanishingly rare and
        // the registry checks for them anyway by holding the mutex.
        return LinkAuthToken.generate(byteLength = 6) // ≈ 8 chars base64url
    }
}

private fun RestoreJobStatus.isTerminal(): Boolean = when (this) {
    RestoreJobStatus.OK, RestoreJobStatus.REJECTED, RestoreJobStatus.FAILED -> true
    else -> false
}
