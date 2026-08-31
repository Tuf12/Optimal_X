package com.example.optimalx.data.eidos

import android.graphics.Bitmap
import android.graphics.Color
import android.net.Uri
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import java.io.ByteArrayOutputStream
import java.io.File
import kotlin.random.Random

@RunWith(AndroidJUnit4::class)
class ChatVisionImageStoreInstrumentedTest {

    @Test
    fun persistBytes_downscalesLargeStillUnderCap() {
        val context = ApplicationProvider.getApplicationContext<android.content.Context>()
        val dir = File(context.cacheDir, "chat-vision-ingest-test").apply {
            deleteRecursively()
            mkdirs()
        }
        val source = noisyJpeg(3200, 2400)
        try {
            val result = ChatVisionImageStore.persistBytes(dir, source, "photo.jpg", "image/jpeg")
            val ok = result as ChatVisionImageStore.Result.Ok
            assertEquals("image/jpeg", ok.attachment.mimeType)
            assertTrue(ok.attachment.storedName.endsWith(".jpg"))
            val stored = File(dir, ok.attachment.storedName)
            assertTrue(stored.isFile)
            assertTrue(stored.length() in 1..ChatVisionImageStore.MAX_BYTES)
            val decoded = ChatVisionImageStore.decodeThumbnail(
                stored,
                ChatVisionImageStore.INGEST_MAX_DIM,
            )
            checkNotNull(decoded)
            assertTrue(decoded.width <= ChatVisionImageStore.INGEST_MAX_DIM)
            assertTrue(decoded.height <= ChatVisionImageStore.INGEST_MAX_DIM)
            decoded.recycle()
        } finally {
            dir.deleteRecursively()
        }
    }

    @Test
    fun persistFromUri_streamsAndPersistsDownscaledJpeg() {
        val context = ApplicationProvider.getApplicationContext<android.content.Context>()
        val sourceFile = File(context.cacheDir, "chat-vision-uri-source.jpg")
        sourceFile.writeBytes(noisyJpeg(2800, 2100))
        try {
            val result = ChatVisionImageStore.persistFromUri(context, Uri.fromFile(sourceFile))
            val ok = result as ChatVisionImageStore.Result.Ok
            val stored = ChatVisionImageStore.fileFor(context, ok.attachment)
            checkNotNull(stored)
            assertTrue(stored.length() in 1..ChatVisionImageStore.MAX_BYTES)
            ChatVisionImageStore.deleteStored(context, ok.attachment.storedName)
        } finally {
            sourceFile.delete()
        }
    }

    private fun noisyJpeg(width: Int, height: Int): ByteArray {
        val bitmap = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
        val rng = Random(42)
        val pixels = IntArray(width * height) {
            Color.rgb(rng.nextInt(256), rng.nextInt(256), rng.nextInt(256))
        }
        bitmap.setPixels(pixels, 0, width, 0, 0, width, height)
        val out = ByteArrayOutputStream()
        val ok = bitmap.compress(Bitmap.CompressFormat.JPEG, 100, out)
        bitmap.recycle()
        check(ok)
        val bytes = out.toByteArray()
        check(bytes.isNotEmpty())
        return bytes
    }
}
