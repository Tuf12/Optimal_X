package com.example.optimalx.ui.gallery

import com.example.optimalx.data.eidos.WorkshopProjectPhase

data class PanelGalleryItem(
    val subfolderId: Long,
    val name: String,
    val phase: WorkshopProjectPhase,
    val hasPublishedRelease: Boolean,
    val createdAt: Long,
    val updatedAt: Long,
) {
    /** Launchable when a frozen release exists — not tied to in-workshop edit phase. */
    val isLaunchable: Boolean get() = hasPublishedRelease
    val statusBadge: String? get() = if (isLaunchable) null else "Draft"
}
