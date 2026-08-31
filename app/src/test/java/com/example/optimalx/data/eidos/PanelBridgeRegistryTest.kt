package com.example.optimalx.data.eidos

import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class PanelBridgeRegistryTest {

    @Test
    fun focusedVisiblePanelWins() = runBlocking {
        val registry = PanelBridgeRegistry()

        val endpointA = object : PanelBridgeRegistry.JsEndpoint {
            override fun invoke(requestId: String, functionName: String, argsJson: String) {
                runBlocking { registry.resolve(requestId, "{\"panel\":\"A\"}") }
            }
        }
        val endpointB = object : PanelBridgeRegistry.JsEndpoint {
            override fun invoke(requestId: String, functionName: String, argsJson: String) {
                runBlocking { registry.resolve(requestId, "{\"panel\":\"B\"}") }
            }
        }

        registry.registerOrUpdate(
            PanelBridgeRegistry.BridgeInstance(
                instanceId = "a",
                workshopSubfolderId = 10,
                contextType = "custom_panel",
                isVisible = true,
                isFocused = false,
                functions = listOf(PanelBridgeRegistry.BridgeFunction("getState")),
                endpoint = endpointA,
            ),
        )
        registry.registerOrUpdate(
            PanelBridgeRegistry.BridgeInstance(
                instanceId = "b",
                workshopSubfolderId = 11,
                contextType = "workshop_preview",
                isVisible = true,
                isFocused = true,
                functions = listOf(PanelBridgeRegistry.BridgeFunction("getState")),
                endpoint = endpointB,
            ),
        )

        val result = registry.call(
            functionName = "getState",
            argsJson = "{}",
            currentSubfolderId = null,
            currentScopeType = "subfolder",
        )

        assertEquals("b", result.instanceId)
        assertTrue(result.resultJson.contains("B"))
    }

    @Test
    fun noVisiblePanelThrowsActionableError() = runBlocking {
        val registry = PanelBridgeRegistry()
        var threw = false
        try {
            registry.call(
                functionName = "getState",
                argsJson = "{}",
                currentSubfolderId = 123,
                currentScopeType = "panel_workshop",
            )
        } catch (t: Throwable) {
            threw = true
            assertTrue(t.message.orEmpty().contains("No active panel bridge"))
        }
        assertTrue(threw)
    }
}
