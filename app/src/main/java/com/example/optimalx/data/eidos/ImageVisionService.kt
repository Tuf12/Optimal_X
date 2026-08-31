package com.example.optimalx.data.eidos

import android.content.Context
import com.example.optimalx.OptimalXApplication
import com.example.optimalx.data.litert.LitertLmDefaults
import com.example.optimalx.data.litert.LitertLmVisionService
import com.example.optimalx.data.preferences.ApiKeyNames
import com.example.optimalx.data.preferences.EncryptedSettingKeys
import com.example.optimalx.data.preferences.SettingsDefaults
import com.example.optimalx.data.preferences.getEncryptedPrefs
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import java.io.File

class ImageVisionService(
    private val context: Context,
    private val client: OkHttpClient = OkHttpClient(),
    private val json: Json = Json { ignoreUnknownKeys = true; explicitNulls = false },
) {

    suspend fun describeImage(file: File): Result<String> = withContext(Dispatchers.IO) {
        try {
            val prefs = getEncryptedPrefs(context)
            val provider = prefs.getString(EncryptedSettingKeys.ACTIVE_PROVIDER, null)
                ?: SettingsDefaults.ACTIVE_PROVIDER
            val prompt =
                "Describe this image in concise detail, including key objects, text visible in the image, and likely context."

            val text = if (provider == LitertLmDefaults.PROVIDER_ID) {
                describeWithLocalGemma(file, prompt)
            } else {
                val encoded = ChatVisionImageStore.encodeForProvider(file)
                    ?: error("Could not decode image")
                when (provider) {
                    "openai" -> describeWithOpenAi(
                        apiKey = prefs.getString(ApiKeyNames.OPENAI, null)
                            ?: error("Missing OpenAI API key"),
                        mimeType = encoded.mimeType,
                        base64Data = encoded.base64,
                        prompt = prompt,
                    )

                    "anthropic" -> describeWithAnthropic(
                        apiKey = prefs.getString(ApiKeyNames.ANTHROPIC, null)
                            ?: error("Missing Anthropic API key"),
                        mimeType = encoded.mimeType,
                        base64Data = encoded.base64,
                        prompt = prompt,
                    )

                    "kimi" -> describeWithKimi(
                        apiKey = prefs.getString(ApiKeyNames.KIMI, null)
                            ?: error("Missing Kimi API key"),
                        mimeType = encoded.mimeType,
                        base64Data = encoded.base64,
                        prompt = prompt,
                    )

                    else -> describeWithXai(
                        apiKey = prefs.getString(ApiKeyNames.XAI, null)
                            ?: error("Missing xAI API key"),
                        mimeType = encoded.mimeType,
                        base64Data = encoded.base64,
                        prompt = prompt,
                    )
                }
            }
            Result.success(text)
        } catch (t: Throwable) {
            Result.failure(t)
        }
    }

    private suspend fun describeWithLocalGemma(file: File, prompt: String): String {
        val app = context.applicationContext as OptimalXApplication
        return LitertLmVisionService(app).describeImage(file, prompt).getOrElse { error ->
            error(error.message ?: "Local Gemma vision failed")
        }
    }

    private fun describeWithOpenAi(
        apiKey: String,
        mimeType: String,
        base64Data: String,
        prompt: String,
    ): String {
        val payload = buildJsonObject {
            put("model", JsonPrimitive("gpt-5.6-luna"))
            put("reasoning", buildJsonObject { put("effort", JsonPrimitive("none")) })
            put("text", buildJsonObject { put("verbosity", JsonPrimitive("low")) })
            put("input", buildJsonArray {
                add(
                    buildJsonObject {
                        put("role", JsonPrimitive("user"))
                        put("content", buildJsonArray {
                            add(buildJsonObject {
                                put("type", JsonPrimitive("input_text"))
                                put("text", JsonPrimitive(prompt))
                            })
                            add(buildJsonObject {
                                put("type", JsonPrimitive("input_image"))
                                put("image_url", JsonPrimitive("data:$mimeType;base64,$base64Data"))
                            })
                        })
                    }
                )
            })
        }

        val root = postJson(
            url = "https://api.openai.com/v1/responses",
            headers = mapOf(
                "Authorization" to "Bearer $apiKey",
                "Content-Type" to "application/json",
            ),
            body = payload,
        )

        val text = root["output"]
            ?.jsonArray
            ?.flatMap { item ->
                val obj = item.jsonObject
                if (obj["type"]?.jsonPrimitive?.contentOrNull != "message") return@flatMap emptyList()
                obj["content"]?.jsonArray?.mapNotNull { c ->
                    val contentObj = c.jsonObject
                    if (contentObj["type"]?.jsonPrimitive?.contentOrNull == "output_text") {
                        contentObj["text"]?.jsonPrimitive?.contentOrNull
                    } else null
                }.orEmpty()
            }
            ?.joinToString("\n")
            ?.trim()
            .orEmpty()

        return text.ifBlank { "No description returned from OpenAI vision." }
    }

    private fun describeWithAnthropic(
        apiKey: String,
        mimeType: String,
        base64Data: String,
        prompt: String,
    ): String {
        val payload = buildJsonObject {
            put("model", JsonPrimitive("claude-haiku-4-5"))
            put("max_tokens", JsonPrimitive(512))
            put("messages", buildJsonArray {
                add(buildJsonObject {
                    put("role", JsonPrimitive("user"))
                    put("content", buildJsonArray {
                        add(buildJsonObject {
                            put("type", JsonPrimitive("text"))
                            put("text", JsonPrimitive(prompt))
                        })
                        add(buildJsonObject {
                            put("type", JsonPrimitive("image"))
                            put("source", buildJsonObject {
                                put("type", JsonPrimitive("base64"))
                                put("media_type", JsonPrimitive(mimeType))
                                put("data", JsonPrimitive(base64Data))
                            })
                        })
                    })
                })
            })
        }

        val root = postJson(
            url = "https://api.anthropic.com/v1/messages",
            headers = mapOf(
                "x-api-key" to apiKey,
                "anthropic-version" to "2023-06-01",
                "content-type" to "application/json",
            ),
            body = payload,
        )

        val text = root["content"]
            ?.jsonArray
            ?.mapNotNull { part ->
                val obj = part.jsonObject
                if (obj["type"]?.jsonPrimitive?.contentOrNull == "text") {
                    obj["text"]?.jsonPrimitive?.contentOrNull
                } else null
            }
            ?.joinToString("\n")
            ?.trim()
            .orEmpty()

        return text.ifBlank { "No description returned from Anthropic vision." }
    }

    private fun describeWithKimi(
        apiKey: String,
        mimeType: String,
        base64Data: String,
        prompt: String,
    ): String {
        val payload = buildJsonObject {
            put("model", JsonPrimitive("kimi-k2.6"))
            put("max_tokens", JsonPrimitive(512))
            put(
                "thinking",
                buildJsonObject { put("type", JsonPrimitive("disabled")) },
            )
            put("messages", buildJsonArray {
                add(buildJsonObject {
                    put("role", JsonPrimitive("user"))
                    put("content", buildJsonArray {
                        add(buildJsonObject {
                            put("type", JsonPrimitive("text"))
                            put("text", JsonPrimitive(prompt))
                        })
                        add(buildJsonObject {
                            put("type", JsonPrimitive("image_url"))
                            put("image_url", buildJsonObject {
                                put("url", JsonPrimitive("data:$mimeType;base64,$base64Data"))
                            })
                        })
                    })
                })
            })
        }

        val root = postJson(
            url = "https://api.moonshot.ai/v1/chat/completions",
            headers = mapOf(
                "Authorization" to "Bearer $apiKey",
                "Content-Type" to "application/json",
            ),
            body = payload,
        )

        val text = root["choices"]
            ?.jsonArray
            ?.firstOrNull()
            ?.jsonObject
            ?.get("message")
            ?.jsonObject
            ?.get("content")
            ?.jsonPrimitive
            ?.contentOrNull
            .orEmpty()

        return text.ifBlank { "No description returned from Kimi vision." }
    }

    private fun describeWithXai(
        apiKey: String,
        mimeType: String,
        base64Data: String,
        prompt: String,
    ): String {
        val payload = buildJsonObject {
            put("model", JsonPrimitive("grok-4.1"))
            put("messages", buildJsonArray {
                add(buildJsonObject {
                    put("role", JsonPrimitive("user"))
                    put("content", buildJsonArray {
                        add(buildJsonObject {
                            put("type", JsonPrimitive("text"))
                            put("text", JsonPrimitive(prompt))
                        })
                        add(buildJsonObject {
                            put("type", JsonPrimitive("image_url"))
                            put("image_url", buildJsonObject {
                                put("url", JsonPrimitive("data:$mimeType;base64,$base64Data"))
                            })
                        })
                    })
                })
            })
        }

        val root = postJson(
            url = "https://api.x.ai/v1/chat/completions",
            headers = mapOf(
                "Authorization" to "Bearer $apiKey",
                "Content-Type" to "application/json",
            ),
            body = payload,
        )

        val text = root["choices"]
            ?.jsonArray
            ?.firstOrNull()
            ?.jsonObject
            ?.get("message")
            ?.jsonObject
            ?.get("content")
            ?.jsonPrimitive
            ?.contentOrNull
            .orEmpty()

        return text.ifBlank { "No description returned from xAI vision." }
    }

    private fun postJson(url: String, headers: Map<String, String>, body: JsonObject): JsonObject {
        val requestBuilder = Request.Builder().url(url)
        headers.forEach { (k, v) -> requestBuilder.header(k, v) }

        val request = requestBuilder
            .post(json.encodeToString(JsonObject.serializer(), body).toRequestBody("application/json".toMediaType()))
            .build()

        client.newCall(request).execute().use { response ->
            val text = response.body?.string().orEmpty()
            if (!response.isSuccessful) error("Vision request failed (${response.code}): $text")
            return json.parseToJsonElement(text).jsonObject
        }
    }
}
