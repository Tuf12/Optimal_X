package com.example.optimalx.data.backup

import kotlinx.serialization.Serializable

/**
 * Spec: app/docs/architecture/OPTIMALX_LINK.md (§1 archive format).
 *
 * Manifest entry written at `manifest.json` inside every snapshot. All fields
 * other than [formatVersion] have defaults so older or newer builds can still
 * parse a manifest that is missing a field — provided we never repurpose a
 * field name without bumping [formatVersion].
 *
 * Lives in its own file with no Android dependencies so the desktop archive
 * tools and the HTTP layer can depend on it freely.
 */
@Serializable
data class BackupManifest(
    val formatVersion: Int,
    val exportEpochMs: Long = 0L,
    val appVersionName: String = "",
    val dbVersion: Int = 0,
    val includesFiles: Boolean = false,
) {
    companion object {
        /** Current archive shape version. Bump only when the on-disk layout changes. */
        const val CURRENT_FORMAT_VERSION: Int = 1
    }
}
