package com.example.optimalx.ui.imagestudio

sealed class ImageStudioGalleryScope {
    data class Subfolder(val subfolderId: Long) : ImageStudioGalleryScope()
    data object Hub : ImageStudioGalleryScope()
}
