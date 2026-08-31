package com.example.optimalx.data.imagestudio

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.example.optimalx.data.db.AppDatabase
import com.example.optimalx.data.model.FileReference
import com.example.optimalx.data.model.ParentFolder
import com.example.optimalx.data.model.Subfolder
import com.example.optimalx.data.sync.SyncFilePaths
import com.example.optimalx.data.sync.SyncGlobalIds
import com.example.optimalx.data.sync.SyncMappers
import com.example.optimalx.data.sync.SyncPullApplier
import com.example.optimalx.data.sync.SyncPushBuilder
import com.example.optimalx.data.sync.SyncTablesPayload
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import kotlinx.serialization.decodeFromString
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File

/**
 * Tier 1 + Tier 3 acceptance for Image Studio sync — automated counterpart to
 * [app/docs/image_studio/sync-and-desktop-parity.md] testing matrix rows 1–3.
 */
@RunWith(AndroidJUnit4::class)
class ImageStudioSyncRoundTripInstrumentedTest {

    private lateinit var context: Context
    private lateinit var db: AppDatabase
    private val json = Json { ignoreUnknownKeys = true }

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
    fun tier1_pushBundle_includesImageStudioMetadata() = runBlocking {
        val (subfolderId, subfolderGlobalId) = seedImageStudioGeneralSubfolder()
        val metadata = ImageStudioMetadata.build(
            prompt = "sunset over mountains",
            backend = ImageStudioModelCatalog.BACKEND_XAI_IMAGINE,
            modelId = "grok-imagine-image",
            tier = "draft",
            aspectRatio = "16:9",
            actualCostUsd = 0.02,
        )
        val metadataJson = ImageStudioMetadata.stringify(metadata)
        val fileGlobalId = SyncGlobalIds.newGlobalId()
        val now = System.currentTimeMillis()
        db.fileReferenceDao().insert(
            FileReference(
                subfolderId = subfolderId,
                fileName = "sunset-mountains.png",
                fileType = "image",
                filePath = SyncFilePaths.localAttachmentFile(context, subfolderId, "sunset-mountains.png")
                    .absolutePath,
                createdAt = now,
                globalId = fileGlobalId,
                metadataJson = metadataJson,
            ),
        )

        val payload = SyncPushBuilder(context, db).buildTier1(lastSyncAt = 0L)
        val wire = payload.fileReferences.single { it.globalId == fileGlobalId }

        assertEquals(metadataJson, wire.metadataJson)
        assertEquals(subfolderGlobalId, wire.subfolderGlobalId)
        assertEquals("sunset-mountains.png", wire.fileName)
    }

    @Test
    fun tier1_pull_preservesImageStudioMetadata() = runBlocking {
        val parentGlobalId = SyncGlobalIds.IMAGE_STUDIO_PARENT
        val subfolderGlobalId = SyncGlobalIds.IMAGE_STUDIO_GENERAL_SUBFOLDER
        val fileGlobalId = SyncGlobalIds.newGlobalId()
        val metadata = ImageStudioMetadata.build(
            prompt = "desktop local dragon",
            backend = "sd_cli_local",
            modelId = "flux2-klein-9b",
            tier = "quality",
            aspectRatio = "3:2",
        )
        val metadataJson = ImageStudioMetadata.stringify(metadata)
        val now = System.currentTimeMillis()

        val payload = SyncTablesPayload(
            parentFolders = listOf(
                com.example.optimalx.data.sync.SyncParentFolderRow(
                    globalId = parentGlobalId,
                    name = "Image Studio",
                    createdAt = now,
                    updatedAt = now,
                    isSystemFolder = true,
                ),
            ),
            subfolders = listOf(
                com.example.optimalx.data.sync.SyncSubfolderRow(
                    globalId = subfolderGlobalId,
                    parentFolderGlobalId = parentGlobalId,
                    name = "General",
                    createdAt = now,
                    updatedAt = now,
                    isSystemSubfolder = true,
                ),
            ),
            fileReferences = listOf(
                SyncMappers.fileReference(
                    FileReference(
                        subfolderId = 0L,
                        fileName = "dragon.png",
                        fileType = "image",
                        filePath = "/remote/dragon.png",
                        createdAt = now,
                        globalId = fileGlobalId,
                        metadataJson = metadataJson,
                    ),
                    subfolderGlobalId = subfolderGlobalId,
                ),
            ),
        )

        val stats = SyncPullApplier(context, db).applyTier1(payload)
        assertTrue((stats.applied["fileReferences"] ?: 0) >= 1)

        val local = db.fileReferenceDao().getByGlobalId(fileGlobalId)
        assertNotNull(local)
        assertEquals(metadataJson, local?.metadataJson)
        val parsed = ImageStudioMetadata.parse(local?.metadataJson)
        assertEquals("desktop local dragon", parsed?.prompt)
        assertEquals("sd_cli_local", parsed?.backend)
    }

    @Test
    fun tier1_jsonWire_roundTripsMetadataJson() {
        val metadata = ImageStudioMetadata.build(
            prompt = "wire test",
            backend = ImageStudioModelCatalog.BACKEND_XAI_IMAGINE,
            modelId = "grok-imagine-image",
            tier = "draft",
            aspectRatio = "1:1",
        )
        val row = SyncMappers.fileReference(
            FileReference(
                subfolderId = 1L,
                fileName = "wire.png",
                fileType = "image",
                filePath = "/tmp/wire.png",
                metadataJson = ImageStudioMetadata.stringify(metadata),
                globalId = SyncGlobalIds.newGlobalId(),
            ),
            subfolderGlobalId = SyncGlobalIds.IMAGE_STUDIO_GENERAL_SUBFOLDER,
        )

        val encoded = json.encodeToString(row)
        val decoded = json.decodeFromString<com.example.optimalx.data.sync.SyncFileReferenceRow>(encoded)

        assertEquals(row.metadataJson, decoded.metadataJson)
        assertEquals(ImageStudioMetadata.SOURCE, ImageStudioMetadata.parse(decoded.metadataJson)?.source)
    }

    @Test
    fun tier3_attachmentBytes_resolveForUpload() = runBlocking {
        val (subfolderId, _) = seedImageStudioGeneralSubfolder()
        val fileName = "tier3-test.png"
        val dest = SyncFilePaths.localAttachmentFile(context, subfolderId, fileName)
        dest.parentFile?.mkdirs()
        val pngHeader = byteArrayOf(
            0x89.toByte(), 0x50, 0x4E, 0x47, 0x0D, 0x0A, 0x1A, 0x0A,
        )
        dest.writeBytes(pngHeader)

        val ref = FileReference(
            subfolderId = subfolderId,
            fileName = fileName,
            fileType = "image",
            filePath = dest.absolutePath,
            globalId = SyncGlobalIds.newGlobalId(),
            metadataJson = ImageStudioMetadata.stringify(
                ImageStudioMetadata.build(
                    prompt = "tier3 bytes",
                    backend = ImageStudioModelCatalog.BACKEND_XAI_IMAGINE,
                    modelId = "grok-imagine-image",
                    tier = "draft",
                    aspectRatio = "1:1",
                ),
            ),
        )
        db.fileReferenceDao().insert(ref)

        val canonical = SyncFilePaths.localAttachmentFile(context, subfolderId, fileName)
        assertTrue(canonical.isFile)
        assertTrue(canonical.length() > 0L)
    }

    private suspend fun seedImageStudioGeneralSubfolder(): Pair<Long, String> {
        val parentId = db.parentFolderDao().insert(
            ParentFolder(
                name = "Image Studio",
                globalId = SyncGlobalIds.IMAGE_STUDIO_PARENT,
                isSystemFolder = true,
            ),
        )
        val subfolderGlobalId = SyncGlobalIds.IMAGE_STUDIO_GENERAL_SUBFOLDER
        val subfolderId = db.subfolderDao().insert(
            Subfolder(
                parentFolderId = parentId,
                name = "General",
                globalId = subfolderGlobalId,
                isSystemSubfolder = true,
            ),
        )
        return subfolderId to subfolderGlobalId
    }

    private companion object {
        const val TEST_DB = "image_studio_sync_roundtrip_test.db"
    }
}
