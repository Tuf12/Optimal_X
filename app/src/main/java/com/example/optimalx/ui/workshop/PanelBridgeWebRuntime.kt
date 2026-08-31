package com.example.optimalx.ui.workshop

import android.webkit.JavascriptInterface
import android.webkit.WebView
import com.example.optimalx.data.eidos.PanelBridgeRegistry
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonPrimitive

private const val BRIDGE_NAME = "OptimalXPanelBridge"

private val bridgeJson = Json { ignoreUnknownKeys = true; explicitNulls = false }

private val bridgeScope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

internal class PanelBridgeJsInterface(
    private val registry: PanelBridgeRegistry,
    private val instanceId: String,
    private val workshopSubfolderId: Long,
    private val contextType: String,
    private val isVisibleProvider: () -> Boolean,
    private val isFocusedProvider: () -> Boolean,
    private val panelStateStore: PanelStateStore? = null,
    private val onEventEmitted: ((eventName: String, payloadJson: String, workshopSubfolderId: Long, contextType: String) -> Unit)? = null,
) {

    data class PanelStateStore(
        val scopeKey: String,
        val loadStateJson: suspend () -> String,
        val saveStateJson: suspend (String) -> Unit,
    )

    @JavascriptInterface
    fun registerTools(functionsJson: String?) {
        val parsed = parseFunctions(functionsJson)
        bridgeScope.launch {
            registry.updateFunctions(instanceId = instanceId, functions = parsed)
            registry.updateVisibility(
                instanceId = instanceId,
                isVisible = isVisibleProvider(),
                isFocused = isFocusedProvider(),
            )
        }
    }

    @JavascriptInterface
    fun resolve(requestId: String?, resultJson: String?) {
        if (requestId.isNullOrBlank()) return
        bridgeScope.launch {
            registry.resolve(requestId, resultJson ?: "null")
        }
    }

    @JavascriptInterface
    fun reject(requestId: String?, errorMessage: String?) {
        if (requestId.isNullOrBlank()) return
        bridgeScope.launch {
            registry.reject(requestId, errorMessage ?: "unknown panel error")
        }
    }

    @JavascriptInterface
    fun emitEvent(eventName: String?, payloadJson: String?) {
        val name = eventName?.trim().orEmpty().ifBlank { "event" }
        val payload = payloadJson ?: "null"
        bridgeScope.launch {
            registry.emitEvent(
                instanceId = instanceId,
                eventName = name,
                payloadJson = payload,
            )
            registry.updateVisibility(
                instanceId = instanceId,
                isVisible = isVisibleProvider(),
                isFocused = isFocusedProvider(),
            )
        }
        onEventEmitted?.invoke(name, payload, workshopSubfolderId, contextType)
    }

    @JavascriptInterface
    fun loadPersistedState(): String {
        val store = panelStateStore ?: return "{}"
        return kotlinx.coroutines.runBlocking(kotlinx.coroutines.Dispatchers.IO) {
            store.loadStateJson()
        }
    }

    @JavascriptInterface
    fun savePersistedState(stateJson: String?) {
        val store = panelStateStore ?: return
        val payload = stateJson?.trim().orEmpty().ifBlank { "{}" }
        bridgeScope.launch(kotlinx.coroutines.Dispatchers.IO) {
            store.saveStateJson(payload)
        }
    }

    private fun parseFunctions(functionsJson: String?): List<PanelBridgeRegistry.BridgeFunction> {
        if (functionsJson.isNullOrBlank()) return defaultFunctions()
        val root = runCatching { bridgeJson.parseToJsonElement(functionsJson) }.getOrNull()
        val arr = when (root) {
            is JsonArray -> root
            is JsonObject -> root["functions"] as? JsonArray
            else -> null
        } ?: return defaultFunctions()

        val out = arr.mapNotNull { el ->
            when (el) {
                is JsonPrimitive -> {
                    val name = el.contentOrNull?.trim().orEmpty()
                    if (name.isBlank()) null else PanelBridgeRegistry.BridgeFunction(name = name)
                }
                is JsonObject -> {
                    val name = el["name"]?.jsonPrimitive?.contentOrNull?.trim().orEmpty()
                    if (name.isBlank()) return@mapNotNull null
                    PanelBridgeRegistry.BridgeFunction(
                        name = name,
                        description = el["description"]?.jsonPrimitive?.contentOrNull,
                        isMutating = el["isMutating"]?.jsonPrimitive?.booleanOrNull == true,
                    )
                }
                else -> null
            }
        }
        return if (out.isEmpty()) defaultFunctions() else out.distinctBy { it.name.lowercase() }
    }

    private fun defaultFunctions(): List<PanelBridgeRegistry.BridgeFunction> = listOf(
        PanelBridgeRegistry.BridgeFunction(name = "getState", description = "Read current panel state"),
        PanelBridgeRegistry.BridgeFunction(name = "runAction", description = "Run panel action", isMutating = true),
    )
}

internal fun installPanelBridgeShim(webView: WebView) {
    val js = """
        (function(){
          if (window.__optimalxBridgeReady) return;
          window.__optimalxBridgeReady = true;
          const native = window.$BRIDGE_NAME;
          const bridge = {
            registerTools: function(functions) {
              try {
                if (!native || !native.registerTools) return;
                native.registerTools(JSON.stringify(functions || []));
              } catch (_e) {}
            },
            resolve: function(requestId, result) {
              try {
                if (!native || !native.resolve) return;
                const payload = (typeof result === 'string') ? result : JSON.stringify(result ?? null);
                native.resolve(String(requestId || ''), payload);
              } catch (_e) {}
            },
            reject: function(requestId, error) {
              try {
                if (!native || !native.reject) return;
                native.reject(String(requestId || ''), String(error || 'panel call failed'));
              } catch (_e) {}
            },
            emitEvent: function(eventName, payload) {
              try {
                if (!native || !native.emitEvent) return;
                const body = (typeof payload === 'string') ? payload : JSON.stringify(payload ?? null);
                native.emitEvent(String(eventName || 'event'), body);
              } catch (_e) {}
            },
            loadPersistedState: function() {
              try {
                if (!native || !native.loadPersistedState) return '{}';
                return native.loadPersistedState();
              } catch (_e) {
                return '{}';
              }
            },
            savePersistedState: function(stateJson) {
              try {
                if (!native || !native.savePersistedState) return;
                const body = (typeof stateJson === 'string') ? stateJson : JSON.stringify(stateJson ?? null);
                native.savePersistedState(body);
              } catch (_e) {}
            },
            _invokeFromAndroid: async function(requestId, functionName, argsJson) {
              try {
                const fn = window[functionName];
                if (typeof fn !== 'function') {
                  bridge.reject(requestId, 'Function not found: ' + functionName);
                  return;
                }
                let parsedArgs = {};
                if (argsJson && String(argsJson).trim().length > 0) {
                  parsedArgs = JSON.parse(argsJson);
                }
                const result = await fn(parsedArgs);
                bridge.resolve(requestId, result);
              } catch (err) {
                bridge.reject(requestId, err && err.message ? err.message : String(err));
              }
            }
          };
          window.OptimalXPanelBridge = bridge;

          const defaultFns = [
            { name: 'getState', description: 'Read current panel state', isMutating: false },
            { name: 'runAction', description: 'Run panel action', isMutating: true }
          ];
          bridge.registerTools(defaultFns);

          // Some panels call registerTools() before this shim exists (during early script load).
          // Retry a few times after bridge install so custom tool catalogs are not missed.
          function tryReRegisterCustomTools() {
            try {
              if (typeof window.registerBridgeTools === 'function') {
                window.registerBridgeTools();
              }
            } catch (_e) {}
          }

          [0, 60, 240, 900].forEach(function(delay) {
            setTimeout(tryReRegisterCustomTools, delay);
          });

          try {
            window.dispatchEvent(new Event('optimalx-bridge-ready'));
          } catch (_e) {}
        })();
    """.trimIndent()
    webView.evaluateJavascript(js, null)
}

internal fun bridgeInvokeJs(webView: WebView, requestId: String, functionName: String, argsJson: String) {
    val safeRequestId = jsSingleQuote(requestId)
    val safeFunctionName = jsSingleQuote(functionName)
    val safeArgsJson = jsSingleQuote(argsJson)
    val js = "window.OptimalXPanelBridge && window.OptimalXPanelBridge._invokeFromAndroid('$safeRequestId','$safeFunctionName','$safeArgsJson');"
    webView.post { webView.evaluateJavascript(js, null) }
}

internal fun restorePersistedPanelState(webView: WebView, stateJson: String) {
    val trimmed = stateJson.trim()
    if (trimmed.isBlank() || trimmed == "{}") return
    val safeJson = jsSingleQuote(trimmed)
    val js = """
        (function() {
          try {
            var state = JSON.parse('$safeJson');
            if (typeof window.panelRestoreState === 'function') {
              window.panelRestoreState(state);
              return;
            }
            if (typeof window.panelHandleAction === 'function') {
              window.panelHandleAction({ action: '__restoreState', state: state });
            }
          } catch (_e) {}
        })();
    """.trimIndent()
    webView.post { webView.evaluateJavascript(js, null) }
}

private fun jsSingleQuote(raw: String): String {
    return raw
        .replace("\\", "\\\\")
        .replace("'", "\\'")
        .replace("\n", "\\n")
        .replace("\r", "")
}

internal const val PANEL_BRIDGE_JS_INTERFACE_NAME: String = BRIDGE_NAME
