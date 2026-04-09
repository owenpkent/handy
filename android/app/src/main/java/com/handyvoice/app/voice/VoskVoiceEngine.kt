package com.handyvoice.app.voice

import android.content.Context
import android.os.Handler
import android.os.Looper
import android.util.Log
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONObject
import org.vosk.Model
import org.vosk.Recognizer
import org.vosk.android.RecognitionListener
import org.vosk.android.SpeechService
import java.io.File
import java.io.FileOutputStream
import java.util.concurrent.TimeUnit
import java.util.zip.ZipInputStream

class VoskVoiceEngine(
    private val context: Context
) : VoiceEngine {

    companion object {
        private const val TAG = "VoskVoiceEngine"
        private const val SAMPLE_RATE = 16000f
        private const val MODEL_DIR_NAME = "vosk-model"
        private const val MODEL_URL =
            "https://alphacephei.com/vosk/models/vosk-model-small-en-us-0.15.zip"

        // How long to wait before promoting a partial result to a final (ms)
        private const val PARTIAL_TIMEOUT_MS = 1200L

        // Grammar: the ONLY phrases Vosk is allowed to output.
        // [unk] catches anything not in the list (noise, silence, unrecognized speech).
        private val GRAMMAR = listOf(
            // Presets
            "slow top", "slow mid", "slow middle", "slow bot", "slow bottom", "slow full",
            "medium top", "medium mid", "medium middle", "medium bot", "medium bottom", "medium full",
            "med top", "med mid", "med middle", "med bot", "med bottom", "med full",
            "fast top", "fast mid", "fast middle", "fast bot", "fast bottom", "fast full",
            // Basic commands
            "start", "stop", "go", "begin", "play",
            "halt", "pause", "end",
            // Speed adjustment
            "faster", "slower", "speed up", "slow down", "quicker",
            // Speed setting
            "speed ten", "speed twenty", "speed thirty", "speed forty", "speed fifty",
            "speed sixty", "speed seventy", "speed eighty", "speed ninety", "speed hundred",
            // Global min/max
            "maximum ten", "maximum twenty", "maximum thirty", "maximum forty", "maximum fifty",
            "maximum sixty", "maximum seventy", "maximum eighty", "maximum ninety", "maximum hundred",
            "minimum ten", "minimum twenty", "minimum thirty", "minimum forty", "minimum fifty",
            "minimum sixty", "minimum seventy", "minimum eighty", "minimum ninety", "minimum hundred",
            "max ten", "max twenty", "max thirty", "max forty", "max fifty",
            "max sixty", "max seventy", "max eighty", "max ninety", "max hundred",
            "min ten", "min twenty", "min thirty", "min forty", "min fifty",
            "min sixty", "min seventy", "min eighty", "min ninety", "min hundred",
            // Speed limit
            "limit ten", "limit twenty", "limit thirty", "limit forty", "limit fifty",
            "limit sixty", "limit seventy", "limit eighty", "limit ninety", "limit hundred",
            // Unknown catch-all
            "[unk]"
        )

        private val GRAMMAR_JSON: String
            get() {
                val items = GRAMMAR.joinToString(", ") { "\"$it\"" }
                return "[$items]"
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

    private var model: Model? = null
    private var speechService: SpeechService? = null
    private val scope = CoroutineScope(Dispatchers.IO + SupervisorJob())

    // Muted = SpeechService runs but results are ignored (avoids AudioRecord warmup issues)
    @Volatile private var muted = true

    // Partial result timeout: auto-promote partials to finals for repeated commands
    private val handler = Handler(Looper.getMainLooper())
    private var pendingPartial: String? = null
    private var lastEmittedFinal: String? = null
    private val partialTimeoutRunnable = Runnable {
        val partial = pendingPartial
        if (partial != null && partial.isNotBlank() && partial != "[unk]" && !muted) {
            Log.d(TAG, "Partial timeout → promoting '$partial' to final")
            emitFinal(partial)
            pendingPartial = null
        }
    }

    override fun initialize() {
        scope.launch {
            Log.d(TAG, "Pre-loading Vosk model...")
            val m = ensureModel()
            if (m != null) {
                Log.d(TAG, "Vosk model pre-loaded and ready")
                // Pre-start the SpeechService muted so AudioRecord is warmed up
                withContext(Dispatchers.Main) {
                    startSpeechServiceIfNeeded(m)
                }
            }
        }
    }

    override fun startListening(continuous: Boolean) {
        scope.launch {
            val m = ensureModel()
            if (m == null) {
                _error.value = "Voice model not ready. Please wait..."
                return@launch
            }

            withContext(Dispatchers.Main) {
                startSpeechServiceIfNeeded(m)
                // Unmute — start forwarding results
                muted = false
                _listeningState.value = true
                _error.value = null
                Log.d(TAG, "Vosk unmuted — listening active")
            }
        }
    }

    override fun stopListening() {
        // Mute — stop forwarding results, but keep SpeechService running
        muted = true
        handler.removeCallbacks(partialTimeoutRunnable)
        pendingPartial = null
        lastEmittedFinal = null
        _listeningState.value = false
        _partialResult.value = ""
        Log.d(TAG, "Vosk muted — listening paused")
    }

    override fun destroy() {
        muted = true
        handler.removeCallbacks(partialTimeoutRunnable)
        try {
            speechService?.stop()
        } catch (e: Exception) {
            Log.e(TAG, "Error stopping Vosk", e)
        }
        speechService = null
        model?.close()
        model = null
        scope.cancel()
    }

    private fun startSpeechServiceIfNeeded(m: Model) {
        if (speechService != null) return
        try {
            val recognizer = Recognizer(m, SAMPLE_RATE, GRAMMAR_JSON)
            speechService = SpeechService(recognizer, SAMPLE_RATE).apply {
                startListening(createListener())
            }
            Log.d(TAG, "SpeechService created and running (muted=$muted)")
        } catch (e: Exception) {
            Log.e(TAG, "Failed to create SpeechService", e)
            _error.value = "Voice engine error: ${e.message}"
        }
    }

    private fun emitFinal(text: String) {
        lastEmittedFinal = text
        _finalResult.value = text + " " + System.currentTimeMillis()
        _partialResult.value = ""
    }

    private fun createListener() = object : RecognitionListener {

        override fun onPartialResult(hypothesis: String?) {
            if (muted) return
            hypothesis ?: return
            try {
                val json = JSONObject(hypothesis)
                val partial = json.optString("partial", "")
                if (partial.isNotBlank() && partial != "[unk]") {
                    _partialResult.value = partial

                    // Start/reset the partial timeout timer
                    handler.removeCallbacks(partialTimeoutRunnable)
                    pendingPartial = partial
                    handler.postDelayed(partialTimeoutRunnable, PARTIAL_TIMEOUT_MS)
                }
            } catch (e: Exception) {
                Log.e(TAG, "Partial parse error", e)
            }
        }

        override fun onResult(hypothesis: String?) {
            if (muted) return
            hypothesis ?: return
            try {
                val json = JSONObject(hypothesis)
                val text = json.optString("text", "")
                // Cancel pending partial timeout since we got a real result
                handler.removeCallbacks(partialTimeoutRunnable)
                pendingPartial = null
                if (text.isNotBlank() && text != "[unk]") {
                    emitFinal(text)
                    Log.d(TAG, "Vosk result: $text")
                }
            } catch (e: Exception) {
                Log.e(TAG, "Result parse error", e)
            }
        }

        override fun onFinalResult(hypothesis: String?) {
            if (muted) return
            hypothesis ?: return
            try {
                val json = JSONObject(hypothesis)
                val text = json.optString("text", "")
                handler.removeCallbacks(partialTimeoutRunnable)
                pendingPartial = null
                if (text.isNotBlank() && text != "[unk]") {
                    emitFinal(text)
                    Log.d(TAG, "Vosk final: $text")
                }
            } catch (e: Exception) {
                Log.e(TAG, "Final parse error", e)
            }
        }

        override fun onError(exception: Exception?) {
            Log.e(TAG, "Vosk error", exception)
            _error.value = "Voice error: ${exception?.message}"
        }

        override fun onTimeout() {
            // Vosk timed out — restart the service
            Log.d(TAG, "Vosk timeout — restarting SpeechService")
            val wasMuted = muted
            try {
                speechService?.stop()
            } catch (e: Exception) {
                Log.e(TAG, "Error stopping on timeout", e)
            }
            speechService = null
            model?.let { m ->
                startSpeechServiceIfNeeded(m)
                muted = wasMuted
            }
        }
    }

    // --- Model Management ---

    private suspend fun ensureModel(): Model? {
        model?.let { return it }

        val modelDir = File(context.filesDir, MODEL_DIR_NAME)
        if (modelDir.exists() && modelDir.isDirectory && modelDir.listFiles()?.isNotEmpty() == true) {
            return loadModel(modelDir)
        }

        // Download model
        withContext(Dispatchers.Main) {
            _error.value = "Downloading voice model (~40MB)..."
        }

        val success = downloadAndUnzipModel(modelDir)
        if (!success) {
            withContext(Dispatchers.Main) {
                _error.value = "Failed to download voice model. Check internet connection."
            }
            return null
        }

        withContext(Dispatchers.Main) {
            _error.value = null
        }
        return loadModel(modelDir)
    }

    private fun loadModel(modelDir: File): Model? {
        return try {
            // The zip extracts to a subdirectory — find it
            val actualDir = modelDir.listFiles()?.firstOrNull { it.isDirectory } ?: modelDir
            Model(actualDir.absolutePath).also {
                model = it
                Log.d(TAG, "Vosk model loaded from ${actualDir.absolutePath}")
            }
        } catch (e: Exception) {
            Log.e(TAG, "Failed to load Vosk model", e)
            _error.value = "Failed to load voice model: ${e.message}"
            null
        }
    }

    private suspend fun downloadAndUnzipModel(destDir: File): Boolean {
        return withContext(Dispatchers.IO) {
            try {
                val client = OkHttpClient.Builder()
                    .connectTimeout(30, TimeUnit.SECONDS)
                    .readTimeout(5, TimeUnit.MINUTES)
                    .build()

                val request = Request.Builder().url(MODEL_URL).build()
                val response = client.newCall(request).execute()

                if (!response.isSuccessful) {
                    Log.e(TAG, "Model download failed: ${response.code}")
                    return@withContext false
                }

                destDir.mkdirs()

                response.body?.byteStream()?.let { inputStream ->
                    ZipInputStream(inputStream).use { zip ->
                        var entry = zip.nextEntry
                        while (entry != null) {
                            val file = File(destDir, entry.name)
                            if (entry.isDirectory) {
                                file.mkdirs()
                            } else {
                                file.parentFile?.mkdirs()
                                FileOutputStream(file).use { out ->
                                    zip.copyTo(out)
                                }
                            }
                            zip.closeEntry()
                            entry = zip.nextEntry
                        }
                    }
                }

                Log.d(TAG, "Model downloaded and extracted to ${destDir.absolutePath}")
                true
            } catch (e: Exception) {
                Log.e(TAG, "Model download error", e)
                destDir.deleteRecursively()
                false
            }
        }
    }
}
