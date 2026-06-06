package com.example.optimalx.voice

internal object NoteReadAloudSessionBridge {
    @Volatile
    private var controls: Controls? = null

    fun register(controls: Controls) {
        this.controls = controls
    }

    fun unregister(controls: Controls) {
        if (this.controls === controls) {
            this.controls = null
        }
    }

    fun controlsOrNull(): Controls? = controls
}

internal interface Controls {
    fun onToggle()
    fun onRewind10()
    fun onForward10()
    fun onStop()
}
