package com.example.optimalx.voice

import android.content.Context
import com.example.optimalx.data.preferences.ApiKeyNames
import com.example.optimalx.data.preferences.getEncryptedPrefs
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.MultipartBody
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.asRequestBody
import java.io.File
import java.util.concurrent.TimeUnit

class WhisperTranscriptionClient(
    context: Context,
    private val httpClient: OkHttpClient = defaultClient,
    private val json: Json = Json { ignoreUnknownKeys = true },
) {
    private val appContext = context.applicationContext

    suspend fun transcribe(wavFile: File): Result<String> = withContext(Dispatchers.IO) {
        val apiKey = getEncryptedPrefs(appContext).getString(ApiKeyNames.OPENAI, null)?.trim()
        if (apiKey.isNullOrBlank()) {
            return@withContext Result.failure(IllegalStateException("OpenAI API key is not configured"))
        }
        val body = MultipartBody.Builder()
            .setType(MultipartBody.FORM)
            .addFormDataPart("model", "whisper-1")
            .addFormDataPart(
                "file",
                wavFile.name,
                wavFile.asRequestBody("audio/wav".toMediaType()),
            )
            .build()
        val request = Request.Builder()
            .url("https://api.openai.com/v1/audio/transcriptions")
            .header("Authorization", "Bearer $apiKey")
            .post(body)
            .build()
        runCatching {
            httpClient.newCall(request).execute().use { response ->
                val responseBody = response.body?.string().orEmpty()
                if (!response.isSuccessful) {
                    error("Whisper HTTP ${response.code}: $responseBody")
                }
                json.parseToJsonElement(responseBody)
                    .jsonObject["text"]
                    ?.jsonPrimitive
                    ?.content
                    ?.trim()
                    .orEmpty()
            }
        }
    }

    companion object {
        private val defaultClient = OkHttpClient.Builder()
            .connectTimeout(30, TimeUnit.SECONDS)
            .readTimeout(120, TimeUnit.SECONDS)
            .writeTimeout(120, TimeUnit.SECONDS)
            .build()
    }
}
