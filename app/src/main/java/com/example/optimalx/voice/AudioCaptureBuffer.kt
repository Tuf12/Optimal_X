package com.example.optimalx.voice

import android.media.AudioFormat
import android.media.AudioRecord
import android.media.MediaRecorder
import com.example.optimalx.voice.pipeline.VoicePipelineConfig
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.FileOutputStream

/**
 * Captures mono 16 kHz PCM while the mic is open and can encode WAV for Whisper / local Gemma scribe.
 * Supports pause/resume without flushing the accumulated buffer, and mid-session PCM range copies
 * so Gemma can transcribe rolling slices without stopping [AudioRecord].
 */
class AudioCaptureBuffer {
    private val pcmStream = ByteArrayOutputStream()
    private var audioRecord: AudioRecord? = null
    private var captureThread: Thread? = null
    @Volatile private var capturing = false

    /** Absolute byte index of [pcmStream] index 0 (advances when prefix is dropped). */
    private var pcmOriginBytes: Int = 0

    val isCapturing: Boolean get() = capturing

    fun hasAudio(): Boolean = synchronized(pcmStream) { pcmStream.size() > 0 }

    /** Bytes currently held in the buffer (not including already-dropped prefix). */
    fun bufferedPcmBytes(): Int = synchronized(pcmStream) { pcmStream.size() }

    /** Absolute end of buffered PCM (= origin + size). */
    fun absolutePcmEnd(): Int = synchronized(pcmStream) { pcmOriginBytes + pcmStream.size() }

    fun pcmOrigin(): Int = synchronized(pcmStream) { pcmOriginBytes }

    fun start() {
        stopInternal(clearBuffer = true)
        startRecordingThread()
    }

    /** Stops [AudioRecord] but keeps captured PCM for later resume or commit. */
    fun pause() {
        stopInternal(clearBuffer = false)
    }

    /** Continues appending to the existing PCM buffer. */
    fun resume() {
        if (capturing) return
        startRecordingThread()
    }

    fun stopAndWriteWav(target: File): Boolean {
        stopInternal(clearBuffer = false)
        val pcm = synchronized(pcmStream) { pcmStream.toByteArray() }
        clearPcmLocked()
        if (pcm.isEmpty()) return false
        writeWavFile(target, pcm)
        return true
    }

    /**
     * Returns a WAV-wrapped copy of the capture and clears the buffer.
     * LiteRT-LM miniaudio needs a container (WAV), not raw PCM16.
     */
    fun stopAndGetWavBytes(): ByteArray? {
        stopInternal(clearBuffer = false)
        val pcm = synchronized(pcmStream) { pcmStream.toByteArray() }
        clearPcmLocked()
        if (pcm.isEmpty()) return null
        return AudioWavCodec.encodeWav(pcm)
    }

    /**
     * Copy absolute PCM range [absStart, absEnd) as a WAV without stopping capture.
     * Returns null if the range is empty or not fully buffered yet.
     */
    fun copyWavRange(absStart: Int, absEnd: Int): ByteArray? {
        val start = AudioWavCodec.alignToFrame(absStart)
        val end = AudioWavCodec.alignToFrame(absEnd)
        if (end <= start) return null
        val pcm = synchronized(pcmStream) {
            val localStart = start - pcmOriginBytes
            val localEnd = end - pcmOriginBytes
            if (localStart < 0 || localEnd > pcmStream.size() || localStart >= localEnd) {
                return null
            }
            pcmStream.toByteArray().copyOfRange(localStart, localEnd)
        }
        return AudioWavCodec.encodeWav(pcm)
    }

    /**
     * Drop buffered PCM before [absKeepFrom] (absolute index). Keeps [absKeepFrom, end).
     * Used after a rolling slice is committed so long meetings do not retain all PCM in RAM.
     */
    fun dropPcmBefore(absKeepFrom: Int) {
        val keepFrom = AudioWavCodec.alignToFrame(absKeepFrom)
        synchronized(pcmStream) {
            val localKeep = keepFrom - pcmOriginBytes
            if (localKeep <= 0) return
            if (localKeep >= pcmStream.size()) {
                clearPcmLocked()
                pcmOriginBytes = keepFrom
                return
            }
            val kept = pcmStream.toByteArray().copyOfRange(localKeep, pcmStream.size())
            pcmStream.reset()
            pcmStream.write(kept)
            pcmOriginBytes = keepFrom
        }
    }

    fun clear() {
        stopInternal(clearBuffer = true)
    }

    private fun clearPcmLocked() {
        pcmStream.reset()
        pcmOriginBytes = 0
    }

    private fun startRecordingThread() {
        val minBuffer = AudioRecord.getMinBufferSize(
            VoicePipelineConfig.SAMPLE_RATE_HZ,
            AudioFormat.CHANNEL_IN_MONO,
            AudioFormat.ENCODING_PCM_16BIT,
        )
        require(minBuffer > 0) { "AudioRecord buffer size invalid" }
        val record = AudioRecord(
            MediaRecorder.AudioSource.MIC,
            VoicePipelineConfig.SAMPLE_RATE_HZ,
            AudioFormat.CHANNEL_IN_MONO,
            AudioFormat.ENCODING_PCM_16BIT,
            minBuffer * 2,
        )
        require(record.state == AudioRecord.STATE_INITIALIZED) { "AudioRecord failed to initialize" }
        audioRecord = record
        capturing = true
        record.startRecording()
        captureThread = Thread {
            val buffer = ByteArray(minBuffer)
            while (capturing) {
                val read = record.read(buffer, 0, buffer.size)
                if (read > 0) {
                    synchronized(pcmStream) {
                        pcmStream.write(buffer, 0, read)
                    }
                }
            }
        }.apply {
            name = "OptimalX.AudioCapture"
            start()
        }
    }

    private fun stopInternal(clearBuffer: Boolean) {
        capturing = false
        captureThread?.join(500)
        captureThread = null
        audioRecord?.let { record ->
            runCatching {
                if (record.recordingState == AudioRecord.RECORDSTATE_RECORDING) {
                    record.stop()
                }
            }
            record.release()
        }
        audioRecord = null
        if (clearBuffer) {
            synchronized(pcmStream) {
                clearPcmLocked()
            }
        }
    }

    private fun writeWavFile(file: File, pcm: ByteArray) {
        file.parentFile?.mkdirs()
        FileOutputStream(file).use { out -> out.write(AudioWavCodec.encodeWav(pcm)) }
    }
}
