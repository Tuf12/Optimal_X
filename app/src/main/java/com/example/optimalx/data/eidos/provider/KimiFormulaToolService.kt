package com.example.optimalx.data.eidos.provider

import kotlinx.coroutines.delay
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import java.io.IOException
import java.io.InterruptedIOException
import java.net.ConnectException
import java.net.SocketTimeoutException
import java.net.UnknownHostException
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import okhttp3.OkHttpClient

private const val MOONSHOT_API_BASE = "https://api.moonshot.ai/v1"

/** Moonshot Formula URI for real-time web search. */
const val KIMI_FORMULA_WEB_SEARCH_URI = "moonshot/web-search:latest"

/** Moonshot Formula URI for URL → Markdown page extraction. */
const val KIMI_FORMULA_FETCH_URI = "moonshot/fetch:latest"

/** Moonshot Formula URI for unit/currency conversion. */
const val KIMI_FORMULA_CONVERT_URI = "moonshot/convert:latest"

/** Moonshot Formula URI for date/time arithmetic and formatting. */
const val KIMI_FORMULA_DATE_URI = "moonshot/date:latest"

/** Moonshot Formula URI for Excel/CSV structural analysis. */
const val KIMI_FORMULA_EXCEL_URI = "moonshot/excel:latest"


/**
 * Loads and executes Kimi K2.6 official Formula tools via the Moonshot Formula API.
 *
 * Shipped formulas: [KIMI_FORMULA_WEB_SEARCH_URI] (`web_search`), [KIMI_FORMULA_FETCH_URI] (`fetch`),
 * [KIMI_FORMULA_CONVERT_URI] (`convert`), [KIMI_FORMULA_DATE_URI] (`date`), [KIMI_FORMULA_EXCEL_URI] (`excel`),
 * Thinking stays enabled; unlike builtin `$web_search`, Formula tools are client-executed.
 *
 * @see <a href="https://platform.kimi.ai/docs/guide/use-official-tools">Kimi official tools</a>
 */
class KimiFormulaToolService(
    private val apiKey: String,
    private val client: OkHttpClient,
    private val json: Json,
) {
    private val loadMutex = Mutex()

    /** True when at least one Formula tool schema was loaded from Moonshot. */
    fun isLoaded(): Boolean = cachedToolSchemas.isNotEmpty()

    suspend fun ensureLoaded() {
        ensureLoadedWithRetry()
    }

    /**
     * Loads Formula tool schemas from Moonshot with retries.
     * Widget/cold-start sends often hit DNS before the network stack is warm — retry instead of
     * proceeding with an empty tool list while the system prompt still advertises web_search/fetch.
     */
    suspend fun ensureLoadedWithRetry(maxAttempts: Int = 4): Boolean {
        if (cachedToolSchemas.isNotEmpty()) return true
        return loadMutex.withLock {
            if (cachedToolSchemas.isNotEmpty()) return true
            repeat(maxAttempts) { attempt ->
                try {
                    loadSchemasFromApi()
                    if (cachedToolSchemas.isNotEmpty()) return@withLock true
                } catch (e: Throwable) {
                    if (!isTransientLoadFailure(e) || attempt == maxAttempts - 1) return@withLock false
                    delay((1_000L * (attempt + 1)).coerceAtMost(4_000L))
                }
            }
            false
        }
    }

    private suspend fun loadSchemasFromApi() {
        val schemas = mutableListOf<JsonObject>()
        val nameToUri = mutableMapOf<String, String>()
        for (uri in KIMI_FORMULA_URIS) {
            val responseText = getJson(
                client = client,
                url = "$MOONSHOT_API_BASE/formulas/$uri/tools",
                bearerToken = apiKey,
            )
            val root = json.parseToJsonElement(responseText).jsonObject
            root["tools"]?.jsonArray.orEmpty().forEach { element ->
                val tool = element.jsonObject
                val func = tool["function"]?.jsonObject ?: return@forEach
                val name = func["name"]?.jsonPrimitive?.contentOrNull?.takeIf { it.isNotBlank() }
                    ?: return@forEach
                if (name in nameToUri && nameToUri[name] != uri) {
                    error("Kimi formula tool name conflict: $name")
                }
                nameToUri[name] = uri
                schemas += tool
            }
        }
        cachedToolSchemas = schemas
        cachedToolNameToUri = nameToUri
    }

    private fun isTransientLoadFailure(error: Throwable): Boolean {
        var current: Throwable? = error
        while (current != null) {
            when (current) {
                is UnknownHostException,
                is ConnectException,
                is SocketTimeoutException,
                is InterruptedIOException,
                is IOException,
                -> return true
            }
            if (current is ProviderHttpException && current.statusCode in 500..599) {
                return true
            }
            current = current.cause
        }
        return false
    }

    fun formulaToolSchemas(): List<JsonObject> = cachedToolSchemas

    fun formulaUriForTool(name: String): String? = cachedToolNameToUri[name]

    fun isFormulaTool(name: String): Boolean = name in cachedToolNameToUri

    suspend fun executeFormulaTool(name: String, argumentsJson: String): String {
        ensureLoaded()
        val uri = cachedToolNameToUri[name]
            ?: return "Error: unknown Kimi formula tool '$name'."
        val payload = buildJsonObject {
            put("name", JsonPrimitive(name))
            put("arguments", JsonPrimitive(argumentsJson))
        }
        val responseText = postJson(
            client = client,
            url = "$MOONSHOT_API_BASE/formulas/$uri/fibers",
            bearerToken = apiKey,
            body = json.encodeToString(JsonObject.serializer(), payload),
        )
        val rawOutput = extractFiberOutput(responseText)
        return enrichFormulaToolOutput(formulaUri = uri, argumentsJson = argumentsJson, rawOutput = rawOutput)
    }

    private fun extractFiberOutput(responseText: String): String {
        val fiber = json.parseToJsonElement(responseText).jsonObject
        val status = fiber["status"]?.jsonPrimitive?.contentOrNull
        if (status == "succeeded") {
            val context = fiber["context"]?.jsonObject
            val output = context?.get("output")?.let(::jsonElementAsString)?.trim().orEmpty()
            if (output.isNotEmpty()) return output
            val encrypted = context?.get("encrypted_output")?.let(::jsonElementAsString)?.trim().orEmpty()
            if (encrypted.isNotEmpty()) return encrypted
            return "Error: Kimi formula succeeded but returned no output."
        }
        fiber["error"]?.let(::jsonElementAsString)?.takeIf { it.isNotBlank() }?.let {
            return "Error: $it"
        }
        fiber["context"]?.jsonObject?.get("error")?.let(::jsonElementAsString)?.takeIf { it.isNotBlank() }?.let {
            return "Error: $it"
        }
        fiber["context"]?.jsonObject?.get("output")?.let(::jsonElementAsString)?.takeIf { it.isNotBlank() }?.let {
            return "Error: $it"
        }
        return "Error: Kimi formula failed (status=${status ?: "unknown"})."
    }

    private fun jsonElementAsString(element: kotlinx.serialization.json.JsonElement): String {
        return when (element) {
            is kotlinx.serialization.json.JsonNull -> ""
            is JsonPrimitive -> if (element.isString) element.contentOrNull.orEmpty() else element.content
            else -> element.toString()
        }
    }

    companion object {
        val KIMI_FORMULA_URIS: List<String> = listOf(
            KIMI_FORMULA_WEB_SEARCH_URI,
            KIMI_FORMULA_FETCH_URI,
            KIMI_FORMULA_CONVERT_URI,
            KIMI_FORMULA_DATE_URI,
            KIMI_FORMULA_EXCEL_URI,
        )

        @Volatile
        private var cachedToolSchemas: List<JsonObject> = emptyList()

        @Volatile
        private var cachedToolNameToUri: Map<String, String> = emptyMap()
    }
}

/**
 * Prepends a source URL header for Formula fetch results so the model can cite links in replies.
 */
internal fun enrichFormulaToolOutput(
    formulaUri: String,
    argumentsJson: String,
    rawOutput: String,
): String {
    if (formulaUri != KIMI_FORMULA_FETCH_URI) return rawOutput
    if (rawOutput.startsWith("Error:")) return rawOutput
    val url = extractUrlFromToolArguments(argumentsJson) ?: return rawOutput
    if (rawOutput.startsWith("Source:")) return rawOutput
    return buildString {
        append("Source: ")
        appendLine(url)
        appendLine()
        append(rawOutput)
    }
}

internal fun extractUrlFromToolArguments(argumentsJson: String): String? {
    return runCatching {
        val obj = Json { ignoreUnknownKeys = true }.parseToJsonElement(argumentsJson).jsonObject
        for (key in listOf("url", "link", "href", "uri")) {
            obj[key]?.jsonPrimitive?.contentOrNull
                ?.trim()
                ?.takeIf { it.isNotBlank() }
                ?.let { return it }
        }
        null
    }.getOrNull()
}
