package com.example.optimalx.voice

import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.speech.RecognitionListener
import android.speech.RecognizerIntent
import android.speech.SpeechRecognizer
import android.util.Log
import androidx.annotation.MainThread
import com.example.optimalx.voice.pipeline.TranscriptAssembler
import com.example.optimalx.voice.pipeline.VoicePipelineConfig

/**
 * Streaming STT using Android [SpeechRecognizer], preferring Google’s recognition
 * service when installed ([GOOGLE_QUICK_SEARCH_PACKAGE]).
 *
 * While the mic is open, each completed utterance from the engine immediately starts
 * another listen pass so push-to-talk matches continuous dictation UX.
 * [onFinalResult] and [onListeningEnded] run after [stopListening] once the recognizer
 * has flushed (typically from a final [onResults] or [onError]).
 */
class GoogleSpeechToTextEngine(private val context: Context) : SttEngine {

    companion object {
        private const val TAG = "OptimalX.STT"
        private const val GOOGLE_QUICK_SEARCH_PACKAGE = "com.google.android.googlequicksearchbox"

        /** Google-internal extra — enables dictation-oriented session behavior. */
        private const val EXTRA_DICTATION_MODE = "android.speech.extra.DICTATION_MODE"

        /** Google-internal extra — suppresses trailing silence beeps between restarts. */
        private const val EXTRA_HIDE_PARTIAL_TRAILING_SILENCE =
            "android.speech.extra.HIDE_PARTIAL_TRAILING_SILENCE"

        private const val COMPLETE_SILENCE_MS = 10_000L
        private const val POSSIBLY_COMPLETE_SILENCE_MS = 6_000L
        private const val MINIMUM_LENGTH_MS = 60_000L

        private val GOOGLE_RECOGNITION_SERVICES = listOf(
            ComponentName(
                GOOGLE_QUICK_SEARCH_PACKAGE,
                "com.google.android.voicesearch.serviceapi.GoogleRecognitionService",
            ),
            ComponentName(
                GOOGLE_QUICK_SEARCH_PACKAGE,
                "com.google.android.apps.search.assistant.surfaces.voice.robinhood.service.AudioRecognitionService",
            ),
        )
    }

    override var onPartialResult: ((String) -> Unit)? = null
    override var onFinalResult: ((String) -> Unit)? = null
    override var onError: ((Int) -> Unit)? = null
    override var onListeningEnded: (() -> Unit)? = null
    override var onRestartingChanged: ((Boolean) -> Unit)? = null

    private val mainHandler = Handler(Looper.getMainLooper())
    private val appContext = context.applicationContext
    private val assembler = TranscriptAssembler()

    private var recognizer: SpeechRecognizer? = null
    private var micOpen = false
    private var capturePaused = false
    private var destroyed = false
    private var lastPartialEmitMs = 0L
    private var terminalDelivered = false

    override fun hasBufferedCapture(): Boolean =
        capturePaused && assembler.displayText().trim().isNotEmpty()

    private val listener = object : RecognitionListener {
        override fun onReadyForSpeech(params: Bundle?) {}
        override fun onBeginningOfSpeech() {}
        override fun onRmsChanged(rmsdB: Float) {}
        override fun onBufferReceived(buffer: ByteArray?) {}
        override fun onEndOfSpeech() {}

        override fun onError(error: Int) {
            runOnMain { handleRecognizerError(error) }
        }

        override fun onResults(results: Bundle?) {
            runOnMain { handleResults(results) }
        }

        override fun onPartialResults(partialResults: Bundle?) {
            runOnMain {
                if (!micOpen || destroyed) return@runOnMain
                extractBestHypothesis(partialResults)?.trim()?.let { assembler.setPartial(it) }
                emitPartialThrottled()
            }
        }

        override fun onEvent(eventType: Int, params: Bundle?) {}
    }

    @MainThread
    override fun startListening(baseText: String) {
        runOnMain {
            if (destroyed) return@runOnMain
            if (!SpeechRecognizer.isRecognitionAvailable(appContext)) {
                Log.e(TAG, "Speech recognition is not available on this device")
                onError?.invoke(SpeechRecognizer.ERROR_CLIENT)
                return@runOnMain
            }
            onRestartingChanged?.invoke(false)
            terminalDelivered = false
            capturePaused = false
            micOpen = true
            assembler.setBase(baseText)
            lastPartialEmitMs = 0L
            emitPartialThrottled(force = true)
            releaseRecognizer()
            val rec = createRecognizer() ?: run {
                micOpen = false
                Log.e(TAG, "Could not create SpeechRecognizer")
                onError?.invoke(SpeechRecognizer.ERROR_CLIENT)
                return@runOnMain
            }
            recognizer = rec
            try {
                rec.startListening(buildListenIntent())
            } catch (t: Throwable) {
                Log.e(TAG, "startListening failed", t)
                micOpen = false
                onError?.invoke(SpeechRecognizer.ERROR_CLIENT)
            }
        }
    }

    @MainThread
    override fun pauseCapture() {
        runOnMain {
            if (destroyed || !micOpen) return@runOnMain
            micOpen = false
            capturePaused = true
            onRestartingChanged?.invoke(false)
            mainHandler.removeCallbacksAndMessages(null)
            releaseRecognizer()
            emitPartialThrottled(force = true)
        }
    }

    @MainThread
    override fun resumeCapture() {
        runOnMain {
            if (destroyed || !capturePaused || micOpen) return@runOnMain
            capturePaused = false
            micOpen = true
            terminalDelivered = false
            lastPartialEmitMs = 0L
            emitPartialThrottled(force = true)
            val rec = createRecognizer() ?: run {
                micOpen = false
                capturePaused = true
                onError?.invoke(SpeechRecognizer.ERROR_CLIENT)
                return@runOnMain
            }
            recognizer = rec
            try {
                rec.startListening(buildListenIntent())
            } catch (t: Throwable) {
                Log.e(TAG, "resumeCapture startListening failed", t)
                micOpen = false
                capturePaused = true
                onError?.invoke(SpeechRecognizer.ERROR_CLIENT)
            }
        }
    }

    @MainThread
    override fun discardCapture() {
        runOnMain {
            micOpen = false
            capturePaused = false
            onRestartingChanged?.invoke(false)
            mainHandler.removeCallbacksAndMessages(null)
            releaseRecognizer()
            assembler.reset()
            terminalDelivered = false
        }
    }

    @MainThread
    override fun updateCommitBase(baseText: String) {
        runOnMain { assembler.setBase(baseText) }
    }

    @MainThread
    override fun stopListening() {
        runOnMain {
            if (destroyed) return@runOnMain
            micOpen = false
            capturePaused = false
            onRestartingChanged?.invoke(false)
            mainHandler.removeCallbacksAndMessages(null)
            val rec = recognizer
            if (rec != null) {
                try {
                    rec.stopListening()
                } catch (t: Throwable) {
                    Log.w(TAG, "stopListening failed", t)
                    deliverTerminalOnce()
                }
            } else {
                deliverTerminalOnce()
            }
        }
    }

    @MainThread
    override fun destroy() {
        runOnMain {
            destroyed = true
            micOpen = false
            capturePaused = false
            onRestartingChanged?.invoke(false)
            mainHandler.removeCallbacksAndMessages(null)
            releaseRecognizer()
            assembler.reset()
        }
    }

    private fun createRecognizer(): SpeechRecognizer? {
        val primary = createGoogleRecognizer()
        val rec = primary ?: SpeechRecognizer.createSpeechRecognizer(appContext)
        rec.setRecognitionListener(listener)
        return rec
    }

    private fun releaseRecognizer() {
        val rec = recognizer
        recognizer = null
        if (rec != null) {
            try {
                rec.cancel()
            } catch (_: Throwable) {
            }
            try {
                rec.destroy()
            } catch (_: Throwable) {
            }
        }
    }

    private fun createGoogleRecognizer(): SpeechRecognizer? {
        val pm = appContext.packageManager
        for (cmp in GOOGLE_RECOGNITION_SERVICES) {
            try {
                pm.getServiceInfo(cmp, PackageManager.GET_META_DATA)
                return SpeechRecognizer.createSpeechRecognizer(appContext, cmp)
            } catch (_: PackageManager.NameNotFoundException) {
            }
        }
        return null
    }

    private fun buildListenIntent(): Intent = Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH).apply {
        putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
        putExtra(RecognizerIntent.EXTRA_PARTIAL_RESULTS, true)
        putExtra(RecognizerIntent.EXTRA_CALLING_PACKAGE, appContext.packageName)
        putExtra(RecognizerIntent.EXTRA_MAX_RESULTS, 5)
        putExtra(RecognizerIntent.EXTRA_SPEECH_INPUT_COMPLETE_SILENCE_LENGTH_MILLIS, COMPLETE_SILENCE_MS)
        putExtra(
            RecognizerIntent.EXTRA_SPEECH_INPUT_POSSIBLY_COMPLETE_SILENCE_LENGTH_MILLIS,
            POSSIBLY_COMPLETE_SILENCE_MS,
        )
        putExtra(RecognizerIntent.EXTRA_SPEECH_INPUT_MINIMUM_LENGTH_MILLIS, MINIMUM_LENGTH_MS)
        putExtra(EXTRA_DICTATION_MODE, true)
        putExtra(EXTRA_HIDE_PARTIAL_TRAILING_SILENCE, true)
        setPackage(GOOGLE_QUICK_SEARCH_PACKAGE)
    }

    private fun handleResults(results: Bundle?) {
        if (destroyed) return
        extractBestHypothesis(results)?.trim()?.let { if (it.isNotEmpty()) assembler.commitFinal(it) }
        emitPartialThrottled(force = true)
        if (!micOpen) {
            deliverTerminalOnce()
            return
        }
        restartListeningAfterUtterance()
    }

    private fun restartListeningAfterUtterance() {
        onRestartingChanged?.invoke(true)
        mainHandler.postDelayed({
            onRestartingChanged?.invoke(false)
            if (!micOpen || destroyed) return@postDelayed
            releaseRecognizer()
            val rec = createRecognizer() ?: run {
                micOpen = false
                onError?.invoke(SpeechRecognizer.ERROR_CLIENT)
                deliverTerminalOnce()
                return@postDelayed
            }
            recognizer = rec
            try {
                rec.startListening(buildListenIntent())
            } catch (t: Throwable) {
                Log.e(TAG, "listen loop restart failed", t)
                micOpen = false
                onError?.invoke(SpeechRecognizer.ERROR_CLIENT)
                deliverTerminalOnce()
            }
        }, 120L)
    }

    private fun handleRecognizerError(error: Int) {
        if (destroyed) return
        if (!micOpen) {
            deliverTerminalOnce()
            return
        }
        when (error) {
            SpeechRecognizer.ERROR_NO_MATCH,
            SpeechRecognizer.ERROR_SPEECH_TIMEOUT,
            -> scheduleListenRetry(delayMs = 300L)
            SpeechRecognizer.ERROR_RECOGNIZER_BUSY -> scheduleListenRetry(delayMs = 400L)
            SpeechRecognizer.ERROR_INSUFFICIENT_PERMISSIONS -> {
                micOpen = false
                onError?.invoke(error)
                deliverTerminalOnce()
            }
            SpeechRecognizer.ERROR_CLIENT -> scheduleListenRetry(delayMs = 250L)
            else -> {
                onError?.invoke(error)
                scheduleListenRetry(delayMs = 300L)
            }
        }
    }

    private fun scheduleListenRetry(delayMs: Long) {
        if (!micOpen || destroyed) return
        onRestartingChanged?.invoke(true)
        mainHandler.postDelayed({
            onRestartingChanged?.invoke(false)
            if (!micOpen || destroyed) return@postDelayed
            releaseRecognizer()
            val rec = createRecognizer() ?: run {
                micOpen = false
                deliverTerminalOnce()
                return@postDelayed
            }
            recognizer = rec
            try {
                rec.startListening(buildListenIntent())
            } catch (t: Throwable) {
                Log.w(TAG, "retry startListening failed", t)
                micOpen = false
                deliverTerminalOnce()
            }
        }, delayMs)
    }

    private fun deliverTerminalOnce() {
        if (terminalDelivered || destroyed) return
        terminalDelivered = true
        val text = assembler.takeAndReset()
        onFinalResult?.invoke(text)
        onListeningEnded?.invoke()
    }

    private fun emitPartialThrottled(force: Boolean = false) {
        val now = System.currentTimeMillis()
        if (!force && now - lastPartialEmitMs < VoicePipelineConfig.UI_UPDATE_THROTTLE_MS) return
        lastPartialEmitMs = now
        onPartialResult?.invoke(assembler.displayText())
    }

    private fun extractBestHypothesis(bundle: Bundle?): String? {
        if (bundle == null) return null
        val list = bundle.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION) ?: return null
        if (list.isEmpty()) return null
        return list[0]
    }

    private fun runOnMain(block: () -> Unit) {
        if (Looper.myLooper() == Looper.getMainLooper()) block() else mainHandler.post(block)
    }
}

/** @deprecated Use [GoogleSpeechToTextEngine] */
typealias SpeechToTextEngine = GoogleSpeechToTextEngine
