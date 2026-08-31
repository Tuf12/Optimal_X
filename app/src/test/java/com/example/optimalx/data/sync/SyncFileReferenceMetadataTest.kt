package com.example.optimalx.data.sync

import com.example.optimalx.data.imagestudio.ImageStudioMetadata
import com.example.optimalx.data.model.FileReference
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Test

class SyncFileReferenceMetadataTest {

    @Test
    fun fileReferenceMapper_roundTripsMetadataJson() {
        val metadata = ImageStudioMetadata.build(
            prompt = "moonlit lake",
            backend = "sd_cli_local",
            modelId = "flux2-klein-4b",
            tier = "quality",
            aspectRatio = "3:2",
            seed = 7,
            width = 1216,
            height = 832,
        )
        val metadataJson = ImageStudioMetadata.stringify(metadata)
        val row = FileReference(
            subfolderId = 1L,
            fileName = "moonlit-lake.png",
            fileType = "image",
            filePath = "/files/moonlit-lake.png",
            metadataJson = metadataJson,
        )

        val wire = SyncMappers.fileReference(row, subfolderGlobalId = "sub-global")
        assertEquals(metadataJson, wire.metadataJson)
        assertEquals(null, wire.deletedAt)
        assertEquals("moonlit-lake.png", wire.fileName)

        val parsed = ImageStudioMetadata.parse(wire.metadataJson)
        assertNotNull(parsed)
        assertEquals("moonlit lake", parsed?.prompt)
    }

    @Test
    fun fileReferenceMapper_preservesNullMetadataForImports() {
        val row = FileReference(
            subfolderId = 1L,
            fileName = "scan.pdf",
            fileType = "pdf",
            filePath = "/files/scan.pdf",
            metadataJson = null,
        )
        val wire = SyncMappers.fileReference(row, subfolderGlobalId = "sub-global")
        assertEquals(null, wire.metadataJson)
    }
}
