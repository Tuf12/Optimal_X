package com.example.optimalx.data.imagestudio

import com.example.optimalx.data.model.FileReference
import java.io.File

data class ImageGalleryItem(
    val ref: FileReference,
    val caption: String,
    val isGenerated: Boolean,
    val bytesOnDevice: Boolean,
    val folderBadge: String? = null,
)

object ImageGalleryMapper {
    private const val PROMPT_SNIPPET_MAX = 96

    fun fromFileReference(
        ref: FileReference,
        folderBadge: String? = null,
    ): ImageGalleryItem {
        val metadata = ImageStudioMetadata.parse(ref.metadataJson)
        val isGenerated = ImageStudioMetadata.isImageStudioMetadata(ref.metadataJson)
        val caption = when {
            isGenerated && metadata != null -> promptSnippet(metadata.prompt)
            metadata?.source == "import" -> "Imported"
            else -> "Added to folder"
        }
        val bytesOnDevice = runCatching {
            val file = File(ref.filePath)
            file.isFile && file.length() > 0L
        }.getOrDefault(false)
        return ImageGalleryItem(
            ref = ref,
            caption = caption,
            isGenerated = isGenerated,
            bytesOnDevice = bytesOnDevice,
            folderBadge = folderBadge,
        )
    }

    fun fromHubRow(row: FileReferenceWithFolderLabels): ImageGalleryItem =
        fromFileReference(
            ref = row.ref,
            folderBadge = "${row.parentFolderName} / ${row.subfolderName}",
        )

    private fun promptSnippet(prompt: String): String {
        val text = prompt.trim().replace(Regex("\\s+"), " ")
        if (text.isEmpty()) return ""
        if (text.length <= PROMPT_SNIPPET_MAX) return text
        return text.take(PROMPT_SNIPPET_MAX - 1) + "…"
    }
}

enum class ImageGalleryFilter {
    ALL,
    GENERATED,
    IMPORTED,
}
