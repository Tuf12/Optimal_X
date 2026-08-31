package com.example.optimalx.voice

/**
 * Common speech-to-text engine contract for in-app, widget, and web search surfaces.
 */
interface SttEngine {
    var onPartialResult: ((String) -> Unit)?
    var onFinalResult: ((String) -> Unit)?
    var onError: ((Int) -> Unit)?
    var onListeningEnded: (() -> Unit)?
    var onRestartingChanged: ((Boolean) -> Unit)?

    fun startListening(baseText: String = "")

    /** False while a prior session is still finishing (e.g. Whisper upload in flight). */
    fun isReadyForSession(): Boolean = true

    /** True when capture is paused with audio or text kept in memory (no API call yet). */
    fun hasBufferedCapture(): Boolean = false

    /** Stop [AudioRecord] / recognizer without transcribing or delivering finals. */
    fun pauseCapture() {}

    /** Continue capture into the same buffer. */
    fun resumeCapture() {}

    /** Drop buffered audio/text without API or callbacks. */
    fun discardCapture() {}

    /** Update prefix merged on commit (e.g. composer text edited while paused). */
    fun updateCommitBase(baseText: String) {}

    fun stopListening()
    fun destroy()
}
