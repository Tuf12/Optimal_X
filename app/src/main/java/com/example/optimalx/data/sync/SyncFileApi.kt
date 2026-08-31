package com.example.optimalx.data.sync

import android.util.Log
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import java.io.BufferedReader
import java.io.File
import java.io.InputStream
import java.io.InputStreamReader
import java.io.OutputStream
import java.net.HttpURLConnection
import java.net.URL

class SyncFileApi {

    fun downloadAttachment(
        endpoint: SyncEndpoint,
        fileGlobalId: String,
        destFile: File,
    ): SyncCallResult<Long> = download(
        url = endpoint.filesRequestUrl(fileGlobalId, KIND_ATTACHMENT),
        token = endpoint.token,
        destFile = destFile,
    )

    fun fetchWorkshopBackupFile(
        endpoint: SyncEndpoint,
        subfolderGlobalId: String,
        relativePath: String,
        destFile: File,
    ): SyncCallResult<Long> = download(
        url = endpoint.filesRequestUrl(subfolderGlobalId, KIND_MOBILE_WORKSHOP_BACKUP, relativePath),
        token = endpoint.token,
        destFile = destFile,
        workshopRelativePath = relativePath,
    )

    fun pushAttachment(
        endpoint: SyncEndpoint,
        fileGlobalId: String,
        sourceFile: File,
        deviceId: String,
    ): SyncCallResult<SyncFilePushResponse> {
        val fileName = sourceFile.name
        val fileBytes = sourceFile.length()
        Log.i(TAG, "POST attachment globalId=$fileGlobalId name=$fileName bytes=$fileBytes")
        return try {
            val conn = open(endpoint.filesPushUrl(fileGlobalId), endpoint.token)
            conn.requestMethod = "POST"
            conn.doOutput = true
            val boundary = "----OptimalXSync${System.currentTimeMillis()}"
            conn.setRequestProperty("Content-Type", "multipart/form-data; boundary=$boundary")

            conn.outputStream.use { raw ->
                writeMultipartField(raw, boundary, "kind", KIND_ATTACHMENT)
                writeMultipartField(raw, boundary, "deviceId", deviceId)
                writeMultipartFile(raw, boundary, "file", sourceFile.name, sourceFile)
                raw.write("--$boundary--\r\n".toByteArray(Charsets.UTF_8))
            }

            val code = conn.responseCode
            val text = readBody(conn, code)
            if (code in 200..299) {
                SyncCallResult.Success(json.decodeFromString(SyncFilePushResponse.serializer(), text))
            } else {
                Log.w(TAG, "POST attachment $fileGlobalId ($fileName) HTTP $code: $text")
                SyncCallResult.Failure(
                    formatFileHttpError(code, text, attachmentFileName = fileName),
                )
            }
        } catch (t: Throwable) {
            Log.w(TAG, "POST attachment $fileGlobalId ($fileName) failed", t)
            SyncCallResult.Failure(t.message ?: t::class.java.simpleName)
        }
    }

    fun pushWorkshopFile(
        endpoint: SyncEndpoint,
        subfolderGlobalId: String,
        relativePath: String,
        sourceFile: File,
        deviceId: String,
    ): SyncCallResult<SyncFilePushResponse> {
        return try {
            val conn = open(endpoint.filesPushUrl(subfolderGlobalId), endpoint.token)
            conn.requestMethod = "POST"
            conn.doOutput = true
            val boundary = "----OptimalXSync${System.currentTimeMillis()}"
            conn.setRequestProperty("Content-Type", "multipart/form-data; boundary=$boundary")

            conn.outputStream.use { raw ->
                writeMultipartField(raw, boundary, "kind", KIND_WORKSHOP)
                writeMultipartField(raw, boundary, "path", relativePath)
                writeMultipartField(raw, boundary, "deviceId", deviceId)
                writeMultipartFile(raw, boundary, "file", sourceFile.name, sourceFile)
                raw.write("--$boundary--\r\n".toByteArray(Charsets.UTF_8))
            }

            val code = conn.responseCode
            val text = readBody(conn, code)
            if (code in 200..299) {
                SyncCallResult.Success(json.decodeFromString(SyncFilePushResponse.serializer(), text))
            } else {
                SyncCallResult.Failure(formatFileHttpError(code, text))
            }
        } catch (t: Throwable) {
            Log.w(TAG, "POST workshop file $relativePath failed", t)
            SyncCallResult.Failure(t.message ?: t::class.java.simpleName)
        }
    }

    private fun download(
        url: String,
        token: String,
        destFile: File,
        workshopRelativePath: String? = null,
    ): SyncCallResult<Long> {
        return try {
            val conn = open(url, token)
            conn.requestMethod = "GET"
            val code = conn.responseCode
            if (code !in 200..299) {
                val text = readBody(conn, code)
                return SyncCallResult.Failure(
                    formatFileHttpError(code, text, workshopRelativePath),
                )
            }
            destFile.parentFile?.mkdirs()
            conn.inputStream.use { input ->
                destFile.outputStream().use { output -> input.copyTo(output) }
            }
            SyncCallResult.Success(destFile.length())
        } catch (t: Throwable) {
            Log.w(TAG, "GET $url failed", t)
            SyncCallResult.Failure(t.message ?: t::class.java.simpleName)
        }
    }

    private fun open(url: String, token: String): HttpURLConnection {
        val conn = URL(url).openConnection() as HttpURLConnection
        conn.connectTimeout = CONNECT_TIMEOUT_MS
        conn.readTimeout = READ_TIMEOUT_MS
        conn.setRequestProperty("Authorization", "Bearer $token")
        return conn
    }

    private fun readBody(conn: HttpURLConnection, code: Int): String {
        val stream: InputStream? = if (code in 200..299) conn.inputStream else conn.errorStream
        return stream?.let { BufferedReader(InputStreamReader(it, Charsets.UTF_8)).use { r -> r.readText() } }
            .orEmpty()
    }

    private fun writeMultipartField(
        output: OutputStream,
        boundary: String,
        name: String,
        value: String,
    ) {
        output.write("--$boundary\r\n".toByteArray(Charsets.UTF_8))
        output.write("Content-Disposition: form-data; name=\"$name\"\r\n\r\n".toByteArray(Charsets.UTF_8))
        output.write(value.toByteArray(Charsets.UTF_8))
        output.write("\r\n".toByteArray(Charsets.UTF_8))
    }

    private fun writeMultipartFile(
        output: OutputStream,
        boundary: String,
        fieldName: String,
        fileName: String,
        file: File,
    ) {
        output.write("--$boundary\r\n".toByteArray(Charsets.UTF_8))
        output.write(
            "Content-Disposition: form-data; name=\"$fieldName\"; filename=\"$fileName\"\r\n"
                .toByteArray(Charsets.UTF_8),
        )
        output.write("Content-Type: application/octet-stream\r\n\r\n".toByteArray(Charsets.UTF_8))
        file.inputStream().use { input -> input.copyTo(output) }
        output.write("\r\n".toByteArray(Charsets.UTF_8))
    }

    private fun formatFileHttpError(
        code: Int,
        body: String,
        workshopRelativePath: String? = null,
        attachmentFileName: String? = null,
    ): String {
        val errorCode = parseErrorCode(body)
        val attachmentLabel = attachmentFileName?.let { "\"$it\"" } ?: "attachment"
        return when (code) {
            401 -> "HTTP 401 — wrong bearer token. Copy the token from desktop Settings → Sync with Mobile."
            400 -> when (errorCode) {
                "missing_file" ->
                    "Desktop did not receive bytes for $attachmentLabel — file may be empty on the phone, " +
                        "or the upload was interrupted. Re-import the file and try Push again."
                "missing_kind" -> "Desktop rejected upload — missing kind=attachment."
                else -> if (body.isBlank()) "HTTP 400" else "HTTP 400: $body"
            }
            404 -> when (errorCode) {
                "file_not_found" -> if (workshopRelativePath != null) {
                    "Workshop file \"$workshopRelativePath\" is not on the desktop PC " +
                        "(missing under backups/mobile-workshop/)."
                } else {
                    "Attachment metadata exists but the file bytes are missing on desktop."
                }
                "not_found" -> when {
                    workshopRelativePath != null ->
                        "Workshop project not found on desktop — run Tier 1 Pull first and confirm " +
                            "target_platform=mobile under backups/mobile-workshop/."
                    attachmentFileName != null ->
                        "Attachment $attachmentLabel is not registered on the desktop PC. " +
                            "Sync will re-send metadata automatically — try again, or run Tier 1 Push first."
                    else ->
                        "File not found on desktop — run Tier 1 Push or Pull first."
                }
                else -> if (workshopRelativePath != null) {
                    "HTTP 404 — workshop file \"$workshopRelativePath\" not found on desktop."
                } else {
                    "Attachment not found on desktop — run Tier 1 Push first so file metadata exists on the PC."
                }
            }
            413 -> "File is too large for desktop sync (max 100 MB per file)."
            else -> if (body.isBlank()) "HTTP $code" else "HTTP $code: $body"
        }
    }

    private fun parseErrorCode(body: String): String? {
        if (body.isBlank()) return null
        return runCatching {
            json.parseToJsonElement(body).jsonObject["error"]?.jsonPrimitive?.content
        }.getOrNull()
    }

    companion object {
        private const val TAG = "SyncFileApi"
        private const val CONNECT_TIMEOUT_MS = 8_000
        private const val READ_TIMEOUT_MS = 300_000
        const val KIND_ATTACHMENT = "attachment"
        const val KIND_WORKSHOP = "workshop"
        const val KIND_MOBILE_WORKSHOP_BACKUP = "mobile_workshop_backup"

        private val json = Json {
            ignoreUnknownKeys = true
            isLenient = true
        }
    }
}
