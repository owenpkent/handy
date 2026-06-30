package com.handyvoice.app.voice

import kotlinx.coroutines.flow.StateFlow

enum class VoiceEngineType {
    ANDROID_STT,
    DEEPGRAM
}

interface VoiceEngine {
    val listeningState: StateFlow<Boolean>
    val partialResult: StateFlow<String>
    val finalResult: StateFlow<String>
    val error: StateFlow<String?>

    fun initialize()
    fun startListening(continuous: Boolean = true)
    fun stopListening()
    fun destroy()
}
