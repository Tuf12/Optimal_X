package com.example.optimalx.ui.imagestudio

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.example.optimalx.OptimalXApplication
import com.example.optimalx.data.db.AppDatabase
import com.example.optimalx.data.imagestudio.ImageAspectRatio
import com.example.optimalx.data.imagestudio.ImageGalleryFilter
import com.example.optimalx.data.imagestudio.ImageGalleryItem
import com.example.optimalx.data.imagestudio.ImageGalleryMapper
import com.example.optimalx.data.imagestudio.ImageGenerationException
import com.example.optimalx.data.imagestudio.ImageGenerationRequest
import com.example.optimalx.data.imagestudio.ImageStudioDraft
import com.example.optimalx.data.imagestudio.ImageStudioMetadata
import com.example.optimalx.data.imagestudio.ImageStudioPanelPrefs
import com.example.optimalx.data.imagestudio.ImageStudioPanelPrefsCodec
import com.example.optimalx.data.imagestudio.ImageStudioPanelState
import com.example.optimalx.data.imagestudio.ImageStudioPreferences
import com.example.optimalx.data.imagestudio.ImageStudioRepository
import com.example.optimalx.data.imagestudio.ImageTier
import com.example.optimalx.data.imagestudio.SaveGeneratedImageResult
import com.example.optimalx.data.imagestudio.XaiImageGenerationService
import com.example.optimalx.data.imagestudio.imageGenerationApiKeyProvider
import com.example.optimalx.data.model.FileReference
import com.example.optimalx.data.repository.EditorRepository
import com.example.optimalx.data.repository.PanelStateRepository
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch

sealed class ImageStudioJobUiState {
    data object Idle : ImageStudioJobUiState()
    data object Running : ImageStudioJobUiState()
    data class Success(val result: SaveGeneratedImageResult) : ImageStudioJobUiState()
    data class Error(val code: String, val message: String) : ImageStudioJobUiState()
}

data class ImageStudioFormUiState(
    val fileName: String = "",
    val prompt: String = "",
    val negativePrompt: String = "",
    val tier: ImageTier = ImageTier.DRAFT,
    val aspectRatio: ImageAspectRatio = ImageAspectRatio.DEFAULT,
    val showNegativePrompt: Boolean = false,
    val nameError: String? = null,
    val formError: String? = null,
)

class ImageStudioViewModel(
    app: Application,
    private val saveSubfolderId: Long,
    private val galleryScope: ImageStudioGalleryScope = ImageStudioGalleryScope.Subfolder(saveSubfolderId),
) : AndroidViewModel(app) {

    private val appRef = app as OptimalXApplication
    private val db = AppDatabase.getInstance(app)
    private val panelStateRepository = PanelStateRepository(db)
    private val repository = ImageStudioRepository(
        context = app.applicationContext,
        db = db,
        generationService = XaiImageGenerationService(
            apiKeyProvider = imageGenerationApiKeyProvider(app),
        ),
    )
    private val editorRepository = EditorRepository(
        db = db,
        semanticIndexer = appRef.semanticIndexer,
        semanticSync = appRef.semanticSyncService,
        semanticChunkBuilder = appRef.semanticChunkBuilder,
    )

    private val _form = MutableStateFlow(ImageStudioFormUiState())
    val form: StateFlow<ImageStudioFormUiState> = _form.asStateFlow()

    private val _jobState = MutableStateFlow<ImageStudioJobUiState>(ImageStudioJobUiState.Idle)
    val jobState: StateFlow<ImageStudioJobUiState> = _jobState.asStateFlow()

    private val _galleryFilter = MutableStateFlow(ImageGalleryFilter.ALL)
    val galleryFilter: StateFlow<ImageGalleryFilter> = _galleryFilter.asStateFlow()

    private val _selectedGalleryId = MutableStateFlow<Long?>(null)
    val selectedGalleryId: StateFlow<Long?> = _selectedGalleryId.asStateFlow()

    private val _isConfigured = MutableStateFlow(repository.isConfigured())
    val isConfigured: StateFlow<Boolean> = _isConfigured.asStateFlow()

    val costEstimateLabel: String
        get() {
            val estimate = repository.estimateCost(currentRequest())
            return estimate?.label ?: "Billed by xAI"
        }

    private val allGalleryItems = when (galleryScope) {
        ImageStudioGalleryScope.Hub -> repository
            .observeAllImagesWithFolderLabels()
            .map { rows ->
                rows
                    .sortedByDescending { row -> sortTimestamp(row.ref) }
                    .map(ImageGalleryMapper::fromHubRow)
            }
        is ImageStudioGalleryScope.Subfolder -> repository
            .observeSubfolderImages(galleryScope.subfolderId)
            .map { refs ->
                refs
                    .filter { it.fileType.equals("image", ignoreCase = true) }
                    .sortedByDescending(::sortTimestamp)
                    .map { ref -> ImageGalleryMapper.fromFileReference(ref) }
            }
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    val filteredGalleryItems: StateFlow<List<ImageGalleryItem>> = combine(
        allGalleryItems,
        _galleryFilter,
    ) { items, filter ->
        applyGalleryFilter(items, filter)
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    private var generateJob: Job? = null
    private var prefsSaveJob: Job? = null

    init {
        viewModelScope.launch {
            val raw = panelStateRepository.loadStateJson(saveSubfolderId, ImageStudioPanelState.SCOPE_KEY)
            val prefs = ImageStudioPanelPrefsCodec.decode(raw)
            val settingsDefaults = ImageStudioPreferences.readDefaults(appRef)
            val hasSavedPanelState = !raw.isNullOrBlank()
            _form.value = ImageStudioFormUiState(
                fileName = prefs.fileName,
                prompt = prefs.prompt,
                negativePrompt = prefs.negativePrompt,
                tier = if (hasSavedPanelState) prefs.tierEnum() else settingsDefaults.tier,
                aspectRatio = if (hasSavedPanelState) prefs.aspectEnum() else settingsDefaults.aspectRatio,
                showNegativePrompt = prefs.negativePrompt.isNotBlank(),
            )
        }
    }

    fun refreshConfiguration() {
        _isConfigured.value = repository.isConfigured()
    }

    fun updateFileName(value: String) {
        _form.value = _form.value.copy(fileName = value, nameError = null, formError = null)
        schedulePrefsSave()
    }

    fun updatePrompt(value: String) {
        _form.value = _form.value.copy(prompt = value, formError = null)
        schedulePrefsSave()
    }

    fun updateNegativePrompt(value: String) {
        _form.value = _form.value.copy(negativePrompt = value)
        schedulePrefsSave()
    }

    fun setShowNegativePrompt(show: Boolean) {
        _form.value = _form.value.copy(showNegativePrompt = show)
    }

    fun updateTier(tier: ImageTier) {
        _form.value = _form.value.copy(tier = tier)
        schedulePrefsSave()
    }

    fun updateAspectRatio(aspectRatio: ImageAspectRatio) {
        _form.value = _form.value.copy(aspectRatio = aspectRatio)
        schedulePrefsSave()
    }

    fun setGalleryFilter(filter: ImageGalleryFilter) {
        _galleryFilter.value = filter
    }

    fun selectGalleryItem(item: ImageGalleryItem?) {
        _selectedGalleryId.value = item?.ref?.id
    }

    fun applyDraft(draft: ImageStudioDraft) {
        _form.value = _form.value.copy(
            prompt = draft.prompt,
            negativePrompt = draft.negativePrompt,
            showNegativePrompt = draft.negativePrompt.isNotBlank(),
            fileName = draft.suggestedName ?: _form.value.fileName,
            tier = draft.tier?.let { ImageTier.fromWire(it) } ?: _form.value.tier,
            aspectRatio = draft.aspectRatio?.let { ImageAspectRatio.fromWire(it) } ?: _form.value.aspectRatio,
            formError = null,
            nameError = null,
        )
        schedulePrefsSave(immediate = true)
    }

    fun reloadSettingsFromGallery(item: ImageGalleryItem) {
        val metadata = ImageStudioMetadata.parse(item.ref.metadataJson) ?: return
        _form.value = _form.value.copy(
            prompt = metadata.prompt,
            negativePrompt = metadata.negativePrompt,
            tier = ImageTier.fromWire(metadata.tier) ?: _form.value.tier,
            aspectRatio = ImageAspectRatio.fromWire(metadata.aspectRatio) ?: _form.value.aspectRatio,
            showNegativePrompt = metadata.negativePrompt.isNotBlank(),
            fileName = item.ref.fileName.substringBeforeLast('.', item.ref.fileName),
            nameError = null,
            formError = null,
        )
        _selectedGalleryId.value = item.ref.id
        schedulePrefsSave()
    }

    fun deleteGalleryImage(item: ImageGalleryItem) {
        viewModelScope.launch(Dispatchers.IO) {
            runCatching {
                editorRepository.deleteFileReference(item.ref.id)
            }
            if (_selectedGalleryId.value == item.ref.id) {
                _selectedGalleryId.value = null
            }
            val job = _jobState.value
            if (job is ImageStudioJobUiState.Success && job.result.fileReferenceId == item.ref.id) {
                _jobState.value = ImageStudioJobUiState.Idle
            }
        }
    }

    fun generate() {
        if (_jobState.value is ImageStudioJobUiState.Running) return
        val state = _form.value
        if (state.fileName.isBlank()) {
            _form.value = state.copy(nameError = "File name is required")
            return
        }
        if (state.prompt.isBlank()) {
            _form.value = state.copy(formError = "Prompt is required")
            return
        }
        if (!isConfigured.value) {
            _form.value = state.copy(formError = "Add your xAI API key in Settings → AI")
            return
        }

        generateJob?.cancel()
        _jobState.value = ImageStudioJobUiState.Running
        generateJob = viewModelScope.launch {
            val result = repository.generateAndSave(
                subfolderId = saveSubfolderId,
                rawFileName = state.fileName,
                request = currentRequest(),
            )
            if (!isActive) return@launch
            result.fold(
                onSuccess = { saved ->
                    _jobState.value = ImageStudioJobUiState.Success(saved)
                    _form.value = _form.value.copy(nameError = null, formError = null)
                    _selectedGalleryId.value = saved.fileReferenceId
                },
                onFailure = { error ->
                    when (error) {
                        is ImageGenerationException -> {
                            if (error.code == ImageGenerationException.DUPLICATE_NAME) {
                                _form.value = _form.value.copy(
                                    nameError = error.message,
                                    formError = null,
                                )
                            } else {
                                _form.value = _form.value.copy(formError = error.message)
                            }
                            _jobState.value = ImageStudioJobUiState.Error(error.code, error.message)
                        }
                        else -> {
                            val message = error.message ?: "Generation failed"
                            _form.value = _form.value.copy(formError = message)
                            _jobState.value = ImageStudioJobUiState.Error(
                                ImageGenerationException.API_ERROR,
                                message,
                            )
                        }
                    }
                },
            )
        }
    }

    fun cancelGenerate() {
        generateJob?.cancel()
        generateJob = null
        if (_jobState.value is ImageStudioJobUiState.Running) {
            _jobState.value = ImageStudioJobUiState.Idle
        }
    }

    fun clearResult() {
        _jobState.value = ImageStudioJobUiState.Idle
    }

    fun prepareRegenerate() {
        val currentName = _form.value.fileName.trim()
        val suggested = when {
            currentName.isBlank() -> ""
            currentName.endsWith("-v2", ignoreCase = true) -> currentName
            else -> "$currentName-v2"
        }
        _form.value = _form.value.copy(fileName = suggested, nameError = null, formError = null)
        _jobState.value = ImageStudioJobUiState.Idle
        schedulePrefsSave()
    }

    fun startOver() {
        cancelGenerate()
        _form.value = ImageStudioFormUiState()
        _jobState.value = ImageStudioJobUiState.Idle
        _selectedGalleryId.value = null
        schedulePrefsSave(immediate = true)
    }

    private fun currentRequest(): ImageGenerationRequest = ImageGenerationRequest(
        prompt = _form.value.prompt,
        negativePrompt = _form.value.negativePrompt,
        tier = _form.value.tier,
        aspectRatio = _form.value.aspectRatio,
    )

    private fun schedulePrefsSave(immediate: Boolean = false) {
        prefsSaveJob?.cancel()
        prefsSaveJob = viewModelScope.launch {
            if (!immediate) delay(700)
            val state = _form.value
            val prefs = ImageStudioPanelPrefs(
                fileName = state.fileName,
                prompt = state.prompt,
                negativePrompt = state.negativePrompt,
                tier = state.tier.wireValue,
                aspectRatio = state.aspectRatio.wireValue,
            )
            panelStateRepository.saveStateJson(
                workshopSubfolderId = saveSubfolderId,
                scopeKey = ImageStudioPanelState.SCOPE_KEY,
                stateJson = ImageStudioPanelPrefsCodec.encode(prefs),
            )
        }
    }

    private fun applyGalleryFilter(
        items: List<ImageGalleryItem>,
        filter: ImageGalleryFilter,
    ): List<ImageGalleryItem> = when (filter) {
        ImageGalleryFilter.ALL -> items
        ImageGalleryFilter.GENERATED -> items.filter { it.isGenerated }
        ImageGalleryFilter.IMPORTED -> items.filter { !it.isGenerated }
    }

    private fun sortTimestamp(ref: FileReference): Long {
        val metadata = ImageStudioMetadata.parse(ref.metadataJson)
        return if (ImageStudioMetadata.isImageStudioMetadata(ref.metadataJson) &&
            metadata?.createdAt != null
        ) {
            metadata.createdAt
        } else {
            ref.createdAt
        }
    }

    override fun onCleared() {
        prefsSaveJob?.cancel()
        generateJob?.cancel()
        super.onCleared()
    }
}
