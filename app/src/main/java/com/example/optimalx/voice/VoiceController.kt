package com.example.optimalx.voice

import android.app.Application
import android.util.Log
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.example.optimalx.data.preferences.ApiKeyNames
import com.example.optimalx.data.preferences.SettingsDefaults
import com.example.optimalx.data.preferences.SettingsKeys
import com.example.optimalx.data.preferences.getEncryptedPrefs
import com.example.optimalx.data.preferences.settingsDataStore
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch

enum class VoiceSessionState { IDLE, LISTENING, PAUSED, TRANSCRIBING, SPEAKING }

/**
 * Press-to-talk voice helper. Chat and notes use OpenAI Whisper when enabled and a key
 * is saved; otherwise [GoogleSpeechToTextEngine].
 *
 * Capture can be paused without transcribing; discard drops the buffer; send/commit runs STT.
 */
class VoiceController(app: Application) : AndroidViewModel(app) {
    companion object {
        private const val TAG = "OptimalX.Voice"
    }

    private val tts = TextToSpeechEngine(app)
    private var stt: SttEngine = VoiceRuntime.createGoogleOnlySttEngine(app)

    private val _sessionState = MutableStateFlow(VoiceSessionState.IDLE)
    val sessionState: StateFlow<VoiceSessionState> = _sessionState.asStateFlow()

    private val _liveTranscript = MutableStateFlow("")
    val liveTranscript: StateFlow<String> = _liveTranscript.asStateFlow()

    private val _isRestarting = MutableStateFlow(false)
    val isRestarting: StateFlow<Boolean> = _isRestarting.asStateFlow()

    private val _hasBufferedCapture = MutableStateFlow(false)
    val hasBufferedCapture: StateFlow<Boolean> = _hasBufferedCapture.asStateFlow()

    private val _usesWhisperCapture = MutableStateFlow(false)
    /** True when chat/notes mic uses buffered PCM + Whisper API (not Google streaming STT). */
    val usesWhisperCapture: StateFlow<Boolean> = _usesWhisperCapture.asStateFlow()

    private var activeOnResult: ((String) -> Unit)? = null
    private var pendingAfterVoiceCommit: (() -> Unit)? = null
    private var pendingStopIntent: StopIntent = StopIntent.NONE
    private var baseText = ""
    private var transcriptDraft: String = ""
    private var usingWhisperEngine = false

    init {
        wireSttCallbacks()
    }

    private fun wireSttCallbacks() {
        stt.onPartialResult = { partial ->
            if (_sessionState.value == VoiceSessionState.LISTENING) {
                transcriptDraft = partial.trim()
                _liveTranscript.value = transcriptDraft
            }
        }
        stt.onFinalResult = { final ->
            transcriptDraft = final.trim()
            when (_sessionState.value) {
                VoiceSessionState.LISTENING,
                VoiceSessionState.TRANSCRIBING,
                -> _liveTranscript.value = transcriptDraft
                else -> Unit
            }
            if (pendingStopIntent != StopIntent.NONE) {
                handleStopIntent()
            }
        }
        stt.onError = { code ->
            Log.d(TAG, "STT error code=$code")
            if (pendingStopIntent != StopIntent.NONE) {
                handleStopIntent()
            }
        }
        stt.onListeningEnded = {
            if (_sessionState.value == VoiceSessionState.LISTENING && pendingStopIntent == StopIntent.NONE) {
                val text = transcriptDraft.trim()
                if (text.isNotBlank()) {
                    activeOnResult?.invoke(text)
                }
                _isRestarting.value = false
                _sessionState.value = VoiceSessionState.IDLE
                refreshBufferedCapture()
            }
        }
        stt.onRestartingChanged = { restarting ->
            _isRestarting.value = restarting && _sessionState.value == VoiceSessionState.LISTENING
        }
    }

    fun startListening(existingText: String = "", onResult: (String) -> Unit) {
        viewModelScope.launch {
            recreateSttEngineIfNeeded()
            if (!stt.isReadyForSession()) {
                Log.d(TAG, "startListening ignored — STT session still finishing")
                return@launch
            }
            activeOnResult = onResult
            baseText = existingText.trim()
            transcriptDraft = baseText
            _liveTranscript.value = transcriptDraft
            _isRestarting.value = false
            pendingStopIntent = StopIntent.NONE
            _sessionState.value = VoiceSessionState.LISTENING
            syncWhisperCaptureFlag()
            refreshBufferedCapture()
            stt.startListening(baseText)
        }
    }

    /**
     * Stops capture (waiting for Whisper if needed), delivers transcript, then runs [action].
     * Pass [mergeBaseText] when the user edited the composer while paused.
     */
    fun commitVoiceThen(mergeBaseText: String = "", action: () -> Unit) {
        val merge = mergeBaseText.trim().ifBlank { baseText }
        when (_sessionState.value) {
            VoiceSessionState.LISTENING -> {
                if (usingWhisperEngine) {
                    if (merge.isNotBlank()) {
                        baseText = merge
                        stt.updateCommitBase(merge)
                    }
                    pendingAfterVoiceCommit = action
                    stopListeningAndCommit()
                } else {
                    finalizeGoogleListening(merge)
                    action()
                }
            }
            VoiceSessionState.PAUSED,
            VoiceSessionState.TRANSCRIBING,
            -> {
                if (merge.isNotBlank()) {
                    baseText = merge
                    stt.updateCommitBase(merge)
                }
                pendingAfterVoiceCommit = action
                stopListeningAndCommit()
            }
            else -> action()
        }
    }

    fun stopListeningAndCommit() {
        if (_sessionState.value != VoiceSessionState.LISTENING &&
            _sessionState.value != VoiceSessionState.PAUSED &&
            _sessionState.value != VoiceSessionState.TRANSCRIBING
        ) {
            return
        }
        pendingStopIntent = StopIntent.COMMIT
        if (_sessionState.value != VoiceSessionState.TRANSCRIBING) {
            _sessionState.value = VoiceSessionState.TRANSCRIBING
        }
        _liveTranscript.value = transcriptDraft
        stt.stopListening()
    }

    fun speakResponse(
        text: String,
        thenListen: Boolean = false,
        onListenResult: ((String) -> Unit)? = null,
        onDone: (() -> Unit)? = null,
    ) {
        tts.stop()
        if (_sessionState.value == VoiceSessionState.LISTENING ||
            _sessionState.value == VoiceSessionState.PAUSED
        ) {
            stt.discardCapture()
            _liveTranscript.value = ""
            transcriptDraft = ""
            baseText = ""
        }
        _isRestarting.value = false
        _sessionState.value = VoiceSessionState.SPEAKING
        refreshBufferedCapture()
        tts.speak(text) {
            if (_sessionState.value == VoiceSessionState.SPEAKING) {
                if (thenListen && onListenResult != null) {
                    startListening(existingText = "", onResult = onListenResult)
                } else {
                    _sessionState.value = VoiceSessionState.IDLE
                    refreshBufferedCapture()
                }
            }
            onDone?.invoke()
        }
    }

    /**
     * Mic tap while active, or user focused the composer during capture.
     * Whisper: pause buffered capture. Google: stop STT and flush text into the composer.
     */
    fun finalizeListeningForEdit(mergeInput: String = "") {
        when (_sessionState.value) {
            VoiceSessionState.LISTENING -> {
                if (usingWhisperEngine) {
                    pauseListening()
                } else {
                    finalizeGoogleListening(mergeInput)
                }
            }
            else -> Unit
        }
    }

    /** Mic while paused (Whisper only). */
    fun handleComposerMicTap(currentInput: String) {
        when (_sessionState.value) {
            VoiceSessionState.LISTENING -> finalizeListeningForEdit(currentInput)
            VoiceSessionState.PAUSED -> {
                if (usingWhisperEngine) resumeListening(currentInput)
            }
            else -> Unit
        }
    }

    /** Pause capture without transcribing or updating the text field (Whisper). */
    fun pauseListening() {
        if (_sessionState.value != VoiceSessionState.LISTENING || !usingWhisperEngine) return
        _isRestarting.value = false
        pendingStopIntent = StopIntent.NONE
        stt.pauseCapture()
        _sessionState.value = VoiceSessionState.PAUSED
        _liveTranscript.value = ""
        refreshBufferedCapture()
    }

    private fun finalizeGoogleListening(mergeInput: String = "") {
        if (usingWhisperEngine || _sessionState.value != VoiceSessionState.LISTENING) return
        val text = transcriptDraft.trim().ifBlank { mergeInput.trim() }
        pendingStopIntent = StopIntent.NONE
        pendingAfterVoiceCommit = null
        _isRestarting.value = false
        stt.pauseCapture()
        stt.discardCapture()
        transcriptDraft = ""
        baseText = ""
        _liveTranscript.value = ""
        _sessionState.value = VoiceSessionState.IDLE
        refreshBufferedCapture()
        if (text.isNotBlank()) activeOnResult?.invoke(text)
    }

    fun resumeListening(currentInputText: String = "") {
        if (_sessionState.value != VoiceSessionState.PAUSED) return
        viewModelScope.launch {
            recreateSttEngineIfNeeded()
            if (!stt.isReadyForSession()) {
                Log.d(TAG, "resumeListening ignored — STT session still finishing")
                return@launch
            }
            val merge = currentInputText.trim()
            if (merge.isNotBlank()) {
                baseText = merge
                stt.updateCommitBase(merge)
            }
            transcriptDraft = baseText
            _liveTranscript.value = transcriptDraft
            _isRestarting.value = false
            pendingStopIntent = StopIntent.NONE
            _sessionState.value = VoiceSessionState.LISTENING
            refreshBufferedCapture()
            stt.resumeCapture()
        }
    }

    /** Drop buffered audio/text without STT or callbacks. */
    fun discardRecording() {
        pendingStopIntent = StopIntent.NONE
        pendingAfterVoiceCommit = null
        stt.discardCapture()
        baseText = ""
        transcriptDraft = ""
        _liveTranscript.value = ""
        _isRestarting.value = false
        _sessionState.value = VoiceSessionState.IDLE
        refreshBufferedCapture()
    }

    fun stopSession() {
        tts.stop()
        pendingStopIntent = StopIntent.NONE
        pendingAfterVoiceCommit = null
        stt.discardCapture()
        stt.destroy()
        stt = VoiceRuntime.createGoogleOnlySttEngine(getApplication())
        usingWhisperEngine = false
        syncWhisperCaptureFlag()
        wireSttCallbacks()
        activeOnResult = null
        baseText = ""
        transcriptDraft = ""
        _liveTranscript.value = ""
        _isRestarting.value = false
        _sessionState.value = VoiceSessionState.IDLE
        refreshBufferedCapture()
    }

    /** Call after mic engine preference changes so the next session uses the right STT. */
    fun applyMicEnginePreferenceFromSettings() {
        viewModelScope.launch {
            if (_sessionState.value == VoiceSessionState.LISTENING ||
                _sessionState.value == VoiceSessionState.PAUSED ||
                _sessionState.value == VoiceSessionState.TRANSCRIBING
            ) {
                stopSession()
            } else {
                recreateSttEngineIfNeeded()
            }
        }
    }

    private suspend fun recreateSttEngineIfNeeded() {
        val prefs = getApplication<Application>().settingsDataStore.data.first()
        val useWhisper = prefs[SettingsKeys.MIC_USE_WHISPER_API] ?: SettingsDefaults.MIC_USE_WHISPER_API
        val hasOpenAiKey = !getEncryptedPrefs(getApplication())
            .getString(ApiKeyNames.OPENAI, null)
            .isNullOrBlank()
        val wantWhisper = useWhisper && hasOpenAiKey
        if (wantWhisper == usingWhisperEngine) return
        stt.discardCapture()
        stt.destroy()
        stt = VoiceRuntime.createChatSttEngine(getApplication(), useWhisper = wantWhisper)
        usingWhisperEngine = wantWhisper
        syncWhisperCaptureFlag()
        wireSttCallbacks()
    }

    private fun syncWhisperCaptureFlag() {
        _usesWhisperCapture.value = usingWhisperEngine
    }

    private fun deliverAndReset(captured: String) {
        val text = captured.trim()
        baseText = ""
        transcriptDraft = ""
        _liveTranscript.value = ""
        _isRestarting.value = false
        _sessionState.value = VoiceSessionState.IDLE
        refreshBufferedCapture()
        val callback = activeOnResult
        val afterCommit = pendingAfterVoiceCommit
        activeOnResult = null
        pendingAfterVoiceCommit = null
        if (text.isNotBlank()) callback?.invoke(text)
        afterCommit?.invoke()
    }

    private fun refreshBufferedCapture() {
        _hasBufferedCapture.value =
            _sessionState.value == VoiceSessionState.PAUSED && stt.hasBufferedCapture()
    }

    override fun onCleared() {
        super.onCleared()
        pendingStopIntent = StopIntent.NONE
        stt.destroy()
        tts.destroy()
    }

    private fun handleStopIntent() {
        when (pendingStopIntent) {
            StopIntent.COMMIT -> {
                pendingStopIntent = StopIntent.NONE
                deliverAndReset(transcriptDraft)
            }
            StopIntent.NONE -> Unit
        }
    }

    private enum class StopIntent { NONE, COMMIT }
}

/**
 * Lightweight Google-only STT session for web search fields.
 */
class WebSearchSttSession(context: android.content.Context) {
    private val engine = VoiceRuntime.createGoogleOnlySttEngine(context)
    var onPartialResult: ((String) -> Unit)? = null
    var onError: ((Int) -> Unit)? = null

    private var commitCallback: ((String) -> Unit)? = null

    init {
        engine.onPartialResult = { onPartialResult?.invoke(it) }
        engine.onFinalResult = { text ->
            commitCallback?.invoke(text.trim())
            commitCallback = null
        }
        engine.onError = { code ->
            onError?.invoke(code)
            commitCallback = null
        }
    }

    fun startListening(baseText: String = "") {
        commitCallback = null
        engine.startListening(baseText)
    }

    fun stopListeningAndCommit(onCommitted: (String) -> Unit) {
        commitCallback = onCommitted
        engine.stopListening()
    }

    fun cancelListening() {
        commitCallback = null
        engine.discardCapture()
    }

    fun destroy() {
        commitCallback = null
        engine.destroy()
    }
}
