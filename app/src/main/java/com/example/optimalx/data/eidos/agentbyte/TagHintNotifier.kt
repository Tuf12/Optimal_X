package com.example.optimalx.data.eidos.agentbyte

interface TagHintNotifier {
    fun notify(title: String, message: String, ref: String?)

    object NoOp : TagHintNotifier {
        override fun notify(title: String, message: String, ref: String?) = Unit
    }
}
