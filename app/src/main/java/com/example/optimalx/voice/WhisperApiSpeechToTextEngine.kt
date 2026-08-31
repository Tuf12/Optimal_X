package com.example.optimalx.voice

import android.content.Context
import android.os.Handler
import android.os.Looper
import android.speech.SpeechRecognizer
import android.util.Log
import androidx.annotation.MainThread
import com.example.optimalx.voice.pipeline.TranscriptAssembler
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import java.io.File

/**
 * Records PCM locally, then uploads to OpenAI Whisper on [stopListening].
 */
class WhisperApiSpeechToTextEngine(context: Context) : SttEngine {
    companion object {
        private const val TAG = "OptimalX.WhisperSTT"
    }

    override var onPartialResult: ((String) -> Unit)? = null
    override var onFinalResult: ((String) -> Unit)? = null
    override var onError: ((Int) -> Unit)? = null
    override var onListeningEnded: (() -> Unit)? = null
    override var onRestartingChanged: ((Boolean) -> Unit)? = null

    private val appContext = context.applicationContext
    private val mainHandler = Handler(Looper.getMainLooper())
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private val recorder = AudioCaptureBuffer()
    private val assembler = TranscriptAssembler()
    private val client = WhisperTranscriptionClient(appContext)

    private var micOpen = false
    private var capturePaused = false
    private var destroyed = false
    private var terminalDelivered = false
    private var transcribing = false
    private var transcribeJob: Job? = null

    override fun isReadyForSession(): Boolean = !destroyed && !transcribing

    override fun hasBufferedCapture(): Boolean = capturePaused && recorder.hasAudio()

    @MainThread
    override fun startListening(baseText: String) {
        runOnMain {
            if (destroyed || transcribing) return@runOnMain
            terminalDelivered = false
            capturePaused = false
            micOpen = true
            assembler.setBase(baseText)
            onPartialResult?.invoke(assembler.displayText())
            runCatching { recorder.start() }
                .onFailure { error ->
                    Log.e(TAG, "Failed to start audio capture", error)
                    micOpen = false
                    onError?.invoke(SpeechRecognizer.ERROR_CLIENT)
                }
        }
    }

    @MainThread
    override fun pauseCapture() {
        runOnMain {
            if (destroyed || !micOpen || transcribing) return@runOnMain
            micOpen = false
            capturePaused = true
            runCatching { recorder.pause() }
                .onFailure { error ->
                    Log.e(TAG, "Failed to pause audio capture", error)
                    capturePaused = false
                    onError?.invoke(SpeechRecognizer.ERROR_CLIENT)
                }
        }
    }

    @MainThread
    override fun resumeCapture() {
        runOnMain {
            if (destroyed || !capturePaused || transcribing || micOpen) return@runOnMain
            capturePaused = false
            micOpen = true
            runCatching { recorder.resume() }
                .onFailure { error ->
                    Log.e(TAG, "Failed to resume audio capture", error)
                    micOpen = false
                    capturePaused = true
                    onError?.invoke(SpeechRecognizer.ERROR_CLIENT)
                }
        }
    }

    @MainThread
    override fun discardCapture() {
        runOnMain {
            transcribeJob?.cancel()
            transcribeJob = null
            micOpen = false
            capturePaused = false
            transcribing = false
            recorder.clear()
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
            if (transcribing) return@runOnMain
            if (!micOpen && !capturePaused) {
                if (!transcribing) deliverTerminalOnce(assembler.snapshot())
                return@runOnMain
            }
            micOpen = false
            capturePaused = false
            transcribing = true
            onPartialResult?.invoke(assembler.displayText())
            transcribeJob = scope.launch {
                val wavFile = File(appContext.cacheDir, "whisper_stt_${System.currentTimeMillis()}.wav")
                val hadAudio = recorder.hasAudio()
                val transcript = try {
                    if (!hadAudio) {
                        ""
                    } else if (!recorder.stopAndWriteWav(wavFile)) {
                        assembler.snapshot()
                    } else {
                        client.transcribe(wavFile).getOrElse { error ->
                            Log.e(TAG, "Whisper transcription failed", error)
                            onError?.invoke(SpeechRecognizer.ERROR_NETWORK)
                            assembler.snapshot()
                        }
                    }
                } finally {
                    wavFile.delete()
                    transcribing = false
                    transcribeJob = null
                }
                val merged = mergeWithBase(assembler.committedText, transcript)
                deliverTerminalOnce(merged)
            }
        }
    }

    @MainThread
    override fun destroy() {
        runOnMain {
            destroyed = true
            transcribeJob?.cancel()
            transcribeJob = null
            micOpen = false
            capturePaused = false
            transcribing = false
            recorder.clear()
            scope.cancel()
            assembler.reset()
        }
    }

    private fun mergeWithBase(base: String, heard: String): String {
        val prefix = base.trim()
        val text = heard.trim()
        if (prefix.isBlank()) return text
        if (text.isBlank()) return prefix
        return "$prefix $text".trim()
    }

    private fun deliverTerminalOnce(text: String) {
        if (terminalDelivered || destroyed) return
        terminalDelivered = true
        assembler.reset()
        onFinalResult?.invoke(text.trim())
        onListeningEnded?.invoke()
    }

    private fun runOnMain(block: () -> Unit) {
        if (Looper.myLooper() == Looper.getMainLooper()) block() else mainHandler.post(block)
    }
}
