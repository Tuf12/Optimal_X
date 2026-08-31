package com.example.optimalx.voice

import android.content.Context

enum class ChatSttBackend {
    GOOGLE,
    WHISPER,
    LOCAL_GEMMA,
}

object VoiceRuntime {
    fun createGoogleOnlySttEngine(context: Context): SttEngine =
        GoogleSpeechToTextEngine(context)

    fun createChatSttEngine(context: Context, backend: ChatSttBackend): SttEngine =
        when (backend) {
            ChatSttBackend.GOOGLE -> GoogleSpeechToTextEngine(context)
            ChatSttBackend.WHISPER -> WhisperApiSpeechToTextEngine(context)
            ChatSttBackend.LOCAL_GEMMA -> GemmaLocalScribeEngine(context)
        }
}
