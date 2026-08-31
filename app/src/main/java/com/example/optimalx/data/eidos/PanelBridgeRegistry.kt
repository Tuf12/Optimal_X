package com.example.optimalx.data.eidos

import com.example.optimalx.data.model.ConversationScopes
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withTimeoutOrNull
import java.util.UUID

/** App-global runtime registry for live Workshop/custom panel JS bridge instances. */
class PanelBridgeRegistry {

    data class BridgeFunction(
        val name: String,
        val description: String? = null,
        val isMutating: Boolean = false,
    )

    data class BridgeInstance(
        val instanceId: String,
        val workshopSubfolderId: Long,
        val contextType: String,
        val isVisible: Boolean,
        val isFocused: Boolean,
        val functions: List<BridgeFunction>,
        val endpoint: JsEndpoint,
        val updatedAt: Long = System.currentTimeMillis(),
    )

    data class ScopeSnapshot(
        val hasEligiblePanel: Boolean,
        val activeContextType: String?,
        val activeWorkshopSubfolderId: Long?,
        val availableFunctions: List<String>,
        val recentEvents: List<BridgeEvent>,
    )

    data class BridgeEvent(
        val instanceId: String,
        val workshopSubfolderId: Long,
        val contextType: String,
        val name: String,
        val payloadJson: String,
        val timestamp: Long = System.currentTimeMillis(),
    )

    data class CallResult(
        val instanceId: String,
        val workshopSubfolderId: Long,
        val contextType: String,
        val functionName: String,
        val resultJson: String,
    )

    interface JsEndpoint {
        fun invoke(requestId: String, functionName: String, argsJson: String)
    }

    private val mutex = Mutex()
    private val instances = linkedMapOf<String, BridgeInstance>()
    private val pending = mutableMapOf<String, CompletableDeferred<ResultPayload>>()
    private val recentEvents = ArrayDeque<BridgeEvent>()
    private val maxRecentEvents = 40

    private data class ResultPayload(
        val ok: Boolean,
        val payload: String,
    )

    suspend fun registerOrUpdate(instance: BridgeInstance) {
        mutex.withLock {
            instances[instance.instanceId] = instance.copy(updatedAt = System.currentTimeMillis())
        }
    }

    suspend fun updateFunctions(instanceId: String, functions: List<BridgeFunction>) {
        mutex.withLock {
            val cur = instances[instanceId] ?: return
            instances[instanceId] = cur.copy(
                functions = functions,
                updatedAt = System.currentTimeMillis(),
            )
        }
    }

    suspend fun updateVisibility(instanceId: String, isVisible: Boolean, isFocused: Boolean) {
        mutex.withLock {
            val cur = instances[instanceId] ?: return
            instances[instanceId] = cur.copy(
                isVisible = isVisible,
                isFocused = isFocused,
                updatedAt = System.currentTimeMillis(),
            )
        }
    }

    suspend fun unregister(instanceId: String) {
        mutex.withLock {
            instances.remove(instanceId)
        }
    }

    suspend fun resolve(requestId: String, resultJson: String) {
        val deferred = mutex.withLock { pending.remove(requestId) } ?: return
        deferred.complete(ResultPayload(ok = true, payload = resultJson))
    }

    suspend fun reject(requestId: String, errorMessage: String) {
        val deferred = mutex.withLock { pending.remove(requestId) } ?: return
        deferred.complete(ResultPayload(ok = false, payload = errorMessage))
    }

    suspend fun emitEvent(instanceId: String, eventName: String, payloadJson: String) {
        mutex.withLock {
            val source = instances[instanceId] ?: return
            recentEvents.addLast(
                BridgeEvent(
                    instanceId = source.instanceId,
                    workshopSubfolderId = source.workshopSubfolderId,
                    contextType = source.contextType,
                    name = eventName.ifBlank { "event" },
                    payloadJson = payloadJson,
                ),
            )
            while (recentEvents.size > maxRecentEvents) recentEvents.removeFirst()
        }
    }

    suspend fun snapshot(
        currentSubfolderId: Long?,
        currentScopeType: String?,
    ): ScopeSnapshot {
        val target = pickTarget(currentSubfolderId, currentScopeType)
        val allFunctions = target?.functions?.map { it.name }?.sorted().orEmpty()
        val eventsForTarget = eventsForTarget(target)
        return ScopeSnapshot(
            hasEligiblePanel = target != null,
            activeContextType = target?.contextType,
            activeWorkshopSubfolderId = target?.workshopSubfolderId,
            availableFunctions = allFunctions,
            recentEvents = eventsForTarget,
        )
    }

    suspend fun call(
        functionName: String,
        argsJson: String,
        currentSubfolderId: Long?,
        currentScopeType: String?,
        timeoutMs: Long = 6_000,
    ): CallResult {
        val target = pickTarget(currentSubfolderId, currentScopeType)
            ?: throw IllegalStateException(
                "No active panel bridge is available (Preview tab not open). " +
                    "Use workshop_read_file + workshop_write_file to patch code instead of call_panel_function.",
            )

        val declared = target.functions.any { it.name.equals(functionName, ignoreCase = true) }
        if (!declared) {
            throw IllegalArgumentException(
                "Function '$functionName' is not registered on the active panel. Registered: " +
                    target.functions.joinToString { it.name },
            )
        }

        val requestId = UUID.randomUUID().toString()
        val deferred = CompletableDeferred<ResultPayload>()
        mutex.withLock { pending[requestId] = deferred }

        try {
            target.endpoint.invoke(requestId, functionName, argsJson)
            val payload = withTimeoutOrNull(timeoutMs) { deferred.await() }
                ?: throw IllegalStateException("Panel bridge call timed out after ${timeoutMs}ms")
            if (!payload.ok) {
                throw IllegalStateException("Panel bridge rejected call: ${payload.payload}")
            }
            return CallResult(
                instanceId = target.instanceId,
                workshopSubfolderId = target.workshopSubfolderId,
                contextType = target.contextType,
                functionName = functionName,
                resultJson = payload.payload,
            )
        } finally {
            mutex.withLock { pending.remove(requestId) }
        }
    }

    private suspend fun pickTarget(
        currentSubfolderId: Long?,
        currentScopeType: String?,
    ): BridgeInstance? {
        val list = mutex.withLock { instances.values.toList() }
            .filter { it.isVisible }
            .filter {
                when (currentScopeType) {
                    ConversationScopes.PANEL_WORKSHOP ->
                        currentSubfolderId == null || it.workshopSubfolderId == currentSubfolderId
                    ConversationScopes.PANEL_RUNNER ->
                        currentSubfolderId != null &&
                            it.workshopSubfolderId == currentSubfolderId &&
                            it.contextType == "gallery"
                    else -> true
                }
            }
        return list
            .sortedWith(
                compareByDescending<BridgeInstance> {
                    when (currentScopeType) {
                        ConversationScopes.PANEL_RUNNER -> it.contextType == "gallery"
                        else -> true
                    }
                }
                    .thenByDescending { it.isFocused }
                    .thenByDescending { it.updatedAt },
            )
            .firstOrNull()
    }

    private fun eventsForTarget(target: BridgeInstance?): List<BridgeEvent> {
        if (target == null) return emptyList()
        return recentEvents
            .asReversed()
            .filter { it.instanceId == target.instanceId }
            .take(8)
            .reversed()
    }
}
