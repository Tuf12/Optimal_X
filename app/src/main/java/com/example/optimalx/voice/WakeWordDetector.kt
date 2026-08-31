package com.example.optimalx.voice

import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.speech.RecognitionListener
import android.speech.RecognizerIntent
import android.speech.SpeechRecognizer

/**
 * Lightweight always-on wake word detector using a SpeechRecognizer restart loop.
 * Only active while the home screen widget is enabled.
 *
 * Each cycle starts one recognition pass. If the wake word is found in the result,
 * [onDetected] fires and detection pauses for a cooldown period before resuming.
 * On error or silence, the cycle restarts immediately (with a short delay).
 *
 * Limitation: uses Google Cloud STT, so requires network and microphone permission.
 * For fully offline detection, replace the inner recognizer with an on-device keyword
 * detection model in a future iteration.
 */
class WakeWordDetector(private val context: Context) {

    private val handler = Handler(Looper.getMainLooper())
    private var recognizer: SpeechRecognizer? = null
    private var active = false
    private var wakeWord = ""
    private var detectedCallback: (() -> Unit)? = null

    fun startDetecting(wakeWord: String, onDetected: () -> Unit) {
        if (active) return
        this.wakeWord = wakeWord
        this.detectedCallback = onDetected
        active = true
        handler.post { startCycle() }
    }

    fun stopDetecting() {
        active = false
        handler.post {
            recognizer?.destroy()
            recognizer = null
        }
        detectedCallback = null
    }

    val isActive: Boolean get() = active

    private fun startCycle() {
        if (!active) return

        recognizer?.destroy()
        recognizer = SpeechRecognizer.createSpeechRecognizer(context).apply {
            setRecognitionListener(object : RecognitionListener {
                override fun onReadyForSpeech(params: Bundle?) = Unit
                override fun onBeginningOfSpeech() = Unit
                override fun onRmsChanged(rmsdB: Float) = Unit
                override fun onBufferReceived(buffer: ByteArray?) = Unit
                override fun onEndOfSpeech() = Unit
                override fun onEvent(eventType: Int, params: Bundle?) = Unit

                override fun onPartialResults(partialResults: Bundle?) {
                    val partial = partialResults
                        ?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)
                        ?.firstOrNull() ?: return
                    if (partial.contains(wakeWord, ignoreCase = true)) {
                        recognizer?.stopListening()
                        detectedCallback?.invoke()
                        // Cooldown before listening again so the detection doesn't fire repeatedly
                        if (active) handler.postDelayed({ startCycle() }, COOLDOWN_MS)
                    }
                }

                override fun onResults(results: Bundle?) {
                    val text = results
                        ?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)
                        ?.firstOrNull() ?: ""
                    if (text.contains(wakeWord, ignoreCase = true)) {
                        detectedCallback?.invoke()
                        if (active) handler.postDelayed({ startCycle() }, COOLDOWN_MS)
                    } else {
                        // No wake word — immediately restart cycle
                        if (active) handler.postDelayed({ startCycle() }, RESTART_DELAY_MS)
                    }
                }

                override fun onError(error: Int) {
                    // Brief delay on error to avoid rapid-fire restarts
                    if (active) handler.postDelayed({ startCycle() }, RESTART_DELAY_MS)
                }
            })
        }

        val intent = Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH).apply {
            putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
            putExtra(RecognizerIntent.EXTRA_PARTIAL_RESULTS, true)
            putExtra(RecognizerIntent.EXTRA_MAX_RESULTS, 1)
            putExtra(RecognizerIntent.EXTRA_CALLING_PACKAGE, context.packageName)
        }
        recognizer?.startListening(intent)
    }

    private companion object {
        const val COOLDOWN_MS = 3_000L
        const val RESTART_DELAY_MS = 800L
    }
}
