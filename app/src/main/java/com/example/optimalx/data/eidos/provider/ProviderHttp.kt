package com.example.optimalx.data.eidos.provider

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import kotlin.coroutines.coroutineContext
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException
import kotlinx.serialization.json.JsonObject
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody

private val jsonMediaType = "application/json; charset=utf-8".toMediaType()

class ProviderHttpException(
    val statusCode: Int,
    val responseBody: String,
) : IllegalStateException("HTTP $statusCode: $responseBody")

suspend fun getJson(
    client: OkHttpClient,
    url: String,
    bearerToken: String?,
    extraHeaders: Map<String, String> = emptyMap(),
): String = withContext(Dispatchers.IO) {
    val builder = Request.Builder()
        .url(url)
        .header("Content-Type", "application/json")
        .get()

    if (bearerToken != null) {
        builder.header("Authorization", "Bearer $bearerToken")
    }

    extraHeaders.forEach { (k, v) -> builder.header(k, v) }

    suspendCancellableCoroutine { cont ->
        val call = client.newCall(builder.build())
        cont.invokeOnCancellation { call.cancel() }
        try {
            call.execute().use { response ->
                val responseBody = response.body?.string().orEmpty()
                if (!response.isSuccessful) {
                    if (cont.isActive) {
                        cont.resumeWithException(
                            ProviderHttpException(
                                statusCode = response.code,
                                responseBody = responseBody,
                            ),
                        )
                    }
                } else {
                    if (cont.isActive) {
                        cont.resume(responseBody)
                    }
                }
            }
        } catch (e: Throwable) {
            if (cont.isActive) {
                cont.resumeWithException(e)
            }
        }
    }
}

suspend fun postJson(
    client: OkHttpClient,
    url: String,
    bearerToken: String?,
    body: String,
    extraHeaders: Map<String, String> = emptyMap(),
): String = withContext(Dispatchers.IO) {
    val builder = Request.Builder()
        .url(url)
        .header("Content-Type", "application/json")

    if (bearerToken != null) {
        builder.header("Authorization", "Bearer $bearerToken")
    }

    extraHeaders.forEach { (k, v) -> builder.header(k, v) }

    val request = builder
        .post(body.toRequestBody(jsonMediaType))
        .build()

    suspendCancellableCoroutine { cont ->
        val call = client.newCall(request)
        cont.invokeOnCancellation { call.cancel() }
        try {
            call.execute().use { response ->
                val responseBody = response.body?.string().orEmpty()
                if (!response.isSuccessful) {
                    if (cont.isActive) {
                        cont.resumeWithException(
                            ProviderHttpException(
                                statusCode = response.code,
                                responseBody = responseBody,
                            ),
                        )
                    }
                } else {
                    if (cont.isActive) {
                        cont.resume(responseBody)
                    }
                }
            }
        } catch (e: Throwable) {
            if (cont.isActive) {
                cont.resumeWithException(e)
            }
        }
    }
}

/**
 * POST with `Accept: text/event-stream` and invoke [onChunk] for each `data: {...}` line.
 * Kimi K2.6 thinking streams use OpenAI-compatible SSE chunks.
 */
suspend fun postJsonStream(
    client: OkHttpClient,
    url: String,
    bearerToken: String?,
    body: String,
    onChunk: (JsonObject) -> Unit,
) = withContext(Dispatchers.IO) {
    val builder = Request.Builder()
        .url(url)
        .header("Content-Type", "application/json")
        .header("Accept", "text/event-stream")

    if (bearerToken != null) {
        builder.header("Authorization", "Bearer $bearerToken")
    }

    val request = builder
        .post(body.toRequestBody(jsonMediaType))
        .build()

    suspendCancellableCoroutine { cont ->
        val call = client.newCall(request)
        cont.invokeOnCancellation { call.cancel() }
        try {
            call.execute().use { response ->
                val responseBody = response.body
                if (!response.isSuccessful) {
                    val errorText = responseBody?.string().orEmpty()
                    if (cont.isActive) {
                        cont.resumeWithException(
                            ProviderHttpException(
                                statusCode = response.code,
                                responseBody = errorText,
                            ),
                        )
                    }
                    return@use
                }
                val source = responseBody?.source()
                if (source == null) {
                    if (cont.isActive) cont.resume(Unit)
                    return@use
                }
                val sseJson = kotlinx.serialization.json.Json { ignoreUnknownKeys = true }
                while (!source.exhausted()) {
                    coroutineContext.ensureActive()
                    val line = source.readUtf8Line() ?: break
                    parseKimiSseDataLine(line, sseJson, onChunk)
                }
                if (cont.isActive) {
                    cont.resume(Unit)
                }
            }
        } catch (e: Throwable) {
            if (cont.isActive) {
                cont.resumeWithException(e)
            }
        }
    }
}
