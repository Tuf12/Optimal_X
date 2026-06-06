package com.example.optimalx.data.eidos

import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import java.util.concurrent.ConcurrentHashMap

/**
 * Process-wide registry for in-flight Eidos sends.
 *
 * Survives Activity/ViewModel recreation: a fresh ViewModel can ask whether a conversation
 * has a send in flight and observe completion to refresh the chat from the database without
 * dropping the response.
 */
object EidosActiveSendRegistry {

    private val activeJobs = ConcurrentHashMap<Long, Job>()

    private val _activeConversationIds = MutableStateFlow<Set<Long>>(emptySet())
    val activeConversationIds: StateFlow<Set<Long>> = _activeConversationIds.asStateFlow()

    private val _completionEvents = MutableSharedFlow<Long>(extraBufferCapacity = 16)
    /** Emits a conversationId whenever its in-flight send finishes (success, error, or cancel). */
    val completionEvents: SharedFlow<Long> = _completionEvents.asSharedFlow()

    fun isActive(conversationId: Long): Boolean = activeJobs.containsKey(conversationId)

    fun register(conversationId: Long, job: Job) {
        activeJobs[conversationId] = job
        _activeConversationIds.value = activeJobs.keys.toSet()
        job.invokeOnCompletion {
            if (activeJobs[conversationId] === job) {
                activeJobs.remove(conversationId)
                _activeConversationIds.value = activeJobs.keys.toSet()
            }
            _completionEvents.tryEmit(conversationId)
        }
    }

    fun cancel(conversationId: Long) {
        activeJobs[conversationId]?.cancel()
    }
}
