package com.example.optimalx.data.eidos

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import kotlin.io.path.createTempDirectory

class ChatVisionImageStoreTest {

    @Test
    fun persistBytes_writesUuidNamedPng() {
        val dir = createTempDirectory(prefix = "chat-vision").toFile()
        try {
            val payload = byteArrayOf(0x89.toByte(), 0x50, 0x4E, 0x47)
            val result = ChatVisionImageStore.persistBytes(dir, payload, "shot.png", "image/png")
            val ok = result as ChatVisionImageStore.Result.Ok
            assertEquals("shot.png", ok.attachment.fileName)
            assertEquals("image/png", ok.attachment.mimeType)
            assertTrue(ok.attachment.storedName.endsWith(".png"))
            assertEquals(payload.toList(), File(dir, ok.attachment.storedName).readBytes().toList())
        } finally {
            dir.deleteRecursively()
        }
    }

    @Test
    fun persistBytes_rejectsTooLargeWhenUndecodable() {
        val dir = createTempDirectory(prefix = "chat-vision").toFile()
        try {
            val bytes = ByteArray(ChatVisionImageStore.MAX_BYTES + 1)
            val result = ChatVisionImageStore.persistBytes(dir, bytes, "big.png", "image/png")
            val err = result as ChatVisionImageStore.Result.Err
            assertTrue(err.message.contains("4 MB"))
        } finally {
            dir.deleteRecursively()
        }
    }

    @Test
    fun sampleSizeFor_powersOfTwoUntilWithinMax() {
        assertEquals(1, ChatVisionImageStore.sampleSizeFor(1024, 768, 2048))
        assertEquals(2, ChatVisionImageStore.sampleSizeFor(4000, 3000, 2048))
        assertEquals(4, ChatVisionImageStore.sampleSizeFor(8000, 6000, 2048))
    }

    @Test
    fun persistBytes_rejectsUnsupportedType() {
        val dir = createTempDirectory(prefix = "chat-vision").toFile()
        try {
            val result = ChatVisionImageStore.persistBytes(
                dir,
                byteArrayOf(1, 2, 3),
                "notes.pdf",
                "application/pdf",
            )
            assertTrue(result is ChatVisionImageStore.Result.Err)
        } finally {
            dir.deleteRecursively()
        }
    }

    @Test
    fun sanitizeFileName_stripsPathAndIllegalChars() {
        assertEquals("shot.png", ChatVisionImageStore.sanitizeFileName("/tmp/nested/shot.png"))
        assertEquals("my_pic.jpg", ChatVisionImageStore.sanitizeFileName("my*pic.jpg"))
    }

    @Test
    fun persistBytes_mapsJpgAliasToJpeg() {
        val dir = createTempDirectory(prefix = "chat-vision").toFile()
        try {
            val result = ChatVisionImageStore.persistBytes(
                dir,
                byteArrayOf(1, 2, 3),
                "photo.JPG",
                "image/jpg",
            )
            val ok = result as ChatVisionImageStore.Result.Ok
            assertEquals("image/jpeg", ok.attachment.mimeType)
            assertEquals("photo.jpg", ok.attachment.fileName)
            assertTrue(ok.attachment.storedName.endsWith(".jpg"))
        } finally {
            dir.deleteRecursively()
        }
    }
}
