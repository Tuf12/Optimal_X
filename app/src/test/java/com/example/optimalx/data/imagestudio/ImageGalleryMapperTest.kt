package com.example.optimalx.data.imagestudio

import com.example.optimalx.data.model.FileReference
import org.junit.Assert.assertEquals
import org.junit.Test

class ImageGalleryMapperTest {

    @Test
    fun fromHubRow_includesFolderBadge() {
        val ref = FileReference(
            id = 1L,
            subfolderId = 10L,
            fileName = "cover.png",
            fileType = "image",
            filePath = "/tmp/cover.png",
        )
        val item = ImageGalleryMapper.fromHubRow(
            FileReferenceWithFolderLabels(
                ref = ref,
                subfolderName = "General",
                parentFolderName = "Image Studio",
            ),
        )
        assertEquals("Image Studio / General", item.folderBadge)
        assertEquals("cover.png", item.ref.fileName)
    }
}
