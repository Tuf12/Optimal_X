package com.example.optimalx.voice

import android.media.AudioFormat
import android.media.AudioRecord
import android.media.MediaRecorder
import com.example.optimalx.voice.pipeline.VoicePipelineConfig
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.FileOutputStream
import java.nio.ByteBuffer
import java.nio.ByteOrder

/**
 * Captures mono 16 kHz PCM while the mic is open and can encode a WAV file for Whisper upload.
 * Supports pause/resume without flushing the accumulated buffer.
 */
class AudioCaptureBuffer {
    private val pcmStream = ByteArrayOutputStream()
    private var audioRecord: AudioRecord? = null
    private var captureThread: Thread? = null
    @Volatile private var capturing = false

    val isCapturing: Boolean get() = capturing

    fun hasAudio(): Boolean = synchronized(pcmStream) { pcmStream.size() > 0 }

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
        pcmStream.reset()
        if (pcm.isEmpty()) return false
        writeWavFile(target, pcm)
        return true
    }

    fun clear() {
        stopInternal(clearBuffer = true)
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
                pcmStream.reset()
            }
        }
    }

    private fun writeWavFile(file: File, pcm: ByteArray) {
        file.parentFile?.mkdirs()
        val totalDataLen = pcm.size + 36
        val byteRate = VoicePipelineConfig.SAMPLE_RATE_HZ * 2
        FileOutputStream(file).use { out ->
            out.write("RIFF".toByteArray())
            out.write(intToLittleEndian(totalDataLen))
            out.write("WAVE".toByteArray())
            out.write("fmt ".toByteArray())
            out.write(intToLittleEndian(16))
            out.write(shortToLittleEndian(1))
            out.write(shortToLittleEndian(1))
            out.write(intToLittleEndian(VoicePipelineConfig.SAMPLE_RATE_HZ))
            out.write(intToLittleEndian(byteRate))
            out.write(shortToLittleEndian(2))
            out.write(shortToLittleEndian(16))
            out.write("data".toByteArray())
            out.write(intToLittleEndian(pcm.size))
            out.write(pcm)
        }
    }

    private fun intToLittleEndian(value: Int): ByteArray =
        ByteBuffer.allocate(4).order(ByteOrder.LITTLE_ENDIAN).putInt(value).array()

    private fun shortToLittleEndian(value: Int): ByteArray =
        ByteBuffer.allocate(2).order(ByteOrder.LITTLE_ENDIAN).putShort(value.toShort()).array()
}
