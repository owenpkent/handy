package com.handyvoice.app.voice

import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.speech.RecognitionListener
import android.speech.RecognizerIntent
import android.speech.SpeechRecognizer
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

class VoiceRecognitionService(private val context: Context) : VoiceEngine {
    
    private var speechRecognizer: SpeechRecognizer? = null
    private var isListening = false
    
    private val _listeningState = MutableStateFlow(false)
    override val listeningState: StateFlow<Boolean> = _listeningState.asStateFlow()
    
    private val _partialResult = MutableStateFlow("")
    override val partialResult: StateFlow<String> = _partialResult.asStateFlow()
    
    private val _finalResult = MutableStateFlow("")
    override val finalResult: StateFlow<String> = _finalResult.asStateFlow()
    
    private val _recognizedCommand = MutableStateFlow<VoiceCommand>(VoiceCommand.Unknown)
    val recognizedCommand: StateFlow<VoiceCommand> = _recognizedCommand.asStateFlow()
    
    private val _error = MutableStateFlow<String?>(null)
    override val error: StateFlow<String?> = _error.asStateFlow()
    
    private var continuousListening = false
    
    override fun initialize() {
        if (!SpeechRecognizer.isRecognitionAvailable(context)) {
            _error.value = "Speech recognition not available on this device"
            return
        }
        
        speechRecognizer = SpeechRecognizer.createSpeechRecognizer(context).apply {
            setRecognitionListener(createRecognitionListener())
        }
    }
    
    override fun startListening(continuous: Boolean) {
        if (speechRecognizer == null) {
            initialize()
        }
        
        continuousListening = continuous
        isListening = true
        _listeningState.value = true
        _error.value = null
        
        val intent = Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH).apply {
            putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
            putExtra(RecognizerIntent.EXTRA_PARTIAL_RESULTS, true)
            putExtra(RecognizerIntent.EXTRA_MAX_RESULTS, 1)
            // Keep listening longer for accessibility
            putExtra(RecognizerIntent.EXTRA_SPEECH_INPUT_MINIMUM_LENGTH_MILLIS, 5000L)
            putExtra(RecognizerIntent.EXTRA_SPEECH_INPUT_COMPLETE_SILENCE_LENGTH_MILLIS, 2000L)
        }
        
        speechRecognizer?.startListening(intent)
    }
    
    override fun stopListening() {
        continuousListening = false
        isListening = false
        _listeningState.value = false
        speechRecognizer?.stopListening()
    }
    
    override fun destroy() {
        stopListening()
        speechRecognizer?.destroy()
        speechRecognizer = null
    }
    
    private fun createRecognitionListener() = object : RecognitionListener {
        
        override fun onReadyForSpeech(params: Bundle?) {
            _listeningState.value = true
        }
        
        override fun onBeginningOfSpeech() {
            _partialResult.value = ""
        }
        
        override fun onRmsChanged(rmsdB: Float) {
            // Could be used for visual feedback of audio level
        }
        
        override fun onBufferReceived(buffer: ByteArray?) {}
        
        override fun onEndOfSpeech() {
            // Speech ended, wait for results
        }
        
        override fun onError(errorCode: Int) {
            val errorMessage = when (errorCode) {
                SpeechRecognizer.ERROR_AUDIO -> "Audio recording error"
                SpeechRecognizer.ERROR_CLIENT -> "Client error"
                SpeechRecognizer.ERROR_INSUFFICIENT_PERMISSIONS -> "Insufficient permissions"
                SpeechRecognizer.ERROR_NETWORK -> "Network error"
                SpeechRecognizer.ERROR_NETWORK_TIMEOUT -> "Network timeout"
                SpeechRecognizer.ERROR_NO_MATCH -> "No speech match"
                SpeechRecognizer.ERROR_RECOGNIZER_BUSY -> "Recognizer busy"
                SpeechRecognizer.ERROR_SERVER -> "Server error"
                SpeechRecognizer.ERROR_SPEECH_TIMEOUT -> "No speech input"
                else -> "Unknown error"
            }
            
            // Don't report timeout as error if in continuous mode - just restart
            if (errorCode == SpeechRecognizer.ERROR_NO_MATCH || 
                errorCode == SpeechRecognizer.ERROR_SPEECH_TIMEOUT) {
                if (continuousListening) {
                    restartListening()
                    return
                }
            }
            
            _error.value = errorMessage
            
            // Restart if continuous mode
            if (continuousListening) {
                restartListening()
            } else {
                _listeningState.value = false
            }
        }
        
        override fun onResults(results: Bundle?) {
            val matches = results?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)
            val transcript = matches?.firstOrNull() ?: ""
            
            if (transcript.isNotBlank()) {
                _finalResult.value = transcript
                val command = VoiceCommandParser.parse(transcript)
                _recognizedCommand.value = command
            }
            
            // Restart listening if continuous mode
            if (continuousListening) {
                restartListening()
            } else {
                _listeningState.value = false
            }
        }
        
        override fun onPartialResults(partialResults: Bundle?) {
            val matches = partialResults?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)
            val partial = matches?.firstOrNull() ?: ""
            _partialResult.value = partial
        }
        
        override fun onEvent(eventType: Int, params: Bundle?) {}
    }
    
    private fun restartListening() {
        if (continuousListening) {
            // Small delay before restarting to avoid rapid restarts
            android.os.Handler(context.mainLooper).postDelayed({
                if (continuousListening) {
                    startListening(true)
                }
            }, 100)
        }
    }
}
