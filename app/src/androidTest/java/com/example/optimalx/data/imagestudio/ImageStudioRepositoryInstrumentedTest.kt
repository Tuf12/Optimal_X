package com.example.optimalx.data.imagestudio

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.example.optimalx.data.db.AppDatabase
import com.example.optimalx.data.model.Subfolder
import com.example.optimalx.data.sync.SyncGlobalIds
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File

@RunWith(AndroidJUnit4::class)
class ImageStudioRepositoryInstrumentedTest {

    private lateinit var context: Context
    private lateinit var db: AppDatabase

    @Before
    fun setUp() {
        context = ApplicationProvider.getApplicationContext()
        context.deleteDatabase(TEST_DB)
        db = Room.inMemoryDatabaseBuilder(context, AppDatabase::class.java)
            .allowMainThreadQueries()
            .build()
    }

    @After
    fun tearDown() {
        db.close()
        context.deleteDatabase(TEST_DB)
    }

    @Test
    fun generateAndSave_persistsFileReferenceWithMetadata() = runBlocking {
        val parentId = db.parentFolderDao().insert(
            com.example.optimalx.data.model.ParentFolder(
                name = "Jobs",
                globalId = SyncGlobalIds.newGlobalId(),
            ),
        )
        val subfolderId = db.subfolderDao().insert(
            Subfolder(
                parentFolderId = parentId,
                name = "Art",
                globalId = SyncGlobalIds.newGlobalId(),
            ),
        )

        val fakeBytes = byteArrayOf(
            0x89.toByte(), 0x50, 0x4E, 0x47, 0x0D, 0x0A, 0x1A, 0x0A,
        )
        val fakeService = FakeImageGenerationService(
            ImageGenerationResult(
                imageBytes = fakeBytes,
                mimeType = "image/png",
                modelId = "grok-imagine-image",
                seed = null,
                width = 1024,
                height = 1024,
                actualCostUsd = 0.02,
                providerRequestId = "req-test",
            ),
        )
        val repo = ImageStudioRepository(context, db, fakeService)

        val request = ImageGenerationRequest(
            prompt = "A red dragon on a cliff",
            tier = ImageTier.DRAFT,
            aspectRatio = ImageAspectRatio.SQUARE,
        )
        val saved = repo.generateAndSave(
            subfolderId = subfolderId,
            rawFileName = "cover art",
            request = request,
        ).getOrThrow()

        assertTrue(saved.fileReferenceId > 0L)
        assertEquals("cover-art.png", saved.fileReference.fileName)
        assertEquals("image", saved.fileReference.fileType)
        assertNotNull(saved.fileReference.metadataJson)

        val metadata = ImageStudioMetadata.parse(saved.fileReference.metadataJson)
        assertEquals("A red dragon on a cliff", metadata?.prompt)
        assertEquals(ImageStudioModelCatalog.BACKEND_XAI_IMAGINE, metadata?.backend)
        assertEquals("draft", metadata?.tier)

        val stored = File(saved.fileReference.filePath)
        assertTrue(stored.isFile)
        assertTrue(stored.length() > 0)

        val duplicate = repo.generateAndSave(subfolderId, "cover-art", request)
        assertTrue(duplicate.isFailure)
        val err = duplicate.exceptionOrNull() as ImageGenerationException
        assertEquals(ImageGenerationException.DUPLICATE_NAME, err.code)
    }

    @Test
    fun parseGenerationResponse_decodesB64Json() {
        val encoded = "iVBORw0KGgoAAAABAAAAAA=="
        val body = """
            {
              "data": [{ "b64_json": "$encoded" }],
              "id": "img_req_1"
            }
        """.trimIndent()

        val result = XaiImageGenerationService.parseGenerationResponse(
            responseText = body,
            modelId = "grok-imagine-image",
            width = 1024,
            height = 1024,
            estimatedCostUsd = 0.02,
            download = { error("should not download") },
        )

        assertEquals("grok-imagine-image", result.modelId)
        assertEquals("image/png", result.mimeType)
        assertEquals("img_req_1", result.providerRequestId)
    }

    private class FakeImageGenerationService(
        private val result: ImageGenerationResult,
    ) : ImageGenerationService {
        override suspend fun generate(request: ImageGenerationRequest): ImageGenerationResult = result
        override fun estimateCost(request: ImageGenerationRequest): CostEstimate? =
            ImageStudioModelCatalog.estimateCost(request.tier)
        override fun isConfigured(): Boolean = true
    }

    private companion object {
        const val TEST_DB = "image_studio_repo_test.db"
    }
}
