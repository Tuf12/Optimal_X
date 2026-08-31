package com.example.optimalx.voice

import android.content.Context
import android.os.Handler
import android.os.Looper
import android.speech.SpeechRecognizer
import android.util.Log
import androidx.annotation.MainThread
import com.example.optimalx.OptimalXApplication
import com.example.optimalx.data.litert.GemmaLocalPolicy
import com.example.optimalx.data.litert.LitertLmScribeService
import com.example.optimalx.voice.pipeline.TranscriptAssembler
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext

/**
 * Records PCM continuously, then peels fixed-duration WAV slices for on-device Gemma
 * while the mic stays open (no VAD mic restart). Remaining audio is flushed on [stopListening].
 */
class GemmaLocalScribeEngine(context: Context) : SttEngine {
    companion object {
        private const val TAG = "OptimalX.GemmaScribe"
        private const val POLL_MS = 200L
    }

    override var onPartialResult: ((String) -> Unit)? = null
    override var onFinalResult: ((String) -> Unit)? = null
    override var onError: ((Int) -> Unit)? = null
    override var onListeningEnded: (() -> Unit)? = null
    override var onRestartingChanged: ((Boolean) -> Unit)? = null

    private val app = context.applicationContext as OptimalXApplication
    private val mainHandler = Handler(Looper.getMainLooper())
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private val recorder = AudioCaptureBuffer()
    private val assembler = TranscriptAssembler()
    private val scribeService = LitertLmScribeService(app)
    private val transcribeMutex = Mutex()

    private val sliceBytes = AudioWavCodec.pcmByteLength(GemmaLocalPolicy.SCRIBE_SLICE_SECONDS)
    private val overlapBytes = AudioWavCodec.pcmByteLength(GemmaLocalPolicy.SCRIBE_SLICE_OVERLAP_SECONDS)
    private val minFlushBytes = AudioWavCodec.pcmByteLength(GemmaLocalPolicy.SCRIBE_MIN_FLUSH_SECONDS)

    private var micOpen = false
    private var capturePaused = false
    private var destroyed = false
    private var terminalDelivered = false
    private var sessionActive = false
    private var flushing = false
    private var rollingJob: Job? = null
    private var flushJob: Job? = null

    /** Absolute PCM byte where the next non-overlapping slice begins. */
    private var nextSliceStartAbs: Int = 0

    override fun isReadyForSession(): Boolean =
        !destroyed && !flushing && flushJob?.isActive != true

    override fun hasBufferedCapture(): Boolean =
        capturePaused && (recorder.hasAudio() || assembler.committedText.isNotBlank())

    @MainThread
    override fun startListening(baseText: String) {
        runOnMain {
            if (destroyed || flushing) return@runOnMain
            terminalDelivered = false
            capturePaused = false
            micOpen = true
            sessionActive = true
            nextSliceStartAbs = 0
            assembler.setBase(baseText)
            onPartialResult?.invoke(assembler.displayText())
            runCatching { recorder.start() }
                .onFailure { error ->
                    Log.e(TAG, "Failed to start audio capture", error)
                    micOpen = false
                    sessionActive = false
                    onError?.invoke(SpeechRecognizer.ERROR_CLIENT)
                    return@runOnMain
                }
            scope.launch { scribeService.beginSession() }
            startRollingLoop()
        }
    }

    @MainThread
    override fun pauseCapture() {
        runOnMain {
            if (destroyed || !micOpen || flushing) return@runOnMain
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
            if (destroyed || !capturePaused || flushing || micOpen) return@runOnMain
            capturePaused = false
            micOpen = true
            sessionActive = true
            runCatching { recorder.resume() }
                .onFailure { error ->
                    Log.e(TAG, "Failed to resume audio capture", error)
                    micOpen = false
                    capturePaused = true
                    onError?.invoke(SpeechRecognizer.ERROR_CLIENT)
                    return@runOnMain
                }
            if (rollingJob?.isActive != true) startRollingLoop()
        }
    }

    @MainThread
    override fun discardCapture() {
        runOnMain {
            rollingJob?.cancel()
            rollingJob = null
            flushJob?.cancel()
            flushJob = null
            micOpen = false
            capturePaused = false
            sessionActive = false
            flushing = false
            nextSliceStartAbs = 0
            recorder.clear()
            assembler.reset()
            terminalDelivered = false
            scope.launch(NonCancellable) { scribeService.endSession() }
        }
    }

    @MainThread
    override fun updateCommitBase(baseText: String) {
        runOnMain {
            // After rolling slices start, assembler commits are authoritative. Re-seeding from
            // the composer (still holding pre-record input) would drop mid-session text.
            if (nextSliceStartAbs > 0 || flushing) return@runOnMain
            assembler.setBase(baseText)
        }
    }

    @MainThread
    override fun stopListening() {
        runOnMain {
            if (destroyed) return@runOnMain
            if (flushing) return@runOnMain
            if (!sessionActive && !micOpen && !capturePaused) {
                deliverTerminalOnce(assembler.snapshot())
                return@runOnMain
            }
            micOpen = false
            capturePaused = false
            sessionActive = false
            flushing = true
            runCatching { recorder.pause() }
            onPartialResult?.invoke(assembler.displayText())
            flushJob = scope.launch {
                try {
                    // Let in-flight rolling work finish, then drain the tail.
                    rollingJob?.join()
                    rollingJob = null
                    processAvailableSlices()
                    flushRemainingTail()
                } finally {
                    withContext(NonCancellable) { scribeService.endSession() }
                    flushing = false
                    flushJob = null
                }
                deliverTerminalOnce(assembler.snapshot())
            }
        }
    }

    @MainThread
    override fun destroy() {
        runOnMain {
            destroyed = true
            rollingJob?.cancel()
            rollingJob = null
            flushJob?.cancel()
            flushJob = null
            micOpen = false
            capturePaused = false
            sessionActive = false
            flushing = false
            nextSliceStartAbs = 0
            recorder.clear()
            assembler.reset()
            scope.launch(NonCancellable) {
                scribeService.endSession()
                scope.cancel()
            }
        }
    }

    private fun startRollingLoop() {
        if (rollingJob?.isActive == true) return
        rollingJob = scope.launch {
            while (isActive && !destroyed && sessionActive) {
                processAvailableSlices()
                delay(POLL_MS)
            }
        }
    }

    private suspend fun processAvailableSlices() {
        while (true) {
            val absEnd = recorder.absolutePcmEnd()
            val readyEnd = nextSliceStartAbs + sliceBytes
            if (absEnd < readyEnd) break
            if (!transcribeAbsoluteRange(
                    absStart = (nextSliceStartAbs - overlapBytes).coerceAtLeast(0),
                    absEnd = readyEnd,
                )
            ) {
                // Advance past a failed full slice so a bad clip cannot stall the loop.
                nextSliceStartAbs = readyEnd
                dropCommittedPrefix()
                break
            }
            nextSliceStartAbs = readyEnd
            dropCommittedPrefix()
        }
    }

    private suspend fun flushRemainingTail() {
        val absEnd = recorder.absolutePcmEnd()
        val newBytes = absEnd - nextSliceStartAbs
        if (newBytes < minFlushBytes) {
            recorder.clear()
            nextSliceStartAbs = 0
            return
        }
        val absStart = (nextSliceStartAbs - overlapBytes).coerceAtLeast(0)
        if (!transcribeAbsoluteRange(absStart, absEnd)) return
        nextSliceStartAbs = absEnd
        recorder.clear()
        nextSliceStartAbs = 0
    }

    private suspend fun transcribeAbsoluteRange(absStart: Int, absEnd: Int): Boolean {
        val wav = recorder.copyWavRange(absStart, absEnd) ?: return false
        return transcribeMutex.withLock {
            val text = scribeService.transcribe(wav).getOrElse { error ->
                Log.e(TAG, "Local Gemma transcription failed", error)
                runOnMain { onError?.invoke(SpeechRecognizer.ERROR_NETWORK) }
                return@withLock false
            }.trim()
            if (text.isNotEmpty()) {
                runOnMain {
                    assembler.commitFinal(text)
                    onPartialResult?.invoke(assembler.displayText())
                }
            }
            true
        }
    }

    private fun dropCommittedPrefix() {
        val keepFrom = (nextSliceStartAbs - overlapBytes).coerceAtLeast(0)
        if (keepFrom > recorder.pcmOrigin()) {
            recorder.dropPcmBefore(keepFrom)
        }
    }

    private fun deliverTerminalOnce(text: String) {
        if (terminalDelivered || destroyed) return
        terminalDelivered = true
        sessionActive = false
        assembler.reset()
        onFinalResult?.invoke(text.trim())
        onListeningEnded?.invoke()
    }

    private fun runOnMain(block: () -> Unit) {
        if (Looper.myLooper() == Looper.getMainLooper()) block() else mainHandler.post(block)
    }
}
