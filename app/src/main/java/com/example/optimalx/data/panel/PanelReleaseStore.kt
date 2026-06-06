package com.example.optimalx.data.panel

import android.content.Context
import com.example.optimalx.data.db.AppDatabase
import com.example.optimalx.data.eidos.WorkshopProjectPhase
import com.example.optimalx.data.preferences.WorkshopProjectPreferences
import com.example.optimalx.ui.workshop.PanelHtmlComposer
import java.io.File

/**
 * Frozen runtime copy of a workshop panel for Panel Gallery and editor custom tabs.
 *
 * Workshop files under `workshop/{id}/` remain editable; published HTML/CSS/JS live under
 * `panel_releases/{id}/` and are refreshed when the user accepts logic or finishes an update.
 */
object PanelReleaseStore {

    private const val RELEASE_ROOT = "panel_releases"
    private val RUNTIME_TYPES = setOf("html", "css", "js")

    fun releaseDir(context: Context, workshopSubfolderId: Long): File =
        File(context.filesDir, "$RELEASE_ROOT/$workshopSubfolderId")

    fun hasRelease(context: Context, workshopSubfolderId: Long): Boolean {
        val dir = releaseDir(context, workshopSubfolderId)
        if (!dir.isDirectory) return false
        return dir.listFiles()?.any { file ->
            file.isFile && file.extension.lowercase() in RUNTIME_TYPES
        } == true
    }

    /**
     * Copies current workshop runtime files into the release directory.
     * @return true when at least one runtime file was published
     */
    suspend fun publishFromWorkshop(
        context: Context,
        db: AppDatabase,
        workshopSubfolderId: Long,
    ): Boolean {
        val runtimeRefs = db.fileReferenceDao()
            .getBySubfolderOnce(workshopSubfolderId)
            .filter { it.fileType.lowercase() in RUNTIME_TYPES }
        val hasHtml = runtimeRefs.any { it.fileType.equals("html", ignoreCase = true) }
        if (!hasHtml) return false

        val destDir = releaseDir(context, workshopSubfolderId)
        destDir.mkdirs()
        destDir.listFiles()?.forEach { it.delete() }

        var copied = 0
        for (ref in runtimeRefs) {
            val source = File(ref.filePath)
            if (!source.isFile) continue
            val dest = File(destDir, ref.fileName)
            source.copyTo(dest, overwrite = true)
            copied++
        }
        if (copied == 0) return false

        WorkshopProjectPreferences.setPublishedAtMs(context, workshopSubfolderId, System.currentTimeMillis())
        return true
    }

    /**
     * For projects already marked Complete before releases existed.
     */
    suspend fun ensurePublishedIfComplete(
        context: Context,
        db: AppDatabase,
        workshopSubfolderId: Long,
    ) {
        if (hasRelease(context, workshopSubfolderId)) return
        if (WorkshopProjectPreferences.getProjectPhase(context, workshopSubfolderId) !=
            WorkshopProjectPhase.COMPLETE
        ) {
            return
        }
        publishFromWorkshop(context, db, workshopSubfolderId)
    }

    fun buildCompositeHtml(context: Context, workshopSubfolderId: Long): String? {
        val dir = releaseDir(context, workshopSubfolderId)
        if (!dir.isDirectory) return null
        return PanelHtmlComposer.buildCompositeHtmlFromDirectory(dir)
    }

    fun deleteRelease(context: Context, workshopSubfolderId: Long) {
        releaseDir(context, workshopSubfolderId).deleteRecursively()
        WorkshopProjectPreferences.clearPublishedAt(context, workshopSubfolderId)
    }
}
