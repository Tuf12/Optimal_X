package com.example.optimalx.data.imagestudio

import com.example.optimalx.data.dao.FileReferenceDao

private val ALLOWED_EXTENSIONS = setOf("png", "webp")

sealed class ImageFileNameResult {
    data class Ok(val fileName: String) : ImageFileNameResult()

    data class Error(
        val code: String,
        val message: String,
        val fileName: String? = null,
    ) : ImageFileNameResult()
}

object ImageStudioFilename {

    fun normalize(input: String?, extension: String = "png"): ImageFileNameResult {
        val defaultExt = if (extension == "webp") "webp" else "png"
        val raw = input?.trim().orEmpty()
        if (raw.isEmpty()) {
            return ImageFileNameResult.Error("invalid_name", "File name is required")
        }
        if (raw.contains('/') || raw.contains('\\') || raw.contains("..")) {
            return ImageFileNameResult.Error(
                "invalid_name",
                "File name cannot contain path separators",
            )
        }

        var stemInput = raw
        var ext = defaultExt
        val dotIndex = raw.lastIndexOf('.')
        if (dotIndex > 0) {
            val maybeExt = raw.substring(dotIndex + 1).lowercase()
            if (maybeExt in ALLOWED_EXTENSIONS) {
                ext = maybeExt
                stemInput = raw.substring(0, dotIndex)
            }
        }

        val stem = stemInput
            .lowercase()
            .replace(Regex("[\\s_]+"), "-")
            .replace(Regex("[^a-z0-9-]+"), "-")
            .replace(Regex("-+"), "-")
            .trim('-')

        if (stem.isEmpty()) {
            return ImageFileNameResult.Error("invalid_name", "File name is required")
        }

        return ImageFileNameResult.Ok("$stem.$ext")
    }

    suspend fun assertUnique(
        dao: FileReferenceDao,
        subfolderId: Long,
        input: String?,
        extension: String = "png",
    ): ImageFileNameResult {
        val normalized = normalize(input, extension)
        if (normalized is ImageFileNameResult.Error) return normalized
        val fileName = (normalized as ImageFileNameResult.Ok).fileName
        val existing = dao.getBySubfolderAndFileName(subfolderId, fileName)
        if (existing != null) {
            return ImageFileNameResult.Error(
                code = "duplicate_name",
                message = "File already exists in this subfolder: $fileName",
                fileName = fileName,
            )
        }
        return ImageFileNameResult.Ok(fileName)
    }
}
