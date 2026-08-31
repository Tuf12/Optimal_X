package com.example.optimalx.data.imagestudio

import android.content.Context
import com.example.optimalx.data.db.AppDatabase
import com.example.optimalx.data.model.FileReference
import com.example.optimalx.data.sync.SyncFilePaths
import com.example.optimalx.data.sync.SyncGlobalIds
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File

data class SaveGeneratedImageResult(
    val fileReference: FileReference,
    val fileReferenceId: Long,
)

class ImageStudioRepository(
    private val context: Context,
    private val db: AppDatabase,
    private val generationService: ImageGenerationService,
) {

    fun isConfigured(): Boolean = generationService.isConfigured()

    fun estimateCost(request: ImageGenerationRequest): CostEstimate? =
        generationService.estimateCost(request)

    suspend fun generateAndSave(
        subfolderId: Long,
        rawFileName: String,
        request: ImageGenerationRequest,
    ): Result<SaveGeneratedImageResult> = withContext(Dispatchers.IO) {
        runCatching {
            val fileNameResult = ImageStudioFilename.assertUnique(
                dao = db.fileReferenceDao(),
                subfolderId = subfolderId,
                input = rawFileName,
            )
            val fileName = when (fileNameResult) {
                is ImageFileNameResult.Ok -> fileNameResult.fileName
                is ImageFileNameResult.Error -> throw ImageGenerationException(
                    code = fileNameResult.code,
                    message = fileNameResult.message,
                )
            }

            val generation = generationService.generate(request)
            saveGeneratedImage(
                subfolderId = subfolderId,
                fileName = fileName,
                request = request,
                generation = generation,
            )
        }
    }

    suspend fun saveGeneratedImage(
        subfolderId: Long,
        fileName: String,
        request: ImageGenerationRequest,
        generation: ImageGenerationResult,
    ): SaveGeneratedImageResult {
        val storageFileName = storageFileNameForMime(fileName, generation.mimeType)
        val destFile = SyncFilePaths.localAttachmentFile(context, subfolderId, storageFileName)
        destFile.parentFile?.mkdirs()

        try {
            destFile.writeBytes(generation.imageBytes)
        } catch (t: Throwable) {
            destFile.delete()
            throw ImageGenerationException(
                ImageGenerationException.SAVE_FAILED,
                "Image generated but save failed",
                t,
            )
        }

        if (!destFile.isFile || destFile.length() <= 0L) {
            destFile.delete()
            throw ImageGenerationException(
                ImageGenerationException.SAVE_FAILED,
                "Image generated but save failed",
            )
        }

        val tierSpec = ImageStudioModelCatalog.tierSpec(request.tier)
        val dimensions = ImageStudioAspectRatio.resolve(request.aspectRatio)
        val estimate = generationService.estimateCost(request)
        val metadata = ImageStudioMetadata.build(
            prompt = request.prompt,
            negativePrompt = request.negativePrompt,
            backend = ImageStudioModelCatalog.BACKEND_XAI_IMAGINE,
            modelId = generation.modelId,
            tier = request.tier.wireValue,
            aspectRatio = request.aspectRatio.wireValue,
            seed = generation.seed,
            width = dimensions.width,
            height = dimensions.height,
            estimatedCostUsd = estimate?.usd,
            actualCostUsd = generation.actualCostUsd,
            providerRequestId = generation.providerRequestId,
        )

        val ref = FileReference(
            subfolderId = subfolderId,
            fileName = storageFileName,
            fileType = "image",
            filePath = destFile.absolutePath,
            globalId = SyncGlobalIds.newGlobalId(),
            metadataJson = ImageStudioMetadata.stringify(metadata),
        )

        val id = db.fileReferenceDao().insert(ref)
        return SaveGeneratedImageResult(fileReference = ref.copy(id = id), fileReferenceId = id)
    }

    fun observeSubfolderImages(subfolderId: Long) =
        db.fileReferenceDao().getBySubfolder(subfolderId)

    fun observeAllImagesWithFolderLabels() =
        db.fileReferenceDao().observeAllImagesWithFolderLabels()

    private fun storageFileNameForMime(fileName: String, mimeType: String): String {
        val targetExt = when {
            mimeType.contains("png") -> "png"
            mimeType.contains("webp") -> "webp"
            mimeType.contains("jpeg") || mimeType.contains("jpg") -> "jpg"
            else -> fileName.substringAfterLast('.', "png")
        }
        val stem = fileName.substringBeforeLast('.', fileName)
        return "$stem.$targetExt"
    }
}
