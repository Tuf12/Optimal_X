package com.example.optimalx.data.sync

import android.content.Context
import com.example.optimalx.data.db.AppDatabase
import com.example.optimalx.data.db.SystemFolderNames
import java.io.File

/**
 * Device-local paths for synced [file_references]. Incoming wire rows carry the origin
 * device's absolute path — never apply that on the receiving device.
 */
object SyncFilePathResolver {

    suspend fun resolve(
        context: Context,
        db: AppDatabase,
        subfolderId: Long,
        fileName: String,
    ): String {
        val subfolder = db.subfolderDao().getById(subfolderId) ?: return ""
        val workshopParent = db.parentFolderDao().getSystemFolderByName(SystemFolderNames.PANEL_WORKSHOP)
        val file = if (workshopParent != null && subfolder.parentFolderId == workshopParent.id) {
            File(SyncFilePaths.workshopRoot(context, subfolderId), fileName)
        } else {
            SyncFilePaths.localAttachmentFile(context, subfolderId, fileName)
        }
        file.parentFile?.mkdirs()
        return file.absolutePath
    }
}
