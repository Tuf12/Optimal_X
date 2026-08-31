package com.example.optimalx.data.sync

import android.util.Log
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import java.io.BufferedReader
import java.io.InputStream
import java.io.InputStreamReader
import java.io.OutputStreamWriter
import java.net.HttpURLConnection
import java.net.URL

sealed interface SyncCallResult<out T> {
    data class Success<T>(val value: T) : SyncCallResult<T>
    data class Failure(val message: String) : SyncCallResult<Nothing>
}

class SyncApi {

    fun status(endpoint: SyncEndpoint): SyncCallResult<SyncStatusResponse> =
        getJson(endpoint.statusUrl(), endpoint.token, SyncStatusResponse.serializer())

    fun push(endpoint: SyncEndpoint, body: SyncPushPullRequest): SyncCallResult<SyncApplyResponse> =
        postJson(endpoint.pushUrl(), endpoint.token, body, SyncApplyResponse.serializer())

    fun pull(endpoint: SyncEndpoint, body: SyncPushPullRequest): SyncCallResult<SyncApplyResponse> =
        postJson(endpoint.pullUrl(), endpoint.token, body, SyncApplyResponse.serializer())

    fun resolve(endpoint: SyncEndpoint, body: SyncResolveRequest): SyncCallResult<SyncApplyResponse> =
        postJson(endpoint.resolveUrl(), endpoint.token, body, SyncApplyResponse.serializer())

    private inline fun <reified T> getJson(
        url: String,
        token: String,
        deserializer: kotlinx.serialization.KSerializer<T>,
    ): SyncCallResult<T> {
        return try {
            val conn = open(url, token)
            conn.requestMethod = "GET"
            val code = conn.responseCode
            val text = readBody(conn, code)
            if (code in 200..299) {
                SyncCallResult.Success(json.decodeFromString(deserializer, text))
            } else {
                SyncCallResult.Failure(formatHttpError(code, text, token))
            }
        } catch (t: Throwable) {
            Log.w(TAG, "GET $url failed", t)
            SyncCallResult.Failure(t.message ?: t::class.java.simpleName)
        }
    }

    private inline fun <reified Req, reified Res> postJson(
        url: String,
        token: String,
        body: Req,
        deserializer: kotlinx.serialization.KSerializer<Res>,
    ): SyncCallResult<Res> {
        return try {
            val conn = open(url, token)
            conn.requestMethod = "POST"
            conn.doOutput = true
            conn.setRequestProperty("Content-Type", "application/json; charset=utf-8")
            val payload = json.encodeToString(body)
            OutputStreamWriter(conn.outputStream, Charsets.UTF_8).use { it.write(payload) }
            val code = conn.responseCode
            val text = readBody(conn, code)
            if (code in 200..299) {
                val parsed = json.decodeFromString(deserializer, text)
                if (url.contains("/pull") && parsed is SyncApplyResponse) {
                    val rows = parsed.tables?.totalRows() ?: 0
                    Log.d(TAG, "POST $url → $rows pull rows, body ${text.length} chars")
                    if (rows == 0 && text.length > 120) {
                        Log.d(TAG, "Pull body preview: ${text.take(400)}")
                    }
                }
                SyncCallResult.Success(parsed)
            } else {
                SyncCallResult.Failure(formatHttpError(code, text, token))
            }
        } catch (t: Throwable) {
            Log.w(TAG, "POST $url failed", t)
            SyncCallResult.Failure(t.message ?: t::class.java.simpleName)
        }
    }

    private fun open(url: String, token: String): HttpURLConnection {
        val conn = URL(url).openConnection() as HttpURLConnection
        conn.connectTimeout = CONNECT_TIMEOUT_MS
        conn.readTimeout = READ_TIMEOUT_MS
        conn.setRequestProperty("Authorization", "Bearer $token")
        conn.setRequestProperty("Accept", "application/json")
        return conn
    }

    private fun readBody(conn: HttpURLConnection, code: Int): String {
        val stream: InputStream? = if (code in 200..299) conn.inputStream else conn.errorStream
        return stream?.let { BufferedReader(InputStreamReader(it, Charsets.UTF_8)).use { r -> r.readText() } }
            .orEmpty()
    }

    private fun formatHttpError(code: Int, body: String, token: String): String {
        if (code == 401) {
            return "HTTP 401 — wrong bearer token. Copy the token from desktop Settings → Sync with Mobile."
        }
        if (code == 409 && body.isNotBlank()) {
            val detail = runCatching {
                val obj = json.parseToJsonElement(body).jsonObject
                val error = obj["error"]?.toString()?.trim('"').orEmpty()
                val detailMsg = obj["detail"]?.toString()?.trim('"').orEmpty()
                when {
                    error.isNotBlank() && detailMsg.isNotBlank() -> "$error — $detailMsg"
                    error.isNotBlank() -> error
                    else -> body
                }
            }.getOrDefault(body)
            return "HTTP 409: $detail"
        }
        return if (body.isBlank()) "HTTP $code" else "HTTP $code: $body"
    }

    companion object {
        private const val TAG = "SyncApi"
        private const val CONNECT_TIMEOUT_MS = 8_000
        private const val READ_TIMEOUT_MS = 120_000
        private val json = Json {
            ignoreUnknownKeys = true
            isLenient = true
            encodeDefaults = true
        }
    }
}
