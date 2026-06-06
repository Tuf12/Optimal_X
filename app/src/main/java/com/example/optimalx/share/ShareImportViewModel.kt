package com.example.optimalx.share

import android.app.Application
import android.content.Context
import android.net.Uri
import android.provider.OpenableColumns
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.example.optimalx.OptimalXApplication
import com.example.optimalx.data.db.AppDatabase
import com.example.optimalx.data.eidos.AppIndexSyncService
import com.example.optimalx.data.model.FileReference
import com.example.optimalx.data.model.ParentFolder
import com.example.optimalx.data.model.Subfolder
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File

data class PickerParent(
    val folder: ParentFolder,
    val subfolders: List<Subfolder>,
    val isExpanded: Boolean = false,
)

sealed class ImportState {
    object Idle : ImportState()
    object Importing : ImportState()
    object Done : ImportState()
    data class Error(val message: String) : ImportState()
}

class ShareImportViewModel(app: Application) : AndroidViewModel(app) {

    private val appRef = app as OptimalXApplication
    private val db = AppDatabase.getInstance(app)
    private val folderRepo = appRef.folderRepository
    private val appIndexSync: AppIndexSyncService = appRef.appIndexSyncService
    private val semanticSync = appRef.semanticSyncService

    private val _folders = MutableStateFlow<List<PickerParent>>(emptyList())
    val folders: StateFlow<List<PickerParent>> = _folders.asStateFlow()

    private val _foldersLoaded = MutableStateFlow(false)
    val foldersLoaded: StateFlow<Boolean> = _foldersLoaded.asStateFlow()

    private val _selectedSubfolderId = MutableStateFlow<Long?>(null)
    val selectedSubfolderId: StateFlow<Long?> = _selectedSubfolderId.asStateFlow()

    private val _importState = MutableStateFlow<ImportState>(ImportState.Idle)
    val importState: StateFlow<ImportState> = _importState.asStateFlow()

    init {
        viewModelScope.launch(Dispatchers.IO) {
            reloadPickerFolders()
            _foldersLoaded.value = true
        }
    }

    private suspend fun reloadPickerFolders(ensureExpandedParentIds: Set<Long> = emptySet()) {
        val parents = db.parentFolderDao().getActiveUserFolders().first()
        val expandedFromState = _folders.value.filter { it.isExpanded }.map { it.folder.id }.toSet()
        val expanded = expandedFromState + ensureExpandedParentIds
        _folders.value = parents.map { parent ->
            val subs = db.subfolderDao().getAllActiveByParent(parent.id).first()
                .filter { !it.isSystemSubfolder }
            PickerParent(parent, subs, isExpanded = expanded.contains(parent.id))
        }
    }

    fun createParentFolder(name: String) {
        val trimmed = name.trim()
        if (trimmed.isEmpty()) return
        viewModelScope.launch(Dispatchers.IO) {
            val id = folderRepo.createParentFolder(trimmed)
            reloadPickerFolders(ensureExpandedParentIds = setOf(id))
        }
    }

    fun createSubfolder(parentId: Long, name: String) {
        val trimmed = name.trim()
        if (trimmed.isEmpty()) return
        viewModelScope.launch(Dispatchers.IO) {
            val id = folderRepo.createSubfolder(parentId, trimmed)
            reloadPickerFolders(ensureExpandedParentIds = setOf(parentId))
            _selectedSubfolderId.value = id
        }
    }

    fun toggleExpand(parentId: Long) {
        _folders.value = _folders.value.map { item ->
            if (item.folder.id == parentId) item.copy(isExpanded = !item.isExpanded)
            else item
        }
    }

    fun selectSubfolder(subfolderId: Long) {
        _selectedSubfolderId.value = subfolderId
    }

    fun importFiles(context: Context, uris: List<Uri>) {
        if (_selectedSubfolderId.value == null || uris.isEmpty()) return
        _importState.value = ImportState.Importing
        viewModelScope.launch(Dispatchers.IO) {
            val subfolderId = _selectedSubfolderId.value!!
            try {
                uris.forEach { uri -> copyAndRecord(context, uri, subfolderId) }
                _importState.value = ImportState.Done
            } catch (e: Exception) {
                _importState.value = ImportState.Error(e.message ?: "Import failed")
            }
        }
    }

    private suspend fun copyAndRecord(context: Context, uri: Uri, subfolderId: Long) {
        val fileName = resolveFileName(context, uri)
        val extension = fileName.substringAfterLast('.', "").lowercase()
        val destDir = File(context.filesDir, "optimalx_files/$subfolderId").apply { mkdirs() }
        val destFile = File(destDir, "${System.currentTimeMillis()}_$fileName")

        withContext(Dispatchers.IO) {
            context.contentResolver.openInputStream(uri)?.use { input ->
                destFile.outputStream().use { output -> input.copyTo(output) }
            }
        }
        db.fileReferenceDao().insert(
            FileReference(
                subfolderId = subfolderId,
                fileName = fileName,
                fileType = extension,
                filePath = destFile.absolutePath,
            )
        )
        appIndexSync.requestSync("share_import_file:$subfolderId")
        semanticSync.requestSync("share_import_file:$subfolderId")
    }

    private fun resolveFileName(context: Context, uri: Uri): String {
        var name = ""
        context.contentResolver.query(uri, null, null, null, null)?.use { cursor ->
            val idx = cursor.getColumnIndex(OpenableColumns.DISPLAY_NAME)
            if (cursor.moveToFirst() && idx >= 0) name = cursor.getString(idx)
        }
        return name.ifEmpty { uri.lastPathSegment?.substringAfterLast('/') ?: "file" }
    }
}
