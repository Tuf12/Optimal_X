package com.example.optimalx.voice

import android.content.Context
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

/**
 * App-scoped chunked text-to-speech with pause, ±10s skip, and notification controls.
 * Shared by note editor, DumpEdit, and Eidos chat surfaces.
 */
class ReadAloudSession(context: Context) : Controls {

    private val appContext = context.applicationContext
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private var tts: TextToSpeechEngine? = null

    private val lock = Any()
    private var chunks: List<String> = emptyList()
    private var chunkStarts: IntArray = IntArray(0)
    private var chunkIndex: Int = 0
    private var wantsPlaying: Boolean = false
    private var notificationTitle: String = DEFAULT_NOTIFICATION_TITLE
    private var onComplete: (() -> Unit)? = null

    private val _barVisible = MutableStateFlow(false)
    val barVisible: StateFlow<Boolean> = _barVisible.asStateFlow()

    private val _isPlaying = MutableStateFlow(false)
    val isPlaying: StateFlow<Boolean> = _isPlaying.asStateFlow()

    fun startFromNote(content: String, onComplete: (() -> Unit)? = null) {
        val sanitized = sanitizeNoteContentForTts(content)
        startInternal(sanitized, NOTE_NOTIFICATION_TITLE, onComplete)
    }

    fun startFromChat(text: String, onComplete: (() -> Unit)? = null) {
        val sanitized = stripMarkdownForTts(text.trim())
        startInternal(sanitized, CHAT_NOTIFICATION_TITLE, onComplete)
    }

    private fun startInternal(sanitized: String, title: String, complete: (() -> Unit)?) {
        if (sanitized.isBlank()) {
            complete?.invoke()
            return
        }
        val split = chunkNoteForReadAloud(sanitized)
        if (split.isEmpty()) {
            complete?.invoke()
            return
        }
        synchronized(lock) {
            chunks = split
            chunkStarts = buildReadAloudChunkStarts(split)
            chunkIndex = 0
            wantsPlaying = true
            notificationTitle = title
            onComplete = complete
            _barVisible.value = true
            _isPlaying.value = true
        }
        syncNotification()
        ensureTts().stop()
        enqueueSpeakChainFromCurrentChunk()
    }

    fun togglePlayback() {
        val startPlayback: Boolean
        synchronized(lock) {
            if (!_barVisible.value || chunks.isEmpty()) return
            startPlayback = !_isPlaying.value
            wantsPlaying = startPlayback
            _isPlaying.value = startPlayback
            if (!startPlayback) {
                tts?.stop()
                syncNotification()
                return
            }
            if (chunkIndex >= chunks.size) {
                chunkIndex = 0
            }
        }
        syncNotification()
        if (startPlayback) enqueueSpeakChainFromCurrentChunk()
    }

    fun rewind10Seconds() {
        val shouldResume: Boolean
        synchronized(lock) {
            if (chunks.isEmpty()) return
            val idx = chunkIndex.coerceAtMost(chunks.lastIndex.coerceAtLeast(0))
            val curStart = chunkStarts[idx]
            val targetOffset = (curStart - NOTE_READ_ALOUD_SKIP_CHARS).coerceAtLeast(0)
            chunkIndex = chunkIndexForCharOffset(chunkStarts, targetOffset)
            shouldResume = wantsPlaying
        }
        tts?.stop()
        syncNotification()
        if (shouldResume) enqueueSpeakChainFromCurrentChunk()
    }

    fun forward10Seconds() {
        val shouldResume: Boolean
        synchronized(lock) {
            if (chunks.isEmpty()) return
            val totalLen = chunkStarts.last()
            val idx = chunkIndex.coerceAtMost(chunks.lastIndex.coerceAtLeast(0))
            val curStart = chunkStarts[idx]
            val targetOffset =
                (curStart + NOTE_READ_ALOUD_SKIP_CHARS).coerceAtMost((totalLen - 1).coerceAtLeast(0))
            chunkIndex = chunkIndexForCharOffset(chunkStarts, targetOffset)
            shouldResume = wantsPlaying
        }
        tts?.stop()
        syncNotification()
        if (shouldResume) enqueueSpeakChainFromCurrentChunk()
    }

    fun stop() {
        val complete: (() -> Unit)?
        synchronized(lock) {
            wantsPlaying = false
            chunks = emptyList()
            chunkStarts = IntArray(0)
            chunkIndex = 0
            complete = onComplete
            onComplete = null
            _barVisible.value = false
            _isPlaying.value = false
        }
        tts?.stop()
        syncNotification()
        complete?.invoke()
    }

    private fun finishNaturally() {
        val complete: (() -> Unit)?
        synchronized(lock) {
            wantsPlaying = false
            _isPlaying.value = false
            _barVisible.value = false
            chunks = emptyList()
            chunkStarts = IntArray(0)
            chunkIndex = 0
            complete = onComplete
            onComplete = null
        }
        syncNotification()
        complete?.invoke()
    }

    private fun ensureTts(): TextToSpeechEngine {
        return tts ?: TextToSpeechEngine(appContext).also { tts = it }
    }

    private fun enqueueSpeakChainFromCurrentChunk() {
        scope.launch {
            val chunkText: String
            val utteranceIndex: Int
            synchronized(lock) {
                if (!wantsPlaying) return@launch
                if (chunkIndex >= chunks.size) {
                    finishNaturally()
                    return@launch
                }
                utteranceIndex = chunkIndex
                chunkText = chunks[utteranceIndex]
            }
            ensureTts().speak(chunkText) {
                scope.launch {
                    synchronized(lock) {
                        if (!wantsPlaying) return@launch
                        if (chunkIndex != utteranceIndex) return@launch
                        chunkIndex++
                        if (chunkIndex >= chunks.size) {
                            finishNaturally()
                            return@launch
                        }
                    }
                    enqueueSpeakChainFromCurrentChunk()
                }
            }
        }
    }

    private fun syncNotification() {
        val visible = _barVisible.value
        if (!visible) {
            NoteReadAloudNotification.hide(appContext)
            return
        }
        NoteReadAloudNotification.show(appContext, _isPlaying.value, notificationTitle)
    }

    override fun onToggle() = togglePlayback()

    override fun onRewind10() = rewind10Seconds()

    override fun onForward10() = forward10Seconds()

    override fun onStop() = stop()

    companion object {
        private const val DEFAULT_NOTIFICATION_TITLE = "Reading aloud"
        private const val NOTE_NOTIFICATION_TITLE = "Reading note aloud"
        private const val CHAT_NOTIFICATION_TITLE = "Reading Eidos reply"
    }
}
