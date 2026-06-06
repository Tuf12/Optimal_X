package com.example.optimalx.voice

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent

class NoteReadAloudReceiver : BroadcastReceiver() {

    override fun onReceive(context: Context, intent: Intent?) {
        val controls = NoteReadAloudSessionBridge.controlsOrNull() ?: run {
            NoteReadAloudNotification.hide(context.applicationContext)
            return
        }
        when (intent?.action) {
            ACTION_TOGGLE -> controls.onToggle()
            ACTION_REWIND_10 -> controls.onRewind10()
            ACTION_FORWARD_10 -> controls.onForward10()
            ACTION_STOP -> controls.onStop()
        }
    }

    companion object {
        const val ACTION_TOGGLE = "com.example.optimalx.voice.NOTE_READ_ALOUD_TOGGLE"
        const val ACTION_REWIND_10 = "com.example.optimalx.voice.NOTE_READ_ALOUD_REWIND_10"
        const val ACTION_FORWARD_10 = "com.example.optimalx.voice.NOTE_READ_ALOUD_FORWARD_10"
        const val ACTION_STOP = "com.example.optimalx.voice.NOTE_READ_ALOUD_STOP"
    }
}
