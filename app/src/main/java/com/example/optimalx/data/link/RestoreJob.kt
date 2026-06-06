package com.example.optimalx.data.link

// Spec: app/docs/architecture/OPTIMALX_LINK.md §recovery-the-workshop-wipe-case.
//
// Models the lifecycle of one inbound restore call:
//
//   POST /v1/restore/full         creates the job, transitions it to
//                                 PENDING_CONFIRM (or FAILED if the body
//                                 isn't a valid snapshot)
//   user taps Accept on phone     RUNNING -> OK
//   user taps Reject on phone     REJECTED
//   5 minutes elapse              REJECTED (auto)
//   GET /v1/jobs/{id}             reports the current state
//
// The model lives in its own file because both the HTTP layer (LinkServer)
// and the UI layer (LinkScreen confirm dialog) bind to it; keeping it
// independent of either prevents accidental cyclic imports.

import com.example.optimalx.data.backup.BackupManifest
import com.example.optimalx.data.backup.RestoreValidation

/** State machine of a restore job. Strictly forward-progressing. */
enum class RestoreJobStatus {
    /** Initial value while the body is still being streamed and validated. */
    VALIDATING,

    /** Validation succeeded; waiting for the user's confirm dialog tap. */
    PENDING_CONFIRM,

    /** User accepted; apply is in progress. */
    RUNNING,

    /** Apply finished successfully. Terminal. */
    OK,

    /** User explicitly rejected, or the 5-minute confirm window expired. Terminal. */
    REJECTED,

    /** Validation or apply failed. Terminal. See [RestoreJob.message]. */
    FAILED,
}

/**
 * Immutable per-job snapshot the UI and the JSON endpoint both consume.
 *
 * @property id stable opaque identifier; used in `/v1/jobs/{id}` and in the
 *              POST response. Generated at job creation.
 * @property callerAddress the IP of the client that initiated the restore.
 *              Surfaced in the confirm dialog so the user knows which
 *              desktop they're about to accept from.
 * @property snapshotEpochMs the moment the **phone** originally exported
 *              this snapshot — pulled from the manifest, not the network
 *              timestamp. May be 0 when the manifest didn't carry one.
 * @property manifest the validated archive's manifest. Null while
 *              [status] is [RestoreJobStatus.VALIDATING] or
 *              [RestoreJobStatus.FAILED] before validation produced one.
 * @property message human-readable status detail. Empty for happy-path
 *              states; populated on [RestoreJobStatus.FAILED] and
 *              [RestoreJobStatus.OK] for the activity log.
 * @property createdAtMs job creation wall-clock; used to evaluate the
 *              5-minute confirm timeout.
 */
data class RestoreJob(
    val id: String,
    val callerAddress: String,
    val createdAtMs: Long,
    val status: RestoreJobStatus,
    val snapshotEpochMs: Long = 0L,
    val manifest: BackupManifest? = null,
    val message: String = "",
)

/**
 * Default time the phone waits for an in-person tap before auto-rejecting
 * a pending restore. Spec: app/docs/architecture/OPTIMALX_LINK.md.
 */
const val DEFAULT_CONFIRM_TIMEOUT_MS: Long = 5L * 60L * 1000L

/**
 * Pull the data from a [RestoreValidation.Ready] into the shape the job
 * registry stores. Centralized so the call site stays one line.
 */
internal fun RestoreValidation.Ready.toReadyFields(): Pair<Long, BackupManifest> =
    plan.manifest.exportEpochMs to plan.manifest
