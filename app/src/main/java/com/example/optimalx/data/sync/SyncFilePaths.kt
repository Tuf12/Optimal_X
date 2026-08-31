package com.example.optimalx.data.sync

import android.content.Context
import java.io.File

object SyncFilePaths {

    fun localAttachmentFile(context: Context, subfolderId: Long, fileName: String): File {
        val dir = File(context.filesDir, "optimalx_files/$subfolderId").apply { mkdirs() }
        return File(dir, fileName)
    }

    fun workshopRoot(context: Context, subfolderId: Long): File =
        File(context.filesDir, "workshop/$subfolderId")
}
