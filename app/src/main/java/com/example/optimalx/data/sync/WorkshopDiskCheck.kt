package com.example.optimalx.data.sync

import android.content.Context
import java.io.File

object WorkshopDiskCheck {

    /** Desktop-built panels typically have multi-kB script.js; scaffold stubs are much smaller. */
    private const val MIN_SCRIPT_BYTES_FOR_RESTORED_PANEL = 800L

    fun needsRestoreFromDesktop(context: Context, subfolderId: Long): Boolean {
        val root = SyncFilePaths.workshopRoot(context, subfolderId)
        if (!root.isDirectory) return true
        val script = File(root, "script.js")
        if (!script.isFile) return true
        if (script.length() < MIN_SCRIPT_BYTES_FOR_RESTORED_PANEL) return true
        val html = File(root, "index.html")
        if (!html.isFile || html.length() < 100L) return true
        return false
    }
}
