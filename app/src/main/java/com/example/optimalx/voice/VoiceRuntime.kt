package com.example.optimalx.voice

import android.content.Context

object VoiceRuntime {
    fun createGoogleOnlySttEngine(context: Context): SttEngine =
        GoogleSpeechToTextEngine(context)

    fun createChatSttEngine(context: Context, useWhisper: Boolean): SttEngine =
        if (useWhisper) {
            WhisperApiSpeechToTextEngine(context)
        } else {
            GoogleSpeechToTextEngine(context)
        }
}
