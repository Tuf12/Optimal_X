package com.example.optimalx.data.sync

import android.content.Context
import android.util.Log
import com.example.optimalx.data.db.AppDatabase
import com.example.optimalx.data.db.SystemFolderNames
import com.example.optimalx.data.model.FileReference
import com.example.optimalx.data.model.Subfolder
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File

class SyncFileService(
    private val context: Context,
    private val db: AppDatabase,
    private val preferences: SyncPreferences = SyncPreferences(context),
    private val api: SyncFileApi = SyncFileApi(),
    private val syncApi: SyncApi = SyncApi(),
    private val pushBuilder: SyncPushBuilder = SyncPushBuilder(context, db),
) {

    suspend fun ensureAttachmentLocal(ref: FileReference): SyncCallResult<FileReference> =
        withContext(Dispatchers.IO) {
            val existing = resolveExistingLocalFile(ref)
            if (existing != null) {
                return@withContext SyncCallResult.Success(maybeUpdateStoredPath(ref, existing))
            }

            val endpoint = preferences.buildEndpoint(preferences.readSnapshot())
                ?: return@withContext SyncCallResult.Failure(
                    "Set desktop host and bearer token in Settings → Sync with Desktop.",
                )

            val dest = SyncFilePaths.localAttachmentFile(context, ref.subfolderId, ref.fileName)
            when (val result = api.downloadAttachment(endpoint, ref.globalId, dest)) {
                is SyncCallResult.Failure -> result
                is SyncCallResult.Success -> {
                    SyncCallResult.Success(maybeUpdateStoredPath(ref, dest))
                }
            }
        }

    suspend fun pushAttachmentsToDesktop(
        since: Long = 0L,
        onProgress: (WorkshopBackupProgress) -> Unit = {},
    ): SyncCallResult<WorkshopBackupSummary> = withContext(Dispatchers.IO) {
        val endpoint = preferences.buildEndpoint(preferences.readSnapshot())
            ?: return@withContext SyncCallResult.Failure(
                "Set desktop host and bearer token in Settings → Sync with Desktop.",
            )

        val refs = collectAttachmentsToUpload(since)
        if (refs.isEmpty()) {
            onProgress(WorkshopBackupProgress(totalFiles = 0, uploadedFiles = 0, currentPath = null))
            return@withContext SyncCallResult.Success(
                WorkshopBackupSummary(filesUploaded = 0, bytesUploaded = 0),
            )
        }

        when (val metaResult = ensureAttachmentMetadataOnDesktop(endpoint, refs)) {
            is SyncCallResult.Failure -> return@withContext metaResult
            is SyncCallResult.Success -> Unit
        }

        val deviceId = preferences.ensureDeviceId()
        var bytesUploaded = 0L
        var filesUploaded = 0
        var filesSkipped = 0
        onProgress(WorkshopBackupProgress(totalFiles = refs.size, uploadedFiles = 0, currentPath = null))

        refs.forEachIndexed { index, (ref, localFile) ->
            onProgress(
                WorkshopBackupProgress(
                    totalFiles = refs.size,
                    uploadedFiles = index,
                    currentPath = ref.fileName,
                ),
            )
            if (localFile.length() <= 0L) {
                filesSkipped++
                Log.w(TAG, "Skipping empty attachment \"${ref.fileName}\" (globalId=${ref.globalId})")
                return@forEachIndexed
            }
            when (
                val result = api.pushAttachment(
                    endpoint = endpoint,
                    fileGlobalId = ref.globalId,
                    sourceFile = localFile,
                    deviceId = deviceId,
                )
            ) {
                is SyncCallResult.Failure ->
                    return@withContext SyncCallResult.Failure(
                        "${result.message} (failed on \"${ref.fileName}\")",
                    )
                is SyncCallResult.Success -> {
                    bytesUploaded += result.value.bytesWritten
                    filesUploaded++
                }
            }
        }

        onProgress(
            WorkshopBackupProgress(
                totalFiles = refs.size,
                uploadedFiles = refs.size,
                currentPath = null,
            ),
        )
        SyncCallResult.Success(
            WorkshopBackupSummary(
                filesUploaded = filesUploaded,
                bytesUploaded = bytesUploaded,
                filesSkipped = filesSkipped,
            ),
        )
    }

    suspend fun backupWorkshopToDesktop(
        subfolderId: Long,
        onProgress: (WorkshopBackupProgress) -> Unit = {},
    ): SyncCallResult<WorkshopBackupSummary> = withContext(Dispatchers.IO) {
        val endpoint = preferences.buildEndpoint(preferences.readSnapshot())
            ?: return@withContext SyncCallResult.Failure(
                "Set desktop host and bearer token in Settings → Sync with Desktop.",
            )

        val subfolder = db.subfolderDao().getById(subfolderId)
            ?: return@withContext SyncCallResult.Failure("Workshop project not found.")

        uploadWorkshopProject(
            endpoint = endpoint,
            subfolder = subfolder,
            deviceId = preferences.ensureDeviceId(),
            onProgress = onProgress,
        )
    }

    suspend fun restoreWorkshopFromDesktop(
        subfolderId: Long,
        onProgress: (WorkshopBackupProgress) -> Unit = {},
    ): SyncCallResult<WorkshopBackupSummary> = withContext(Dispatchers.IO) {
        val endpoint = preferences.buildEndpoint(preferences.readSnapshot())
            ?: return@withContext SyncCallResult.Failure(
                "Set desktop host and bearer token in Settings → Sync with Desktop.",
            )

        val subfolder = db.subfolderDao().getById(subfolderId)
            ?: return@withContext SyncCallResult.Failure("Workshop project not found.")

        val globalId = subfolder.globalId.trim()
        if (globalId.isEmpty()) {
            return@withContext SyncCallResult.Failure(
                "Workshop project \"${subfolder.name}\" is missing a sync globalId.",
            )
        }

        val relativePaths = collectWorkshopRelativePaths(subfolderId)
        if (relativePaths.isEmpty()) {
            onProgress(WorkshopBackupProgress(totalFiles = 0, uploadedFiles = 0, currentPath = null))
            return@withContext SyncCallResult.Failure(
                "No workshop files to sync — run Pull first, or sync workshop files to PC from a phone project.",
            )
        }

        val workshopRoot = SyncFilePaths.workshopRoot(context, subfolderId).apply { mkdirs() }
        var bytesDownloaded = 0L
        var filesSynced = 0
        var filesSkipped = 0
        onProgress(WorkshopBackupProgress(totalFiles = relativePaths.size, uploadedFiles = 0, currentPath = null))

        relativePaths.forEachIndexed { index, relativePath ->
            onProgress(
                WorkshopBackupProgress(
                    totalFiles = relativePaths.size,
                    uploadedFiles = index,
                    currentPath = relativePath,
                ),
            )
            val destFile = File(workshopRoot, relativePath)
            when (
                val result = api.fetchWorkshopBackupFile(
                    endpoint = endpoint,
                    subfolderGlobalId = globalId,
                    relativePath = relativePath,
                    destFile = destFile,
                )
            ) {
                is SyncCallResult.Failure -> {
                    if (isMissingWorkshopFileOnDesktop(result)) {
                        filesSkipped++
                        destFile.takeIf { it.exists() }?.delete()
                    } else {
                        return@withContext result
                    }
                }
                is SyncCallResult.Success -> {
                    bytesDownloaded += result.value
                    filesSynced++
                    syncFileReferenceAfterRestore(subfolderId, relativePath, destFile)
                }
            }
        }

        if (filesSynced == 0) {
            return@withContext SyncCallResult.Failure(
                if (filesSkipped > 0) {
                    "No workshop files found on the desktop PC for this project. " +
                        "Confirm files exist under backups/mobile-workshop/ and the project is mobile-scoped."
                } else {
                    "No workshop files to sync — run Pull first, or sync workshop files to PC from a phone project."
                },
            )
        }

        onProgress(
            WorkshopBackupProgress(
                totalFiles = relativePaths.size,
                uploadedFiles = relativePaths.size,
                currentPath = null,
            ),
        )
        SyncCallResult.Success(
            WorkshopBackupSummary(
                filesUploaded = filesSynced,
                bytesUploaded = bytesDownloaded,
                filesSkipped = filesSkipped,
            ),
        )
    }

    suspend fun backupAllMobileWorkshopsToDesktop(
        onProgress: (WorkshopBackupAllProgress) -> Unit = {},
    ): SyncCallResult<WorkshopBackupAllSummary> = withContext(Dispatchers.IO) {
        val endpoint = preferences.buildEndpoint(preferences.readSnapshot())
            ?: return@withContext SyncCallResult.Failure(
                "Set desktop host and bearer token in Settings → Sync with Desktop.",
            )

        val projects = listMobileWorkshopProjects()
        if (projects.isEmpty()) {
            onProgress(
                WorkshopBackupAllProgress(
                    projectsTotal = 0,
                    projectsCompleted = 0,
                    currentProjectName = null,
                    currentFileTotal = 0,
                    currentFileUploaded = 0,
                    currentPath = null,
                ),
            )
            return@withContext SyncCallResult.Success(
                WorkshopBackupAllSummary(
                    projectsBackedUp = 0,
                    projectsSkippedEmpty = 0,
                    filesUploaded = 0,
                    bytesUploaded = 0,
                ),
            )
        }

        val deviceId = preferences.ensureDeviceId()
        var projectsBackedUp = 0
        var projectsSkippedEmpty = 0
        var filesUploaded = 0
        var bytesUploaded = 0L

        projects.forEachIndexed { projectIndex, subfolder ->
            onProgress(
                WorkshopBackupAllProgress(
                    projectsTotal = projects.size,
                    projectsCompleted = projectIndex,
                    currentProjectName = subfolder.name,
                    currentFileTotal = 0,
                    currentFileUploaded = 0,
                    currentPath = null,
                ),
            )
            when (
                val result = uploadWorkshopProject(
                    endpoint = endpoint,
                    subfolder = subfolder,
                    deviceId = deviceId,
                    onProgress = { fileProgress ->
                        onProgress(
                            WorkshopBackupAllProgress(
                                projectsTotal = projects.size,
                                projectsCompleted = projectIndex,
                                currentProjectName = subfolder.name,
                                currentFileTotal = fileProgress.totalFiles,
                                currentFileUploaded = fileProgress.uploadedFiles,
                                currentPath = fileProgress.currentPath,
                            ),
                        )
                    },
                )
            ) {
                is SyncCallResult.Failure -> {
                    val detail = result.message
                    return@withContext SyncCallResult.Failure(
                        if (projects.size == 1) {
                            detail
                        } else {
                            "Failed backing up \"${subfolder.name}\": $detail"
                        },
                    )
                }
                is SyncCallResult.Success -> {
                    val summary = result.value
                    if (summary.filesUploaded == 0) {
                        projectsSkippedEmpty++
                    } else {
                        projectsBackedUp++
                    }
                    filesUploaded += summary.filesUploaded
                    bytesUploaded += summary.bytesUploaded
                }
            }
        }

        onProgress(
            WorkshopBackupAllProgress(
                projectsTotal = projects.size,
                projectsCompleted = projects.size,
                currentProjectName = null,
                currentFileTotal = 0,
                currentFileUploaded = 0,
                currentPath = null,
            ),
        )
        SyncCallResult.Success(
            WorkshopBackupAllSummary(
                projectsBackedUp = projectsBackedUp,
                projectsSkippedEmpty = projectsSkippedEmpty,
                filesUploaded = filesUploaded,
                bytesUploaded = bytesUploaded,
            ),
        )
    }

    private suspend fun listMobileWorkshopProjects(): List<Subfolder> {
        val parentId = db.parentFolderDao().getSystemFolderByName(SystemFolderNames.PANEL_WORKSHOP)?.id
            ?: return emptyList()
        return db.subfolderDao().getAllByParentOnce(parentId)
            .filter { it.deletedAt == null && !it.isSystemSubfolder }
            .sortedBy { it.name.lowercase() }
    }

    private suspend fun uploadWorkshopProject(
        endpoint: SyncEndpoint,
        subfolder: Subfolder,
        deviceId: String,
        onProgress: (WorkshopBackupProgress) -> Unit,
    ): SyncCallResult<WorkshopBackupSummary> {
        val globalId = subfolder.globalId.trim()
        if (globalId.isEmpty()) {
            return SyncCallResult.Failure(
                "Workshop project \"${subfolder.name}\" is missing a sync globalId.",
            )
        }

        val workshopRoot = SyncFilePaths.workshopRoot(context, subfolder.id)
        if (!workshopRoot.isDirectory) {
            onProgress(WorkshopBackupProgress(totalFiles = 0, uploadedFiles = 0, currentPath = null))
            return SyncCallResult.Success(WorkshopBackupSummary(filesUploaded = 0, bytesUploaded = 0))
        }

        val files = listWorkshopFiles(workshopRoot)
        var bytesUploaded = 0L
        onProgress(WorkshopBackupProgress(totalFiles = files.size, uploadedFiles = 0, currentPath = null))

        files.forEachIndexed { index, (file, relativePath) ->
            onProgress(
                WorkshopBackupProgress(
                    totalFiles = files.size,
                    uploadedFiles = index,
                    currentPath = relativePath,
                ),
            )
            when (
                val result = api.pushWorkshopFile(
                    endpoint = endpoint,
                    subfolderGlobalId = globalId,
                    relativePath = relativePath,
                    sourceFile = file,
                    deviceId = deviceId,
                )
            ) {
                is SyncCallResult.Failure -> return result
                is SyncCallResult.Success -> bytesUploaded += result.value.bytesWritten
            }
        }

        onProgress(
            WorkshopBackupProgress(
                totalFiles = files.size,
                uploadedFiles = files.size,
                currentPath = null,
            ),
        )
        return SyncCallResult.Success(
            WorkshopBackupSummary(
                filesUploaded = files.size,
                bytesUploaded = bytesUploaded,
            ),
        )
    }

    private suspend fun ensureAttachmentMetadataOnDesktop(
        endpoint: SyncEndpoint,
        refs: List<Pair<FileReference, File>>,
    ): SyncCallResult<Unit> {
        val globalIds = refs.map { it.first.globalId.trim() }.filter { it.isNotEmpty() }
        if (globalIds.isEmpty()) {
            return SyncCallResult.Failure("Attachments are missing sync globalIds.")
        }

        val tables = pushBuilder.buildTier1ForFiles(globalIds)
        if (tables.fileReferences.isEmpty()) {
            return SyncCallResult.Failure("Could not build attachment metadata for desktop sync.")
        }

        Log.i(
            TAG,
            "Pushing attachment metadata for ${tables.fileReferences.size} file(s) before byte upload",
        )

        val deviceId = preferences.ensureDeviceId()
        val lastSyncAt = preferences.readSnapshot().lastSyncAt
        val request = SyncPushPullRequest(
            deviceId = deviceId,
            lastSyncAt = lastSyncAt,
            tiers = listOf(1),
            tables = tables,
        )
        return when (val result = syncApi.push(endpoint, request)) {
            is SyncCallResult.Failure -> result
            is SyncCallResult.Success -> SyncCallResult.Success(Unit)
        }
    }

    private suspend fun collectAttachmentsToUpload(since: Long): List<Pair<FileReference, File>> {
        val refs = if (since > 0L) {
            db.fileReferenceDao().getChangedSince(since)
        } else {
            db.fileReferenceDao().getAllOnce()
        }
        return refs.mapNotNull { ref ->
            val globalId = ref.globalId.trim()
            if (globalId.isEmpty()) return@mapNotNull null
            if (isWorkshopSubfolder(ref.subfolderId)) return@mapNotNull null
            val localFile = resolveExistingLocalFile(ref) ?: return@mapNotNull null
            ref to localFile
        }.sortedBy { it.first.fileName.lowercase() }
    }

    private suspend fun isWorkshopSubfolder(subfolderId: Long): Boolean {
        val workshopParent = db.parentFolderDao().getSystemFolderByName(SystemFolderNames.PANEL_WORKSHOP)
            ?: return false
        val subfolder = db.subfolderDao().getById(subfolderId) ?: return false
        return subfolder.parentFolderId == workshopParent.id
    }

    private suspend fun maybeUpdateStoredPath(ref: FileReference, localFile: File): FileReference {
        if (ref.filePath == localFile.absolutePath) return ref
        val updated = ref.copy(filePath = localFile.absolutePath)
        db.fileReferenceDao().insert(updated)
        return updated
    }

    private fun resolveExistingLocalFile(ref: FileReference): File? {
        val canonical = SyncFilePaths.localAttachmentFile(context, ref.subfolderId, ref.fileName)
        if (canonical.isFile) return canonical
        val stored = File(ref.filePath)
        return stored.takeIf { it.isFile }
    }

    private suspend fun collectWorkshopRelativePaths(subfolderId: Long): List<String> {
        val paths = linkedSetOf<String>()
        db.fileReferenceDao().getBySubfolderOnce(subfolderId).forEach { ref ->
            val name = normalizeWorkshopRelativePath(ref.fileName)
            if (name.isNotEmpty() && !name.contains("..")) {
                paths.add(name)
            }
        }
        val workshopRoot = SyncFilePaths.workshopRoot(context, subfolderId)
        if (workshopRoot.isDirectory) {
            listWorkshopFiles(workshopRoot).forEach { (_, relativePath) ->
                paths.add(relativePath)
            }
        }
        if (paths.isEmpty()) {
            WorkshopFileManifest.standardFiles.forEach { paths.add(it) }
        }
        return paths.sorted()
    }

    private fun normalizeWorkshopRelativePath(fileName: String): String {
        val trimmed = fileName.trim().replace('\\', '/').trimStart('/')
        val mobileMarker = "/backups/mobile-workshop/"
        val mobileIdx = trimmed.indexOf(mobileMarker)
        if (mobileIdx >= 0) {
            return trimmed.substring(mobileIdx + mobileMarker.length).substringAfter('/')
        }
        val workshopMarker = "/workshop/"
        val workshopIdx = trimmed.indexOf(workshopMarker)
        if (workshopIdx >= 0) {
            return trimmed.substring(workshopIdx + workshopMarker.length).substringAfter('/')
        }
        return trimmed
    }

    private fun isMissingWorkshopFileOnDesktop(result: SyncCallResult.Failure): Boolean {
        val message = result.message
        return message.contains("missing under backups/mobile-workshop/", ignoreCase = true) ||
            (
                message.contains("workshop file \"", ignoreCase = true) &&
                    message.contains("not on the desktop", ignoreCase = true)
                )
    }

    private suspend fun syncFileReferenceAfterRestore(
        subfolderId: Long,
        relativePath: String,
        localFile: File,
    ) {
        val normalizedName = relativePath.replace('\\', '/').trimStart('/')
        val basename = File(normalizedName).name
        val refs = db.fileReferenceDao().getBySubfolderOnce(subfolderId)
        val existing = refs.find { ref ->
            val refName = ref.fileName.replace('\\', '/').trimStart('/')
            refName == normalizedName || refName == basename || File(refName).name == basename
        }
        if (existing != null) {
            val storedName = existing.fileName.replace('\\', '/').trimStart('/')
            val fileName = if (storedName == normalizedName) existing.fileName else normalizedName
            val updated = existing.copy(fileName = fileName, filePath = localFile.absolutePath)
            if (updated != existing) {
                db.fileReferenceDao().insert(updated)
            }
        } else {
            db.fileReferenceDao().insert(
                FileReference(
                    subfolderId = subfolderId,
                    fileName = normalizedName,
                    fileType = basename.substringAfterLast('.', "txt"),
                    filePath = localFile.absolutePath,
                ),
            )
        }
    }

    companion object {
        private const val TAG = "SyncFileService"
    }

    private fun listWorkshopFiles(root: File): List<Pair<File, String>> {
        val out = mutableListOf<Pair<File, String>>()
        root.walkTopDown().forEach { file ->
            if (!file.isFile) return@forEach
            val relative = root.toURI().relativize(file.toURI()).path.replace('\\', '/')
            if (relative.isNotBlank() && !relative.contains("..")) {
                out.add(file to relative)
            }
        }
        return out.sortedBy { it.second }
    }
}
