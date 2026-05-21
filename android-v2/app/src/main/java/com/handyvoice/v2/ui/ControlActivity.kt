package com.handyvoice.v2.ui

import android.Manifest
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Bundle
import android.view.View
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import androidx.lifecycle.lifecycleScope
import com.google.android.material.button.MaterialButton
import com.handyvoice.v2.R
import com.handyvoice.v2.api.ConnectionState
import com.handyvoice.v2.api.HandyV2ApiClient
import com.handyvoice.v2.audio.AudioCapture
import com.handyvoice.v2.audio.Vad
import com.handyvoice.v2.data.PreferencesManager
import com.handyvoice.v2.dispatch.Dispatch
import com.handyvoice.v2.voice.MfccDtwEngine
import com.handyvoice.v2.voice.TemplateEngine
import com.handyvoice.v2.voice.TemplateStore
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * Main control screen.
 *
 * Two listening modes, picked in Settings:
 *   - "button"    (default) — tap mic, capture 1.5 s, match, dispatch.
 *   - "always_on"           — continuous capture; VAD opens a 1.5 s window
 *                             when speech is detected, matcher scores it,
 *                             dispatch fires on a confident match.
 *
 * On first run (no saved templates), we punt to TrainingActivity so the
 * user records each command before the engine is usable.
 */
class ControlActivity : AppCompatActivity() {

    companion object {
        private const val WINDOW_SECONDS = 1.5
        private const val VAD_BLOCK_MS = 30
        private const val VAD_SPEECH_FRAMES_TO_TRIGGER = 4   // ~120 ms of voiced audio
        private const val COOLDOWN_MS = 1500L
    }

    private lateinit var prefs: PreferencesManager
    private lateinit var store: TemplateStore
    private lateinit var engine: MfccDtwEngine
    private lateinit var capture: AudioCapture
    private lateinit var vad: Vad
    private val api = HandyV2ApiClient()

    // Views
    private lateinit var setupPanel: LinearLayout
    private lateinit var controlPanel: LinearLayout
    private lateinit var connectionKeyInput: EditText
    private lateinit var connectButton: MaterialButton
    private lateinit var settingsButton: MaterialButton
    private lateinit var statusText: TextView
    private lateinit var statusDot: View
    private lateinit var voiceModeLabel: TextView
    private lateinit var micButton: MaterialButton
    private lateinit var lastMatchText: TextView
    private lateinit var startButton: MaterialButton
    private lateinit var stopButton: MaterialButton
    private lateinit var slowerButton: MaterialButton
    private lateinit var fasterButton: MaterialButton
    private lateinit var debugToggleButton: MaterialButton
    private lateinit var debugPanel: View
    private lateinit var debugLogText: TextView
    private lateinit var debugCopyButton: MaterialButton
    private lateinit var debugClearButton: MaterialButton

    private val log: MutableList<String> = mutableListOf()
    private val timestamp = SimpleDateFormat("HH:mm:ss", Locale.US)

    private var alwaysOnJob: Job? = null
    private var isCapturing = false
    private var globalMaxSpeed: Int = PreferencesManager.DEFAULT_GLOBAL_MAX_SPEED
    private var globalMinStroke: Int = PreferencesManager.DEFAULT_GLOBAL_MIN_STROKE
    private var globalMaxStroke: Int = PreferencesManager.DEFAULT_GLOBAL_MAX_STROKE
    private var threshold: Float = PreferencesManager.DEFAULT_THRESHOLD
    private var listeningMode: String = PreferencesManager.MODE_BUTTON

    private val requestMic = registerForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
        if (granted) {
            applyListeningMode()
        } else {
            toast("Microphone permission is required.")
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_control)

        prefs = PreferencesManager(applicationContext)
        store = TemplateStore(applicationContext.filesDir)
        engine = MfccDtwEngine()
        capture = AudioCapture()
        vad = Vad()

        bindViews()
        observeApi()
        bootstrap()
    }

    private fun bindViews() {
        setupPanel = findViewById(R.id.setupPanel)
        controlPanel = findViewById(R.id.controlPanel)
        connectionKeyInput = findViewById(R.id.connectionKeyInput)
        connectButton = findViewById(R.id.connectButton)
        settingsButton = findViewById(R.id.settingsButton)
        statusText = findViewById(R.id.statusText)
        statusDot = findViewById(R.id.statusDot)
        voiceModeLabel = findViewById(R.id.voiceModeLabel)
        micButton = findViewById(R.id.micButton)
        lastMatchText = findViewById(R.id.lastMatchText)
        startButton = findViewById(R.id.startButton)
        stopButton = findViewById(R.id.stopButton)
        slowerButton = findViewById(R.id.slowerButton)
        fasterButton = findViewById(R.id.fasterButton)
        debugToggleButton = findViewById(R.id.debugToggleButton)
        debugPanel = findViewById(R.id.debugPanel)
        debugLogText = findViewById(R.id.debugLogText)
        debugCopyButton = findViewById(R.id.debugCopyButton)
        debugClearButton = findViewById(R.id.debugClearButton)

        connectButton.setOnClickListener { onConnectTapped() }
        settingsButton.setOnClickListener { v -> showMainMenu(v) }
        micButton.setOnClickListener { onMicTapped() }
        startButton.setOnClickListener { lifecycleScope.launch { api.start() } }
        stopButton.setOnClickListener { lifecycleScope.launch { api.stop() } }
        slowerButton.setOnClickListener {
            lifecycleScope.launch { adjustSpeedRespectingLimits(-Dispatch.DEFAULT_STEP) }
        }
        fasterButton.setOnClickListener {
            lifecycleScope.launch { adjustSpeedRespectingLimits(Dispatch.DEFAULT_STEP) }
        }

        debugToggleButton.setOnClickListener {
            val show = debugPanel.visibility != View.VISIBLE
            debugPanel.visibility = if (show) View.VISIBLE else View.GONE
            debugToggleButton.text = if (show) "Hide Debug" else "Show Debug"
        }
        debugCopyButton.setOnClickListener {
            val cm = getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
            cm.setPrimaryClip(ClipData.newPlainText("handy v2 debug", log.joinToString("\n")))
            toast("Copied")
        }
        debugClearButton.setOnClickListener {
            log.clear()
            debugLogText.text = ""
            api.clearLog()
        }
    }

    private fun observeApi() {
        lifecycleScope.launch {
            api.connectionState.collectLatest { state ->
                when (state) {
                    ConnectionState.CONNECTED -> {
                        setupPanel.visibility = View.GONE
                        controlPanel.visibility = View.VISIBLE
                        statusText.setText(R.string.status_connected)
                        applyListeningMode()
                    }
                    ConnectionState.CONNECTING -> {
                        statusText.text = "Connecting..."
                    }
                    ConnectionState.DISCONNECTED -> {
                        controlPanel.visibility = View.GONE
                        setupPanel.visibility = View.VISIBLE
                        statusText.setText(R.string.status_disconnected)
                        stopAlwaysOn()
                    }
                }
            }
        }
        lifecycleScope.launch {
            api.debugLog.collectLatest { lines ->
                // The API client maintains its own ring buffer; just mirror it.
                val merged = (lines + log).distinct().take(80)
                debugLogText.text = merged.joinToString("\n")
            }
        }
    }

    private suspend fun adjustSpeedRespectingLimits(designDelta: Int) {
        val scaledDelta = designDelta * globalMaxSpeed.coerceIn(1, 100) / 100
        val target = (api.currentSpeed.value + scaledDelta).coerceIn(0, 100)
        api.setSpeed(target)
    }

    private fun bootstrap() {
        lifecycleScope.launch {
            val snap = prefs.snapshot()
            globalMaxSpeed = snap.globalMaxSpeed
            globalMinStroke = snap.globalMinStroke
            globalMaxStroke = snap.globalMaxStroke
            threshold = snap.matchThreshold
            listeningMode = snap.listeningMode

            // Onboarding gate: only force training on first run. After the user
            // has been through (or deliberately bailed out of) onboarding, trust
            // the flag — they can re-record from Settings whenever they want.
            if (!snap.onboarded) {
                appLog("First run; launching onboarding.")
                startActivity(Intent(this@ControlActivity, TrainingActivity::class.java))
                return@launch
            }

            // Load templates into the matcher (may be empty if user bailed onboarding).
            val loaded = withContext(Dispatchers.IO) { store.loadAll() }
            engine.loadInto(loaded)
            appLog("Engine ready (${loaded.size} labels: ${loaded.keys.joinToString(",")})")

            // Restore connection key + try to connect.
            connectionKeyInput.setText(snap.connectionKey)
            if (snap.connectionKey.isNotBlank()) {
                api.setConnectionKey(snap.connectionKey)
                api.connect()
            }
        }
    }

    override fun onResume() {
        super.onResume()
        // Re-load prefs in case the user changed listening mode or threshold.
        lifecycleScope.launch {
            val snap = prefs.snapshot()
            val modeChanged = listeningMode != snap.listeningMode
            globalMaxSpeed = snap.globalMaxSpeed
            globalMinStroke = snap.globalMinStroke
            globalMaxStroke = snap.globalMaxStroke
            threshold = snap.matchThreshold
            listeningMode = snap.listeningMode
            if (modeChanged && api.connectionState.value == ConnectionState.CONNECTED) {
                applyListeningMode()
            }
        }
    }

    override fun onPause() {
        super.onPause()
        stopAlwaysOn()
    }

    private fun onConnectTapped() {
        val key = connectionKeyInput.text.toString().trim()
        if (key.isBlank()) {
            toast("Connection key required.")
            return
        }
        lifecycleScope.launch {
            prefs.setConnectionKey(key)
            api.setConnectionKey(key)
            api.connect()
        }
    }

    private fun ensureMicThenRun(block: () -> Unit) {
        val granted = ContextCompat.checkSelfPermission(this, Manifest.permission.RECORD_AUDIO) ==
            PackageManager.PERMISSION_GRANTED
        if (granted) block() else requestMic.launch(Manifest.permission.RECORD_AUDIO)
    }

    private fun applyListeningMode() {
        stopAlwaysOn()
        when (listeningMode) {
            PreferencesManager.MODE_ALWAYS_ON -> {
                voiceModeLabel.text = "Always listening"
                micButton.text = "ON"
                ensureMicThenRun { startAlwaysOn() }
            }
            else -> {
                voiceModeLabel.text = "Tap mic to listen"
                micButton.text = "MIC"
            }
        }
    }

    private fun onMicTapped() {
        if (listeningMode == PreferencesManager.MODE_ALWAYS_ON) {
            // In always-on mode the mic button is a status indicator only.
            return
        }
        if (isCapturing) return
        ensureMicThenRun { lifecycleScope.launch { captureOnce() } }
    }

    private suspend fun captureOnce() {
        isCapturing = true
        micButton.text = "..."
        try {
            val audio = withContext(Dispatchers.IO) {
                runCatching { capture.readWindow(WINDOW_SECONDS) }.getOrNull()
            } ?: return
            handleAudio(audio)
        } finally {
            isCapturing = false
            micButton.text = "MIC"
        }
    }

    private suspend fun handleAudio(audio: ShortArray) {
        val peak = vad.peak(audio)
        appLog("Captured peak=$peak")
        val match = withContext(Dispatchers.Default) { engine.match(audio) }
        if (match == null) {
            lastMatchText.text = "No templates loaded"
            return
        }
        val passes = match.score <= threshold
        val line = "Heard: ${match.label}  score=${"%.2f".format(match.score)} margin=${"%.2f".format(match.margin)}"
        appLog(if (passes) line else "$line  (below threshold)")
        lastMatchText.text = if (passes) "✓ ${match.label}" else "? ${match.label} (score ${"%.1f".format(match.score)})"
        if (passes) {
            runCatching {
                Dispatch.dispatch(
                    label = match.label,
                    api = api,
                    globalMaxSpeed = globalMaxSpeed,
                    globalMinStroke = globalMinStroke,
                    globalMaxStroke = globalMaxStroke,
                )
            }.onFailure { appLog("Dispatch error: ${it.message}") }
        }
    }

    private fun startAlwaysOn() {
        if (alwaysOnJob?.isActive == true) return
        appLog("Always-on listening started.")
        alwaysOnJob = lifecycleScope.launch(Dispatchers.IO) {
            val blockSamples = AudioCapture.SAMPLE_RATE * VAD_BLOCK_MS / 1000
            val windowSamples = (AudioCapture.SAMPLE_RATE * WINDOW_SECONDS).toInt()
            val buffer = ShortArray(windowSamples)
            var written = 0
            var voicedRun = 0
            var lastFireAt = 0L

            capture.stream(blockSamples, shouldContinue = { alwaysOnJob?.isActive == true }) { block ->
                // Sliding-window: copy block into a circular buffer; only score
                // once we've seen enough consecutive voiced blocks.
                val n = block.size
                if (n >= windowSamples) {
                    System.arraycopy(block, n - windowSamples, buffer, 0, windowSamples)
                    written = windowSamples
                } else {
                    if (written + n > windowSamples) {
                        val shift = written + n - windowSamples
                        System.arraycopy(buffer, shift, buffer, 0, windowSamples - shift)
                        written = windowSamples - n
                    }
                    System.arraycopy(block, 0, buffer, written, n)
                    written += n
                }

                if (vad.isSpeech(block)) voicedRun++ else voicedRun = 0

                val now = System.currentTimeMillis()
                if (voicedRun >= VAD_SPEECH_FRAMES_TO_TRIGGER && written >= windowSamples &&
                    now - lastFireAt >= COOLDOWN_MS) {
                    voicedRun = 0
                    lastFireAt = now
                    val snapshot = buffer.copyOf()
                    lifecycleScope.launch { handleAudio(snapshot) }
                }
            }
        }
    }

    private fun stopAlwaysOn() {
        alwaysOnJob?.let {
            if (it.isActive) {
                it.cancel()
                appLog("Always-on stopped.")
            }
        }
        alwaysOnJob = null
    }

    private fun appLog(message: String) {
        val line = "[${timestamp.format(Date())}] $message"
        log.add(0, line)
        if (log.size > 100) log.removeLast()
        debugLogText.text = log.joinToString("\n")
    }

    private fun toast(s: String) = Toast.makeText(this, s, Toast.LENGTH_SHORT).show()
}
