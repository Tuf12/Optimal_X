package com.example.optimalx.data.litert

import com.example.optimalx.data.eidos.model.EidosToolDefinition
import com.google.ai.edge.litertlm.ToolProvider
import com.google.ai.edge.litertlm.tool

object LitertLmToolRegistry {

    fun toolProviders(definitions: List<EidosToolDefinition>): List<ToolProvider> =
        definitions.map { definition ->
            tool(LitertLmOpenApiTool(definition))
        }
}
