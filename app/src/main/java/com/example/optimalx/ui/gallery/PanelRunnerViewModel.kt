package com.example.optimalx.ui.gallery

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.example.optimalx.OptimalXApplication
import com.example.optimalx.data.panel.PanelReleaseStore
import com.example.optimalx.ui.workshop.PanelHtmlComposer
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

class PanelRunnerViewModel(
    app: Application,
    private val workshopSubfolderId: Long,
) : AndroidViewModel(app) {

    private val db = (app as OptimalXApplication).database

    private val _panelName = MutableStateFlow("Panel")
    val panelName: StateFlow<String> = _panelName.asStateFlow()

    private val _compositeHtml = MutableStateFlow("<html><body><p>Loading panel…</p></body></html>")
    val compositeHtml: StateFlow<String> = _compositeHtml.asStateFlow()

    init {
        viewModelScope.launch {
            val name = db.subfolderDao().getById(workshopSubfolderId)?.name?.trim().orEmpty()
            if (name.isNotBlank()) {
                _panelName.value = name
            }
            loadPublishedHtml()
        }
    }

    private suspend fun loadPublishedHtml() {
        val ctx = getApplication<Application>().applicationContext
        val html = withContext(Dispatchers.IO) {
            PanelReleaseStore.buildCompositeHtml(ctx, workshopSubfolderId)
                ?: run {
                    PanelReleaseStore.ensurePublishedIfComplete(ctx, db, workshopSubfolderId)
                    PanelReleaseStore.buildCompositeHtml(ctx, workshopSubfolderId)
                }
                ?: db.fileReferenceDao().getBySubfolderOnce(workshopSubfolderId).let { files ->
                    PanelHtmlComposer.buildCompositeHtmlFromFileReferences(files)
                }
        }
        _compositeHtml.value = html
    }
}
