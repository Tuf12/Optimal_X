package com.example.optimalx.data.eidos

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.ImageDecoder
import android.graphics.Matrix
import android.media.ExifInterface
import android.net.Uri
import android.os.Build
import android.provider.OpenableColumns
import android.util.Base64
import androidx.annotation.RequiresApi
import java.io.ByteArrayOutputStream
import java.io.File
import java.util.UUID

/** Device-local chat-vision bytes under `filesDir/chat-images/`. Not Files-panel rows. */
object ChatVisionImageStore {
    const val MAX_BYTES = 4 * 1024 * 1024
    const val INGEST_MAX_DIM = 2048
    const val DIR_NAME = "chat-images"
    const val CAPTURE_DIR_NAME = "chat-vision-capture"

    private const val INGEST_JPEG_QUALITY = 85
    private val INGEST_JPEG_QUALITIES = intArrayOf(INGEST_JPEG_QUALITY, 70, 55)
    private const val SOURCE_READ_MAX_BYTES = 40L * 1024 * 1024
    private const val COPY_BUFFER_BYTES = 64 * 1024

    private val MIME_TO_EXT = mapOf(
        "image/png" to ".png",
        "image/jpeg" to ".jpg",
        "image/jpg" to ".jpg",
        "image/webp" to ".webp",
        "image/gif" to ".gif",
    )
    private val EXT_TO_MIME = mapOf(
        ".png" to "image/png",
        ".jpg" to "image/jpeg",
        ".jpeg" to "image/jpeg",
        ".webp" to "image/webp",
        ".gif" to "image/gif",
    )
    private val HEIC_MIMES = setOf("image/heic", "image/heif")

    sealed class Result {
        data class Ok(val attachment: ChatVisionAttachment) : Result()
        data class Err(val message: String) : Result()
    }

    fun dir(context: Context): File = File(context.filesDir, DIR_NAME).apply { mkdirs() }

    fun captureDir(context: Context): File =
        File(context.cacheDir, CAPTURE_DIR_NAME).apply { mkdirs() }

    fun storedFile(context: Context, storedName: String): File? {
        val name = ChatVisionAttachmentCodec.safeStoredName(storedName) ?: return null
        val root = dir(context).canonicalFile
        val file = File(root, name).canonicalFile
        if (file == root) return null
        if (!file.path.startsWith(root.path + File.separator)) return null
        return file
    }

    data class Encoded(
        val mimeType: String,
        val base64: String,
    ) {
        val dataUrl: String get() = "data:$mimeType;base64,$base64"
    }

    fun fileFor(context: Context, attachment: ChatVisionAttachment): File? {
        val file = storedFile(context, attachment.storedName) ?: return null
        return file.takeIf { it.isFile && it.length() > 0L }
    }

    fun filePathsForAttachment(context: Context, attachment: ChatVisionAttachment?): List<String> {
        if (attachment == null) return emptyList()
        val file = fileFor(context, attachment) ?: return emptyList()
        return listOf(file.absolutePath)
    }

    fun filePathsForJson(context: Context, imageAttachmentJson: String?): List<String> {
        return filePathsForAttachment(
            context,
            ChatVisionAttachmentCodec.parseAttachmentJson(imageAttachmentJson),
        )
    }

    fun persistFromUri(context: Context, uri: Uri): Result {
        val fileName = queryDisplayName(context, uri) ?: "image.png"
        val mime = context.contentResolver.getType(uri)?.lowercase()?.trim().orEmpty()
            .ifEmpty { mimeFromFileName(fileName) }
        val knownSize = querySize(context, uri)
        if (knownSize != null && knownSize > SOURCE_READ_MAX_BYTES) {
            return Result.Err("Image is too large to attach.")
        }
        val temp = File(captureDir(context), "ingest_${UUID.randomUUID()}.bin")
        try {
            return when (copyUriToFile(context, uri, temp, SOURCE_READ_MAX_BYTES)) {
                CopyResult.Ok -> persistFromSourceFile(dir(context), temp, fileName, mime)
                CopyResult.TooLarge -> Result.Err("Image is too large to attach.")
                CopyResult.Failed -> Result.Err("Could not read that image.")
            }
        } finally {
            temp.delete()
        }
    }

    fun persistFromBitmap(
        context: Context,
        bitmap: Bitmap,
        fileName: String = "photo.jpg",
    ): Result {
        val scaled = scaleToMax(bitmap, INGEST_MAX_DIM)
        val bytes = compressJpegUnderCap(scaled)
        if (scaled != bitmap) scaled.recycle()
        if (bytes == null || bytes.isEmpty()) {
            return Result.Err("Could not read that image.")
        }
        return persistBytes(dir(context), bytes, fileName, "image/jpeg")
    }

    fun persistBytes(
        dir: File,
        bytes: ByteArray,
        fileName: String,
        mimeType: String,
    ): Result {
        if (bytes.isEmpty()) {
            return Result.Err("Could not read that image.")
        }
        val temp = File.createTempFile("chat-vision-ingest", ".bin")
        try {
            temp.writeBytes(bytes)
            return persistFromSourceFile(dir, temp, fileName, mimeType)
        } finally {
            temp.delete()
        }
    }

    fun deleteStored(context: Context, storedName: String) {
        storedFile(context, storedName)?.delete()
    }

    fun decodeThumbnail(file: File, maxPx: Int = 512): Bitmap? {
        val bounds = peekBounds(file)
        if (bounds.width <= 0 || bounds.height <= 0) {
            return BitmapFactory.decodeFile(file.absolutePath)
        }
        val opts = BitmapFactory.Options().apply {
            inSampleSize = sampleSizeFor(bounds.width, bounds.height, maxPx)
        }
        return BitmapFactory.decodeFile(file.absolutePath, opts)
    }

    /**
     * Downscale and base64-encode for provider multimodal parts.
     * HEIC/GIF become JPEG. Returns null if the file cannot be decoded.
     */
    fun encodeForProvider(file: File, maxDim: Int = 1400): Encoded? {
        if (!file.isFile || file.length() <= 0L) return null
        val bitmap = decodeThumbnail(file, maxDim) ?: return null
        val scaled = scaleToMax(bitmap, maxDim)
        val sourceMime = mimeFromFileName(file.name).ifBlank { "image/jpeg" }
        val format = when {
            sourceMime == "image/png" -> Bitmap.CompressFormat.PNG
            sourceMime == "image/webp" && Build.VERSION.SDK_INT >= 30 -> Bitmap.CompressFormat.WEBP_LOSSY
            else -> Bitmap.CompressFormat.JPEG
        }
        val outMime = when (format) {
            Bitmap.CompressFormat.PNG -> "image/png"
            Bitmap.CompressFormat.WEBP_LOSSY -> "image/webp"
            else -> "image/jpeg"
        }
        val out = ByteArrayOutputStream()
        val ok = scaled.compress(format, 85, out)
        if (scaled != bitmap) scaled.recycle()
        bitmap.recycle()
        if (!ok) return null
        val bytes = out.toByteArray()
        if (bytes.isEmpty()) return null
        return Encoded(
            mimeType = outMime,
            base64 = Base64.encodeToString(bytes, Base64.NO_WRAP),
        )
    }

    fun clipboardImageUri(context: Context): Uri? {
        val clipboard = context.getSystemService(ClipboardManager::class.java) ?: return null
        val clip = clipboard.primaryClip ?: return null
        return firstImageUri(context, clip)
    }

    fun firstImageUri(context: Context, clip: ClipData): Uri? {
        for (i in 0 until clip.itemCount) {
            val uri = clip.getItemAt(i).uri ?: continue
            val type = context.contentResolver.getType(uri)
            if (type.isNullOrBlank() || type.startsWith("image/")) return uri
        }
        return null
    }

    fun createCaptureFile(context: Context): File {
        val file = File(captureDir(context), "capture_${System.currentTimeMillis()}.jpg")
        file.createNewFile()
        return file
    }

    internal fun sampleSizeFor(width: Int, height: Int, maxPx: Int): Int {
        if (width <= 0 || height <= 0 || maxPx <= 0) return 1
        var sample = 1
        while (width / sample > maxPx || height / sample > maxPx) {
            sample *= 2
        }
        return sample
    }

    private data class PreparedPayload(
        val bytes: ByteArray,
        val mimeType: String,
        val ext: String,
        val fileName: String,
    )

    private data class ImageKind(
        val mimeType: String,
        val ext: String,
        val heic: Boolean,
    )

    private enum class CopyResult { Ok, TooLarge, Failed }

    private fun persistFromSourceFile(
        dir: File,
        source: File,
        fileName: String,
        mimeType: String,
    ): Result {
        val sanitizedName = sanitizeFileName(fileName)
        val kind = classifyKind(mimeType, sanitizedName)
            ?: return Result.Err("Use a PNG, JPEG, WebP, or GIF image.")
        val prepared = ingestNormalized(source, sanitizedName, kind)
            ?: fallbackOriginal(source, sanitizedName, kind)
            ?: return if (source.length() > MAX_BYTES) {
                Result.Err("Image is larger than 4 MB.")
            } else {
                Result.Err("Could not read that image.")
            }
        if (prepared.bytes.isEmpty()) {
            return Result.Err("Could not read that image.")
        }
        if (prepared.bytes.size > MAX_BYTES) {
            return Result.Err("Image is larger than 4 MB.")
        }
        dir.mkdirs()
        val storedName = "${UUID.randomUUID()}${prepared.ext}"
        val dest = File(dir, storedName)
        dest.writeBytes(prepared.bytes)
        return Result.Ok(
            ChatVisionAttachment(
                fileName = prepared.fileName,
                mimeType = prepared.mimeType,
                storedName = storedName,
            ),
        )
    }

    private fun ingestNormalized(
        source: File,
        fileName: String,
        kind: ImageKind,
    ): PreparedPayload? {
        if (canKeepOriginal(source, kind)) {
            val bytes = runCatching { source.readBytes() }.getOrNull() ?: return null
            if (bytes.isEmpty()) return null
            return PreparedPayload(
                bytes = bytes,
                mimeType = kind.mimeType,
                ext = kind.ext,
                fileName = replaceExt(fileName, kind.ext),
            )
        }
        val bitmap = decodeForIngest(source, INGEST_MAX_DIM) ?: return null
        val scaled = scaleToMax(bitmap, INGEST_MAX_DIM)
        val jpeg = compressJpegUnderCap(scaled)
        if (scaled != bitmap) scaled.recycle()
        bitmap.recycle()
        if (jpeg == null || jpeg.isEmpty()) return null
        return PreparedPayload(
            bytes = jpeg,
            mimeType = "image/jpeg",
            ext = ".jpg",
            fileName = replaceExt(fileName, ".jpg"),
        )
    }

    private fun canKeepOriginal(source: File, kind: ImageKind): Boolean {
        if (kind.heic) return false
        val length = source.length()
        if (length <= 0L || length > MAX_BYTES) return false
        val bounds = peekBounds(source)
        return bounds.width > 0 &&
            bounds.height > 0 &&
            bounds.width <= INGEST_MAX_DIM &&
            bounds.height <= INGEST_MAX_DIM
    }

    private fun fallbackOriginal(
        source: File,
        fileName: String,
        kind: ImageKind,
    ): PreparedPayload? {
        if (kind.heic) return null
        val length = source.length()
        if (length <= 0L || length > MAX_BYTES) return null
        val bytes = runCatching { source.readBytes() }.getOrNull() ?: return null
        if (bytes.isEmpty()) return null
        return PreparedPayload(
            bytes = bytes,
            mimeType = kind.mimeType,
            ext = kind.ext,
            fileName = replaceExt(fileName, kind.ext),
        )
    }

    private fun decodeForIngest(file: File, maxDim: Int): Bitmap? {
        return try {
            val decoded = if (Build.VERSION.SDK_INT >= 28) {
                decodeWithImageDecoder(file, maxDim)
            } else {
                decodeWithBitmapFactory(file, maxDim)
            } ?: return null
            softwareBitmap(decoded)
        } catch (_: Throwable) {
            null
        }
    }

    @RequiresApi(28)
    private fun decodeWithImageDecoder(file: File, maxDim: Int): Bitmap? {
        return try {
            val source = ImageDecoder.createSource(file)
            ImageDecoder.decodeBitmap(source) { decoder, info, _ ->
                decoder.allocator = ImageDecoder.ALLOCATOR_SOFTWARE
                val width = info.size.width
                val height = info.size.height
                val longest = maxOf(width, height)
                if (longest > maxDim) {
                    val scale = maxDim.toFloat() / longest
                    decoder.setTargetSize(
                        (width * scale).toInt().coerceAtLeast(1),
                        (height * scale).toInt().coerceAtLeast(1),
                    )
                }
            }
        } catch (_: Exception) {
            decodeWithBitmapFactory(file, maxDim)
        }
    }

    private fun decodeWithBitmapFactory(file: File, maxDim: Int): Bitmap? {
        val bounds = peekBounds(file)
        val opts = BitmapFactory.Options().apply {
            inSampleSize = sampleSizeFor(bounds.width, bounds.height, maxDim)
        }
        val bitmap = BitmapFactory.decodeFile(file.absolutePath, opts) ?: return null
        return applyExifOrientation(bitmap, file)
    }

    private data class Bounds(val width: Int, val height: Int)

    private fun peekBounds(file: File): Bounds {
        return try {
            val opts = BitmapFactory.Options().apply { inJustDecodeBounds = true }
            BitmapFactory.decodeFile(file.absolutePath, opts)
            Bounds(opts.outWidth, opts.outHeight)
        } catch (_: Throwable) {
            Bounds(-1, -1)
        }
    }

    private fun applyExifOrientation(bitmap: Bitmap, file: File): Bitmap {
        val orientation = try {
            ExifInterface(file.absolutePath).getAttributeInt(
                ExifInterface.TAG_ORIENTATION,
                ExifInterface.ORIENTATION_NORMAL,
            )
        } catch (_: Exception) {
            return bitmap
        }
        val matrix = Matrix()
        when (orientation) {
            ExifInterface.ORIENTATION_FLIP_HORIZONTAL -> matrix.preScale(-1f, 1f)
            ExifInterface.ORIENTATION_FLIP_VERTICAL -> matrix.preScale(1f, -1f)
            ExifInterface.ORIENTATION_ROTATE_90 -> matrix.postRotate(90f)
            ExifInterface.ORIENTATION_ROTATE_180 -> matrix.postRotate(180f)
            ExifInterface.ORIENTATION_ROTATE_270 -> matrix.postRotate(270f)
            ExifInterface.ORIENTATION_TRANSPOSE -> {
                matrix.postRotate(90f)
                matrix.preScale(-1f, 1f)
            }
            ExifInterface.ORIENTATION_TRANSVERSE -> {
                matrix.postRotate(270f)
                matrix.preScale(-1f, 1f)
            }
            else -> return bitmap
        }
        val rotated = Bitmap.createBitmap(bitmap, 0, 0, bitmap.width, bitmap.height, matrix, true)
        if (rotated != bitmap) bitmap.recycle()
        return rotated
    }

    private fun scaleToMax(bitmap: Bitmap, maxDim: Int): Bitmap {
        if (bitmap.width <= maxDim && bitmap.height <= maxDim) return bitmap
        val scale = minOf(maxDim.toFloat() / bitmap.width, maxDim.toFloat() / bitmap.height)
        return Bitmap.createScaledBitmap(
            bitmap,
            (bitmap.width * scale).toInt().coerceAtLeast(1),
            (bitmap.height * scale).toInt().coerceAtLeast(1),
            true,
        )
    }

    private fun compressJpegUnderCap(bitmap: Bitmap): ByteArray? {
        var last: ByteArray? = null
        for (quality in INGEST_JPEG_QUALITIES) {
            val bytes = compressJpeg(bitmap, quality) ?: continue
            last = bytes
            if (bytes.size <= MAX_BYTES) return bytes
        }
        return last
    }

    private fun compressJpeg(bitmap: Bitmap, quality: Int): ByteArray? {
        val software = softwareBitmap(bitmap) ?: return null
        try {
            val out = ByteArrayOutputStream()
            if (!software.compress(Bitmap.CompressFormat.JPEG, quality, out)) return null
            return out.toByteArray().takeIf { it.isNotEmpty() }
        } finally {
            if (software != bitmap) software.recycle()
        }
    }

    private fun softwareBitmap(bitmap: Bitmap): Bitmap? {
        if (Build.VERSION.SDK_INT >= 26 && bitmap.config == Bitmap.Config.HARDWARE) {
            return bitmap.copy(Bitmap.Config.ARGB_8888, false)
        }
        return bitmap
    }

    private fun classifyKind(mimeType: String, fileName: String): ImageKind? {
        val mime = mimeType.lowercase().trim()
        if (isHeic(mime, fileName)) {
            return ImageKind(mimeType = "image/jpeg", ext = ".jpg", heic = true)
        }
        val canonical = when {
            mime == "image/jpg" -> "image/jpeg"
            mime in MIME_TO_EXT -> mime
            mime.isEmpty() -> EXT_TO_MIME[extOf(fileName)] ?: return null
            else -> return null
        }
        val ext = MIME_TO_EXT[canonical] ?: return null
        return ImageKind(
            mimeType = if (canonical == "image/jpg") "image/jpeg" else canonical,
            ext = ext,
            heic = false,
        )
    }

    private fun copyUriToFile(
        context: Context,
        uri: Uri,
        dest: File,
        maxBytes: Long,
    ): CopyResult {
        val input = try {
            context.contentResolver.openInputStream(uri)
        } catch (_: Exception) {
            null
        } ?: return CopyResult.Failed
        return try {
            dest.parentFile?.mkdirs()
            input.use { src ->
                dest.outputStream().use { out ->
                    val buf = ByteArray(COPY_BUFFER_BYTES)
                    var total = 0L
                    while (true) {
                        val n = src.read(buf)
                        if (n < 0) break
                        total += n
                        if (total > maxBytes) return CopyResult.TooLarge
                        out.write(buf, 0, n)
                    }
                }
            }
            if (dest.isFile && dest.length() > 0L) CopyResult.Ok else CopyResult.Failed
        } catch (_: Exception) {
            CopyResult.Failed
        }
    }

    private fun isHeic(mime: String, fileName: String): Boolean {
        if (mime in HEIC_MIMES) return true
        val ext = extOf(fileName)
        return ext == ".heic" || ext == ".heif"
    }

    private fun mimeFromFileName(fileName: String): String {
        return EXT_TO_MIME[extOf(fileName)].orEmpty()
    }

    private fun extOf(fileName: String): String {
        val dot = fileName.lastIndexOf('.')
        if (dot < 0 || dot == fileName.lastIndex) return ""
        return fileName.substring(dot).lowercase()
    }

    private fun replaceExt(fileName: String, ext: String): String {
        val base = fileName.substringBeforeLast('.', fileName).ifBlank { "image" }
        return base + ext
    }

    internal fun sanitizeFileName(name: String): String {
        val base = name.trim().substringAfterLast('/').substringAfterLast('\\')
            .ifBlank { "image.png" }
        return base.replace(Regex("[^\\w.\\- ()\\[\\]]+"), "_").take(120).ifBlank { "image.png" }
    }

    private fun queryDisplayName(context: Context, uri: Uri): String? {
        return try {
            context.contentResolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)
                ?.use { cursor ->
                    val idx = cursor.getColumnIndex(OpenableColumns.DISPLAY_NAME)
                    if (cursor.moveToFirst() && idx >= 0) cursor.getString(idx) else null
                }
        } catch (_: Exception) {
            null
        } ?: uri.lastPathSegment?.substringAfterLast('/')
    }

    private fun querySize(context: Context, uri: Uri): Long? {
        return try {
            context.contentResolver.query(uri, arrayOf(OpenableColumns.SIZE), null, null, null)
                ?.use { cursor ->
                    val idx = cursor.getColumnIndex(OpenableColumns.SIZE)
                    if (cursor.moveToFirst() && idx >= 0) {
                        val size = cursor.getLong(idx)
                        if (size > 0L) size else null
                    } else {
                        null
                    }
                }
        } catch (_: Exception) {
            null
        }
    }
}
