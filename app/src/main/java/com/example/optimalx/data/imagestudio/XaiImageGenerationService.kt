package com.example.optimalx.data.imagestudio

import android.util.Base64
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import okhttp3.MediaType.Companion.toMediaType
import java.io.IOException
import java.net.SocketTimeoutException
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

class XaiImageGenerationService(
    private val apiKeyProvider: ImageGenerationApiKeyProvider,
    private val client: OkHttpClient = defaultClient,
    private val json: Json = Json { ignoreUnknownKeys = true; explicitNulls = false },
) : ImageGenerationService {

    override fun isConfigured(): Boolean = !apiKeyProvider.xaiApiKey().isNullOrBlank()

    override fun estimateCost(request: ImageGenerationRequest): CostEstimate? =
        ImageStudioModelCatalog.estimateCost(request.tier)

    override suspend fun generate(request: ImageGenerationRequest): ImageGenerationResult =
        withContext(Dispatchers.IO) {
            val prompt = request.prompt.trim()
            if (prompt.isEmpty()) {
                throw ImageGenerationException(
                    ImageGenerationException.EMPTY_PROMPT,
                    "Prompt is required",
                )
            }

            val apiKey = apiKeyProvider.xaiApiKey()
                ?: throw ImageGenerationException(
                    ImageGenerationException.NO_API_KEY,
                    "Add your xAI API key in Settings → AI",
                )

            val spec = ImageStudioModelCatalog.tierSpec(request.tier)
            val dimensions = ImageStudioAspectRatio.resolve(request.aspectRatio)
            val body = buildRequestBody(
                modelId = spec.modelId,
                prompt = prompt,
                aspectRatio = request.aspectRatio.wireValue,
                resolution = spec.resolution,
            )

            val responseText = try {
                postJson(apiKey, body)
            } catch (e: SocketTimeoutException) {
                throw ImageGenerationException(
                    ImageGenerationException.API_TIMEOUT,
                    "Image generation timed out",
                    e,
                )
            } catch (e: IOException) {
                throw ImageGenerationException(
                    ImageGenerationException.NETWORK_OFFLINE,
                    "Network error while generating image",
                    e,
                )
            }

            parseGenerationResponse(
                responseText = responseText,
                modelId = spec.modelId,
                width = dimensions.width,
                height = dimensions.height,
                estimatedCostUsd = spec.estimatedUsd,
                download = { url -> downloadBytes(url) },
            )
        }

    private fun buildRequestBody(
        modelId: String,
        prompt: String,
        aspectRatio: String,
        resolution: String,
    ): JsonObject = buildJsonObject {
        put("model", modelId)
        put("prompt", prompt)
        put("n", 1)
        put("aspect_ratio", aspectRatio)
        put("resolution", resolution)
        put("response_format", "b64_json")
    }

    private fun postJson(apiKey: String, body: JsonObject): String {
        val request = Request.Builder()
            .url(IMAGES_URL)
            .header("Authorization", "Bearer $apiKey")
            .header("Content-Type", "application/json")
            .post(json.encodeToString(JsonObject.serializer(), body).toRequestBody(JSON_MEDIA_TYPE))
            .build()

        client.newCall(request).execute().use { response ->
            val text = response.body?.string().orEmpty()
            if (response.isSuccessful) return text
            throw mapHttpError(response.code, text)
        }
    }

    private fun downloadBytes(url: String): ByteArray {
        val request = Request.Builder().url(url).get().build()
        client.newCall(request).execute().use { response ->
            if (!response.isSuccessful) {
                throw ImageGenerationException(
                    ImageGenerationException.API_ERROR,
                    "Failed to download generated image (${response.code})",
                )
            }
            return response.body?.bytes()
                ?: throw ImageGenerationException(
                    ImageGenerationException.API_ERROR,
                    "Empty image download response",
                )
        }
    }

  companion object {
        const val IMAGES_URL: String = "https://api.x.ai/v1/images/generations"

        private val JSON_MEDIA_TYPE = "application/json".toMediaType()
        private val responseJson = Json { ignoreUnknownKeys = true; explicitNulls = false }

        val defaultClient: OkHttpClient = OkHttpClient.Builder()
            .connectTimeout(30, TimeUnit.SECONDS)
            .readTimeout(120, TimeUnit.SECONDS)
            .writeTimeout(120, TimeUnit.SECONDS)
            .callTimeout(120, TimeUnit.SECONDS)
            .build()

        fun mapHttpError(code: Int, body: String): ImageGenerationException {
            val detail = extractErrorMessage(body)
            return when (code) {
                401, 403 -> ImageGenerationException(
                    ImageGenerationException.API_AUTH_FAILED,
                    detail ?: "xAI API key rejected",
                )
                429 -> ImageGenerationException(
                    ImageGenerationException.API_RATE_LIMIT,
                    detail ?: "Too many requests — wait and retry",
                )
                in 400..499 -> {
                    val policy = detail?.contains("policy", ignoreCase = true) == true ||
                        detail?.contains("content", ignoreCase = true) == true
                    if (policy) {
                        ImageGenerationException(
                            ImageGenerationException.API_CONTENT_POLICY,
                            detail ?: "Provider rejected prompt",
                        )
                    } else {
                        ImageGenerationException(
                            ImageGenerationException.API_ERROR,
                            detail ?: "Image generation failed ($code)",
                        )
                    }
                }
                else -> ImageGenerationException(
                    ImageGenerationException.API_ERROR,
                    detail ?: "Image generation failed ($code)",
                )
            }
        }

        fun extractErrorMessage(body: String): String? {
            if (body.isBlank()) return null
            return runCatching {
                val root = responseJson.parseToJsonElement(body).jsonObject
                root["error"]?.jsonObject?.get("message")?.jsonPrimitive?.contentOrNull
                    ?: root["message"]?.jsonPrimitive?.contentOrNull
            }.getOrNull()?.trim()?.takeIf { it.isNotEmpty() }
        }

        fun parseGenerationResponse(
            responseText: String,
            modelId: String,
            width: Int,
            height: Int,
            estimatedCostUsd: Double,
            download: (String) -> ByteArray,
            jsonParser: Json = responseJson,
        ): ImageGenerationResult {
            val root = jsonParser.parseToJsonElement(responseText).jsonObject
            val item = root["data"]?.jsonArray?.firstOrNull()?.jsonObject
                ?: throw ImageGenerationException(
                    ImageGenerationException.API_ERROR,
                    "No image data in provider response",
                )

            val b64 = item["b64_json"]?.jsonPrimitive?.contentOrNull
            val bytes = if (!b64.isNullOrBlank()) {
                Base64.decode(b64, Base64.DEFAULT)
            } else {
                val url = item["url"]?.jsonPrimitive?.contentOrNull
                    ?: throw ImageGenerationException(
                        ImageGenerationException.API_ERROR,
                        "Provider returned no image bytes or URL",
                    )
                download(url)
            }

            if (bytes.isEmpty()) {
                throw ImageGenerationException(
                    ImageGenerationException.API_ERROR,
                    "Provider returned empty image",
                )
            }

            val mimeType = guessMimeType(bytes)
            val actualCost = root["usage"]?.jsonObject
                ?.get("cost_in_usd_ticks")
                ?.jsonPrimitive
                ?.contentOrNull
                ?.toLongOrNull()
                ?.let { ticks -> ticks / 10_000_000_000.0 }
                ?: estimatedCostUsd

            return ImageGenerationResult(
                imageBytes = bytes,
                mimeType = mimeType,
                modelId = modelId,
                seed = null,
                width = width,
                height = height,
                actualCostUsd = actualCost,
                providerRequestId = root["id"]?.jsonPrimitive?.contentOrNull,
            )
        }

        private fun guessMimeType(bytes: ByteArray): String {
            if (bytes.size >= 8 &&
                bytes[0] == 0x89.toByte() &&
                bytes[1] == 0x50.toByte() &&
                bytes[2] == 0x4E.toByte() &&
                bytes[3] == 0x47.toByte()
            ) {
                return "image/png"
            }
            if (bytes.size >= 3 &&
                bytes[0] == 0xFF.toByte() &&
                bytes[1] == 0xD8.toByte() &&
                bytes[2] == 0xFF.toByte()
            ) {
                return "image/jpeg"
            }
            if (bytes.size >= 12 &&
                bytes[0] == 0x52.toByte() &&
                bytes[1] == 0x49.toByte() &&
                bytes[2] == 0x46.toByte() &&
                bytes[3] == 0x46.toByte() &&
                bytes[8] == 0x57.toByte() &&
                bytes[9] == 0x45.toByte() &&
                bytes[10] == 0x42.toByte() &&
                bytes[11] == 0x50.toByte()
            ) {
                return "image/webp"
            }
            return "image/png"
        }
    }
}
