package com.example.optimalx.data.litert

import com.example.optimalx.data.eidos.model.EidosToolDefinition
import com.google.ai.edge.litertlm.OpenApiTool
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonObject

/**
 * Registers one Eidos tool with LiteRT-LM. Execution is handled by [com.example.optimalx.data.eidos.EidosApiClient]
 * (automaticToolCalling=false); [execute] should not run in production.
 */
class LitertLmOpenApiTool(
    private val definition: EidosToolDefinition,
) : OpenApiTool {

    override fun getToolDescriptionJsonString(): String =
        buildOpenApiToolJson(definition).toString()

    override fun execute(paramsJsonString: String): String =
        """{"error":"Tool execution is handled by OptimalX Eidos, not LiteRT automatic calling."}"""

    companion object {
        private val json = Json { ignoreUnknownKeys = true; explicitNulls = false }

        fun buildOpenApiToolJson(definition: EidosToolDefinition): JsonObject = buildJsonObject {
            put("name", definition.name)
            put("description", definition.description)
            put("parameters", definition.parametersSchema)
        }

        fun traceToolNames(definitions: List<EidosToolDefinition>): String =
            json.encodeToString(
                JsonObject.serializer(),
                buildJsonObject {
                    putJsonObject("tools") {
                        definitions.forEachIndexed { index, def ->
                            put(index.toString(), def.name)
                        }
                    }
                },
            )
    }
}
