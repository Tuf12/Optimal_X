package com.example.optimalx.voice

import android.content.Context
import android.os.LocaleList
import android.speech.tts.TextToSpeech
import android.speech.tts.UtteranceProgressListener
import java.util.Locale
import java.util.UUID

/**
 * Thin wrapper around Android [TextToSpeech] using the user's preferred engine from system
 * settings (no explicit engine package — same as [TextToSpeech]’s default constructor).
 *
 * After init we call [TextToSpeech.setLanguage] with the primary [LocaleList] entry (and fall back
 * to US English). That matches how many OEM/Google engines map “device locale” to the voice the
 * user selected under Settings › Text-to-speech output, rather than staying on an engine-internal
 * generic default.
 *
 * Requests that arrive before initialization finishes are held and spoken once ready; a newer
 * request replaces a queued one (same idea as [TextToSpeech.QUEUE_FLUSH]).
 */
class TextToSpeechEngine(context: Context) {

    private val appContext = context.applicationContext
    private var tts: TextToSpeech? = null

    @Volatile
    private var initialized = false

    @Volatile
    private var initFailed = false

    private val lock = Any()
    private var pendingText: String? = null
    private var pendingOnDone: (() -> Unit)? = null

    private val pendingDoneCallbacks = mutableMapOf<String, () -> Unit>()

    init {
        tts = TextToSpeech(appContext, ::onInit)
    }

    private fun onInit(status: Int) {
        val engine: TextToSpeech
        val toSpeak: String?
        val toSpeakOnDone: (() -> Unit)?
        synchronized(lock) {
            engine = tts ?: return
            if (status != TextToSpeech.SUCCESS) {
                initFailed = true
                engine.shutdown()
                tts = null
                val stuck = pendingOnDone
                pendingText = null
                pendingOnDone = null
                stuck?.invoke()
                return
            }
            applyLocale(engine)
            initialized = true
            engine.setOnUtteranceProgressListener(object : UtteranceProgressListener() {
                override fun onStart(utteranceId: String?) = Unit
                override fun onDone(utteranceId: String?) {
                    pendingDoneCallbacks.remove(utteranceId)?.invoke()
                }
                @Deprecated("Deprecated in Java")
                override fun onError(utteranceId: String?) {
                    pendingDoneCallbacks.remove(utteranceId)?.invoke()
                }

                override fun onError(utteranceId: String?, errorCode: Int) {
                    pendingDoneCallbacks.remove(utteranceId)?.invoke()
                }
            })
            toSpeak = pendingText
            toSpeakOnDone = pendingOnDone
            pendingText = null
            pendingOnDone = null
        }
        if (toSpeak != null) {
            speakNow(engine, toSpeak, toSpeakOnDone)
        }
    }

    private fun applyLocale(engine: TextToSpeech) {
        val locale = resolveLocale()
        val langResult = runCatching { engine.setLanguage(locale) }
            .getOrElse { TextToSpeech.LANG_NOT_SUPPORTED }
        if (langResult == TextToSpeech.LANG_MISSING_DATA || langResult == TextToSpeech.LANG_NOT_SUPPORTED) {
            runCatching { engine.setLanguage(Locale.US) }
        }
    }

    private fun resolveLocale(): Locale {
        val list = LocaleList.getDefault()
        return if (list.size() > 0) list[0] else Locale.getDefault()
    }

    /** Speak [text]. Calls [onDone] when the utterance finishes (or immediately if TTS unavailable). */
    fun speak(text: String, onDone: (() -> Unit)? = null) {
        val speechText = stripMarkdownForTts(text)
        if (speechText.isBlank()) {
            onDone?.invoke()
            return
        }
        synchronized(lock) {
            if (initFailed) {
                onDone?.invoke()
                return
            }
            val engine = tts
            if (!initialized || engine == null) {
                pendingOnDone?.invoke()
                pendingText = speechText
                pendingOnDone = onDone
                return
            }
            speakNow(engine, speechText, onDone)
        }
    }

    private fun speakNow(engine: TextToSpeech, speechText: String, onDone: (() -> Unit)?) {
        val max = TextToSpeech.getMaxSpeechInputLength()
        val chunk = if (speechText.length <= max) {
            speechText
        } else {
            speechText.substring(0, (max - 1).coerceAtLeast(0)) + "…"
        }
        val id = UUID.randomUUID().toString()
        if (onDone != null) {
            pendingDoneCallbacks[id] = onDone
        }
        val code = engine.speak(chunk, TextToSpeech.QUEUE_FLUSH, null, id)
        if (code == TextToSpeech.ERROR) {
            pendingDoneCallbacks.remove(id)
            onDone?.invoke()
        }
    }

    fun stop() {
        synchronized(lock) {
            pendingText = null
            pendingOnDone = null
        }
        tts?.stop()
        pendingDoneCallbacks.clear()
    }

    fun destroy() {
        val stuckPending = synchronized(lock) {
            pendingText = null
            val cb = pendingOnDone
            pendingOnDone = null
            initialized = false
            initFailed = false
            val engine = tts
            tts = null
            engine?.stop()
            engine?.shutdown()
            cb
        }
        stuckPending?.invoke()
        pendingDoneCallbacks.clear()
    }
}
