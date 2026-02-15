package com.handyvoice.app.voice

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.media.AudioFormat
import android.media.AudioRecord
import android.media.MediaRecorder
import android.util.Log
import androidx.core.content.ContextCompat
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import okhttp3.WebSocket
import okhttp3.WebSocketListener
import okio.ByteString
import okio.ByteString.Companion.toByteString
import org.json.JSONObject
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import java.util.concurrent.TimeUnit

class DeepgramVoiceEngine(
    private val context: Context,
    private val apiKey: String
) : VoiceEngine {

    companion object {
        private const val TAG = "DeepgramVoiceEngine"
        private const val SAMPLE_RATE = 16000
        private const val CHANNEL_CONFIG = AudioFormat.CHANNEL_IN_MONO
        private const val AUDIO_FORMAT = AudioFormat.ENCODING_PCM_16BIT
        // Keyword boosting: intensifier values (max 10) tell Deepgram to
        // strongly prefer these words over similar-sounding alternatives.
        // This effectively constrains recognition to our command vocabulary.
        private val KEYWORDS = listOf(
            "slow:10", "medium:10", "med:10", "fast:10",
            "top:10", "mid:10", "middle:10", "bot:10", "bottom:10", "full:10",
            "start:10", "stop:10", "go:10", "begin:5", "play:5",
            "halt:5", "pause:5", "end:5",
            "faster:10", "slower:10", "speed:10",
            "speed up:10", "slow down:10"
        )

        private val DEEPGRAM_WS_URL: String
            get() {
                val keywordsParam = KEYWORDS.joinToString("&keywords=") { 
                    java.net.URLEncoder.encode(it, "UTF-8") 
                }
                return "wss://api.deepgram.com/v1/listen" +
                    "?model=nova-2" +
                    "&language=en-US" +
                    "&punctuate=false" +
                    "&interim_results=true" +
                    "&endpointing=300" +
                    "&vad_events=true" +
                    "&encoding=linear16" +
                    "&sample_rate=16000" +
                    "&channels=1" +
                    "&keywords=$keywordsParam"
            }
    }

    private val _listeningState = MutableStateFlow(false)
    override val listeningState: StateFlow<Boolean> = _listeningState.asStateFlow()

    private val _partialResult = MutableStateFlow("")
    override val partialResult: StateFlow<String> = _partialResult.asStateFlow()

    private val _finalResult = MutableStateFlow("")
    override val finalResult: StateFlow<String> = _finalResult.asStateFlow()

    private val _error = MutableStateFlow<String?>(null)
    override val error: StateFlow<String?> = _error.asStateFlow()

    private var webSocket: WebSocket? = null
    private var audioRecord: AudioRecord? = null
    private var continuousListening = false
    private var recordingJob: Job? = null
    private val scope = CoroutineScope(Dispatchers.IO + SupervisorJob())

    private val okHttpClient = OkHttpClient.Builder()
        .readTimeout(0, TimeUnit.MILLISECONDS)
        .build()

    override fun initialize() {
        if (apiKey.isBlank()) {
            _error.value = "Deepgram API key not set. Go to Settings to enter it."
            return
        }
        if (ContextCompat.checkSelfPermission(context, Manifest.permission.RECORD_AUDIO)
            != PackageManager.PERMISSION_GRANTED
        ) {
            _error.value = "Microphone permission required"
            return
        }
    }

    override fun startListening(continuous: Boolean) {
        if (apiKey.isBlank()) {
            _error.value = "Deepgram API key not set. Go to Settings to enter it."
            return
        }

        continuousListening = continuous
        _error.value = null
        _partialResult.value = ""

        connectWebSocket()
    }

    override fun stopListening() {
        continuousListening = false
        _listeningState.value = false
        stopRecording()
        webSocket?.close(1000, "User stopped listening")
        webSocket = null
    }

    override fun destroy() {
        stopListening()
        scope.cancel()
    }

    private fun connectWebSocket() {
        val request = Request.Builder()
            .url(DEEPGRAM_WS_URL)
            .addHeader("Authorization", "Token $apiKey")
            .build()

        webSocket = okHttpClient.newWebSocket(request, object : WebSocketListener() {

            override fun onOpen(webSocket: WebSocket, response: Response) {
                Log.d(TAG, "WebSocket connected")
                _listeningState.value = true
                _error.value = null
                startRecording(webSocket)
            }

            override fun onMessage(webSocket: WebSocket, text: String) {
                try {
                    val json = JSONObject(text)
                    val channel = json.optJSONObject("channel") ?: return
                    val alternatives = channel.optJSONArray("alternatives") ?: return
                    if (alternatives.length() == 0) return

                    val transcript = alternatives.getJSONObject(0)
                        .optString("transcript", "")

                    if (transcript.isBlank()) return

                    val isFinal = json.optBoolean("is_final", false)

                    if (isFinal) {
                        _finalResult.value = transcript
                        _partialResult.value = ""
                        Log.d(TAG, "Final: $transcript")
                    } else {
                        _partialResult.value = transcript
                        Log.d(TAG, "Partial: $transcript")
                    }
                } catch (e: Exception) {
                    Log.e(TAG, "Error parsing Deepgram response", e)
                }
            }

            override fun onFailure(webSocket: WebSocket, t: Throwable, response: Response?) {
                Log.e(TAG, "WebSocket error: ${t.message}", t)
                _listeningState.value = false
                stopRecording()

                val errorMsg = when {
                    t.message?.contains("401") == true -> "Invalid Deepgram API key"
                    t.message?.contains("timeout") == true -> "Connection timeout"
                    else -> "Deepgram connection error: ${t.message}"
                }
                _error.value = errorMsg

                // Auto-reconnect in continuous mode
                if (continuousListening) {
                    scope.launch {
                        delay(2000)
                        if (continuousListening) {
                            connectWebSocket()
                        }
                    }
                }
            }

            override fun onClosing(webSocket: WebSocket, code: Int, reason: String) {
                Log.d(TAG, "WebSocket closing: $code $reason")
                stopRecording()
                _listeningState.value = false

                // Auto-reconnect in continuous mode
                if (continuousListening) {
                    scope.launch {
                        delay(500)
                        if (continuousListening) {
                            connectWebSocket()
                        }
                    }
                }
            }

            override fun onClosed(webSocket: WebSocket, code: Int, reason: String) {
                Log.d(TAG, "WebSocket closed: $code $reason")
                _listeningState.value = false
            }
        })
    }

    private fun startRecording(ws: WebSocket) {
        val bufferSize = AudioRecord.getMinBufferSize(SAMPLE_RATE, CHANNEL_CONFIG, AUDIO_FORMAT)
            .coerceAtLeast(SAMPLE_RATE) // At least 1 second buffer

        try {
            audioRecord = AudioRecord(
                MediaRecorder.AudioSource.VOICE_RECOGNITION,
                SAMPLE_RATE,
                CHANNEL_CONFIG,
                AUDIO_FORMAT,
                bufferSize
            )

            if (audioRecord?.state != AudioRecord.STATE_INITIALIZED) {
                _error.value = "Failed to initialize audio recorder"
                return
            }

            audioRecord?.startRecording()

            recordingJob = scope.launch {
                val buffer = ByteArray(bufferSize / 2)
                while (isActive && _listeningState.value) {
                    val bytesRead = audioRecord?.read(buffer, 0, buffer.size) ?: -1
                    if (bytesRead > 0) {
                        ws.send(buffer.copyOf(bytesRead).toByteString())
                    }
                }
            }
        } catch (e: SecurityException) {
            _error.value = "Microphone permission required"
        } catch (e: Exception) {
            _error.value = "Audio recording error: ${e.message}"
            Log.e(TAG, "Recording error", e)
        }
    }

    private fun stopRecording() {
        recordingJob?.cancel()
        recordingJob = null
        try {
            audioRecord?.stop()
            audioRecord?.release()
        } catch (e: Exception) {
            Log.e(TAG, "Error stopping recording", e)
        }
        audioRecord = null
    }
}
