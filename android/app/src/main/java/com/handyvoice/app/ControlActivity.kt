package com.handyvoice.app

import android.Manifest
import android.content.Intent
import android.content.SharedPreferences
import android.content.pm.PackageManager
import android.os.Bundle
import android.text.Editable
import android.text.TextWatcher
import android.view.View
import android.widget.SeekBar
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import androidx.lifecycle.lifecycleScope
import com.handyvoice.app.api.ConnectionState
import com.handyvoice.app.api.HandyV2ApiClient
import com.handyvoice.app.data.PreferencesManager
import com.handyvoice.app.databinding.ActivityControlBinding
import com.handyvoice.app.voice.DeepgramVoiceEngine
import com.handyvoice.app.voice.VoiceCommand
import com.handyvoice.app.voice.VoiceCommandParser
import com.handyvoice.app.voice.VoiceEngine
import com.handyvoice.app.voice.VoiceRecognitionService
import com.handyvoice.app.voice.VoskVoiceEngine
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch

class ControlActivity : AppCompatActivity() {
    
    private lateinit var binding: ActivityControlBinding
    private lateinit var prefs: SharedPreferences
    private lateinit var prefsManager: PreferencesManager
    private val api = HandyV2ApiClient()
    
    private var speedMultiplier = 100
    private var globalMin = 0
    private var globalMax = 100
    private var currentBaseSpeed = 50
    
    private var feedbackJob: Job? = null
    
    // Voice control
    private var voiceEngine: VoiceEngine? = null
    private var isVoiceActive = false
    
    private val micPermissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { granted ->
        if (granted) {
            toggleVoice()
        } else {
            showFeedback("Microphone permission denied", false)
        }
    }
    
    // Preset values (matching web app)
    private val speedValues = mapOf("slow" to 25, "medium" to 50, "fast" to 80)
    private val positionValues = mapOf(
        "top" to Pair(50, 90),
        "middle" to Pair(30, 70),
        "mid" to Pair(30, 70),
        "bottom" to Pair(10, 50),
        "bot" to Pair(10, 50),
        "full" to Pair(0, 100)
    )
    
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityControlBinding.inflate(layoutInflater)
        setContentView(binding.root)
        
        prefs = getSharedPreferences("handy_prefs", MODE_PRIVATE)
        prefsManager = PreferencesManager(this)
        
        loadSettings()
        setupUI()
        setupMicButton()
        setupDebugPanel()
        observeState()
        
        // Auto-connect if we have a saved key
        val savedKey = prefs.getString("connection_key", "") ?: ""
        if (savedKey.isNotBlank()) {
            binding.connectionKeyInput.setText(savedKey)
        }
    }
    
    private fun loadSettings() {
        speedMultiplier = prefs.getInt("speed_multiplier", 100)
        globalMin = prefs.getInt("global_min", 0)
        globalMax = prefs.getInt("global_max", 100)
        
        binding.speedSlider.progress = speedMultiplier
        binding.speedInput.setText(speedMultiplier.toString())
        binding.globalMinSlider.progress = globalMin
        binding.globalMinInput.setText(globalMin.toString())
        binding.globalMaxSlider.progress = globalMax
        binding.globalMaxInput.setText(globalMax.toString())
    }
    
    private fun saveSettings() {
        prefs.edit()
            .putInt("speed_multiplier", speedMultiplier)
            .putInt("global_min", globalMin)
            .putInt("global_max", globalMax)
            .apply()
    }
    
    private fun setupUI() {
        // Connect button
        binding.connectButton.setOnClickListener {
            val key = binding.connectionKeyInput.text.toString().trim()
            if (key.isBlank()) {
                showFeedback("Enter your connection key", false)
                return@setOnClickListener
            }
            
            api.setConnectionKey(key)
            prefs.edit().putString("connection_key", key).apply()
            
            lifecycleScope.launch {
                binding.connectButton.isEnabled = false
                binding.connectButton.text = "Connecting..."
                val success = api.connect()
                binding.connectButton.isEnabled = true
                binding.connectButton.text = "Connect"
                
                if (success) {
                    showControls()
                }
            }
        }
        
        // Menu button
        binding.menuButton.setOnClickListener {
            startActivity(Intent(this, SettingsActivity::class.java))
        }
        
        // Start/Stop buttons
        binding.startButton.setOnClickListener {
            lifecycleScope.launch {
                if (api.start()) {
                    showFeedback("Started", true)
                }
            }
        }
        
        binding.stopButton.setOnClickListener {
            lifecycleScope.launch {
                if (api.stop()) {
                    showFeedback("Stopped", true)
                }
            }
        }
        
        // Stroke Min slider
        setupSlider(binding.strokeMinSlider, binding.strokeMinInput) { value ->
            val min = value.coerceIn(0, binding.strokeMaxSlider.progress - 1)
            lifecycleScope.launch {
                api.setStrokeRange(min, binding.strokeMaxSlider.progress)
            }
        }
        
        // Stroke Max slider
        setupSlider(binding.strokeMaxSlider, binding.strokeMaxInput) { value ->
            val max = value.coerceIn(binding.strokeMinSlider.progress + 1, globalMax)
            lifecycleScope.launch {
                api.setStrokeRange(binding.strokeMinSlider.progress, max)
            }
        }
        
        // Speed slider
        setupSlider(binding.speedSlider, binding.speedInput) { value ->
            speedMultiplier = value.coerceIn(10, 100)
            saveSettings()
            val scaledSpeed = applySpeedMultiplier(currentBaseSpeed)
            lifecycleScope.launch {
                api.setSpeed(scaledSpeed)
                showFeedback("Speed: $scaledSpeed%", true)
            }
        }
        
        // Global Min slider
        setupSlider(binding.globalMinSlider, binding.globalMinInput) { value ->
            globalMin = value.coerceIn(0, globalMax - 10)
            saveSettings()
            binding.strokeMinSlider.min = globalMin
            val newMin = binding.strokeMinSlider.progress.coerceAtLeast(globalMin)
            binding.strokeMinSlider.progress = newMin
            binding.strokeMinInput.setText(newMin.toString())
            lifecycleScope.launch {
                api.setStrokeRange(newMin, binding.strokeMaxSlider.progress)
                showFeedback("G.Min: $globalMin%", true)
            }
        }
        
        // Global Max slider
        setupSlider(binding.globalMaxSlider, binding.globalMaxInput) { value ->
            globalMax = value.coerceIn(globalMin + 10, 100)
            saveSettings()
            binding.strokeMaxSlider.max = globalMax
            val newMax = binding.strokeMaxSlider.progress.coerceAtMost(globalMax)
            binding.strokeMaxSlider.progress = newMax
            binding.strokeMaxInput.setText(newMax.toString())
            lifecycleScope.launch {
                api.setStrokeRange(binding.strokeMinSlider.progress, newMax)
                showFeedback("G.Max: $globalMax%", true)
            }
        }
        
        // Preset buttons
        setupPresetButton(binding.presetSlowTop, "slow", "top")
        setupPresetButton(binding.presetMedTop, "medium", "top")
        setupPresetButton(binding.presetFastTop, "fast", "top")
        setupPresetButton(binding.presetSlowMid, "slow", "mid")
        setupPresetButton(binding.presetMedMid, "medium", "mid")
        setupPresetButton(binding.presetFastMid, "fast", "mid")
        setupPresetButton(binding.presetSlowBot, "slow", "bot")
        setupPresetButton(binding.presetMedBot, "medium", "bot")
        setupPresetButton(binding.presetFastBot, "fast", "bot")
        setupPresetButton(binding.presetSlowFull, "slow", "full")
        setupPresetButton(binding.presetMedFull, "medium", "full")
        setupPresetButton(binding.presetFastFull, "fast", "full")
    }
    
    private fun setupSlider(slider: SeekBar, input: android.widget.EditText, onChange: (Int) -> Unit) {
        slider.setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(seekBar: SeekBar?, progress: Int, fromUser: Boolean) {
                if (fromUser) {
                    input.setText(progress.toString())
                }
            }
            
            override fun onStartTrackingTouch(seekBar: SeekBar?) {}
            
            override fun onStopTrackingTouch(seekBar: SeekBar?) {
                seekBar?.let { onChange(it.progress) }
            }
        })
        
        input.addTextChangedListener(object : TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) {}
            override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) {}
            override fun afterTextChanged(s: Editable?) {
                val value = s?.toString()?.toIntOrNull() ?: return
                if (slider.progress != value) {
                    slider.progress = value
                    onChange(value)
                }
            }
        })
    }
    
    private fun setupPresetButton(button: com.google.android.material.button.MaterialButton, speed: String, position: String) {
        button.setOnClickListener {
            applyPreset(speed, position)
        }
    }
    
    private fun applyPreset(speed: String, position: String) {
        val speedVal = speedValues[speed]
        val posVal = positionValues[position]
        
        if (speedVal == null) {
            debugLog("Preset", "Unknown speed: '$speed'")
            return
        }
        if (posVal == null) {
            debugLog("Preset", "Unknown position: '$position'")
            return
        }
        
        // Scale preset within global limits
        val globalRange = globalMax - globalMin
        val scaledMin = globalMin + (posVal.first * globalRange / 100)
        val scaledMax = globalMin + (posVal.second * globalRange / 100)
        
        debugLog("Preset", "$speed $position → stroke=$scaledMin-$scaledMax, speed=$speedVal")
        
        binding.strokeMinSlider.progress = scaledMin
        binding.strokeMinInput.setText(scaledMin.toString())
        binding.strokeMaxSlider.progress = scaledMax
        binding.strokeMaxInput.setText(scaledMax.toString())
        
        currentBaseSpeed = speedVal
        val scaledSpeed = applySpeedMultiplier(speedVal)
        
        lifecycleScope.launch {
            debugLog("API", "connState=${api.connectionState.value}")
            // Order must match web app: stroke → start → speed
            val strokeOk = api.setStrokeRange(scaledMin, scaledMax)
            debugLog("API", "setStrokeRange($scaledMin,$scaledMax) → $strokeOk")
            val startOk = api.start()
            debugLog("API", "start() → $startOk")
            // Web app sets speed AFTER starting
            val speedOk = api.setSpeed(scaledSpeed)
            debugLog("API", "setSpeed($scaledSpeed) → $speedOk")
            if (strokeOk && startOk && speedOk) {
                showFeedback("$speed $position - Speed: $scaledSpeed%", true)
            } else {
                debugLog("API", "FAILED: stroke=$strokeOk start=$startOk speed=$speedOk")
            }
        }
    }
    
    private fun applySpeedMultiplier(speed: Int): Int {
        return (speed * speedMultiplier / 100).coerceIn(0, 100)
    }
    
    private fun observeState() {
        lifecycleScope.launch {
            api.connectionState.collect { state ->
                updateConnectionUI(state)
            }
        }
        
        lifecycleScope.launch {
            api.lastError.collect { error ->
                error?.let {
                    debugLog("API-ERR", it)
                    showFeedback(it, false)
                }
            }
        }
    }
    
    private fun updateConnectionUI(state: ConnectionState) {
        when (state) {
            ConnectionState.DISCONNECTED -> {
                binding.statusText.text = "Disconnected"
                binding.statusDot.setBackgroundResource(R.drawable.status_dot)
                binding.statusDot.backgroundTintList = ContextCompat.getColorStateList(this, R.color.status_disconnected)
                showSetup()
            }
            ConnectionState.CONNECTING -> {
                binding.statusText.text = "Connecting..."
                binding.statusDot.backgroundTintList = ContextCompat.getColorStateList(this, R.color.status_listening)
            }
            ConnectionState.CONNECTED -> {
                binding.statusText.text = "Connected"
                binding.statusDot.backgroundTintList = ContextCompat.getColorStateList(this, R.color.status_connected)
                showControls()
            }
        }
    }
    
    private fun showSetup() {
        binding.setupPanel.visibility = View.VISIBLE
        binding.controlPanel.visibility = View.GONE
    }
    
    private fun showControls() {
        binding.setupPanel.visibility = View.GONE
        binding.controlPanel.visibility = View.VISIBLE
    }
    
    private fun showFeedback(message: String, success: Boolean) {
        feedbackJob?.cancel()
        binding.feedbackText.text = message
        binding.feedbackText.setTextColor(
            ContextCompat.getColor(this, if (success) R.color.status_connected else R.color.emergency_red)
        )
        binding.feedbackText.visibility = View.VISIBLE
        
        feedbackJob = lifecycleScope.launch {
            delay(3000)
            binding.feedbackText.visibility = View.GONE
        }
    }
    
    // ---- Debug Panel ----
    
    private fun setupDebugPanel() {
        binding.debugToggleButton.setOnClickListener {
            if (binding.debugPanel.visibility == View.GONE) {
                binding.debugPanel.visibility = View.VISIBLE
                binding.debugToggleButton.text = "Hide Debug"
            } else {
                binding.debugPanel.visibility = View.GONE
                binding.debugToggleButton.text = "Show Debug"
            }
        }
        
        binding.debugClearButton.setOnClickListener {
            binding.debugLogText.text = ""
        }
        
        binding.debugCopyButton.setOnClickListener {
            val clipboard = getSystemService(CLIPBOARD_SERVICE) as android.content.ClipboardManager
            val clip = android.content.ClipData.newPlainText("Debug Log", binding.debugLogText.text)
            clipboard.setPrimaryClip(clip)
            Toast.makeText(this, "Debug log copied to clipboard", Toast.LENGTH_SHORT).show()
        }
        
        // Pipe API client internal log to debug panel
        lifecycleScope.launch {
            api.debugLog.collect { entries ->
                if (entries.isNotEmpty()) {
                    val latest = entries.first()
                    debugLog("Handy", latest)
                }
            }
        }
    }
    
    private fun debugLog(tag: String, message: String) {
        val time = java.text.SimpleDateFormat("HH:mm:ss", java.util.Locale.US)
            .format(java.util.Date())
        val line = "[$time] $tag: $message\n"
        runOnUiThread {
            binding.debugLogText.append(line)
            binding.debugScrollView.post {
                binding.debugScrollView.fullScroll(View.FOCUS_DOWN)
            }
        }
    }
    
    // ---- Voice Control ----
    
    private fun setupMicButton() {
        binding.micButton.setOnClickListener {
            if (ContextCompat.checkSelfPermission(this, Manifest.permission.RECORD_AUDIO)
                != PackageManager.PERMISSION_GRANTED
            ) {
                micPermissionLauncher.launch(Manifest.permission.RECORD_AUDIO)
            } else {
                toggleVoice()
            }
        }
    }
    
    private suspend fun initVoiceEngine() {
        voiceEngine?.destroy()
        
        val enginePref = prefsManager.voiceEngine.first()
        val deepgramKey = prefsManager.deepgramApiKey.first()
        debugLog("Engine", "pref=$enginePref")
        
        voiceEngine = when (enginePref) {
            "deepgram" -> if (deepgramKey.isNotBlank()) {
                binding.voiceEngineLabel.text = "Voice: Deepgram"
                debugLog("Engine", "Using Deepgram")
                DeepgramVoiceEngine(this@ControlActivity, deepgramKey)
            } else {
                binding.voiceEngineLabel.text = "Voice: Vosk (offline)"
                debugLog("Engine", "Deepgram key missing, falling back to Vosk")
                VoskVoiceEngine(this@ControlActivity)
            }
            "android_stt" -> {
                binding.voiceEngineLabel.text = "Voice: Android STT"
                debugLog("Engine", "Using Android STT")
                VoiceRecognitionService(this@ControlActivity)
            }
            else -> {
                binding.voiceEngineLabel.text = "Voice: Vosk (offline)"
                debugLog("Engine", "Using Vosk (offline, constrained)")
                VoskVoiceEngine(this@ControlActivity)
            }
        }
        
        debugLog("Engine", "Initializing...")
        voiceEngine?.initialize()
        debugLog("Engine", "Initialized")
        observeVoiceState()
    }
    
    private fun toggleVoice() {
        val engine = voiceEngine ?: run {
            debugLog("Voice", "No engine available!")
            return
        }
        
        if (isVoiceActive) {
            debugLog("Voice", "Stopping...")
            engine.stopListening()
            isVoiceActive = false
            updateMicUI(false)
        } else {
            debugLog("Voice", "Starting...")
            engine.startListening(true)
            isVoiceActive = true
            updateMicUI(true)
        }
    }
    
    private fun updateMicUI(listening: Boolean) {
        if (listening) {
            binding.micButton.text = "STOP"
            binding.micButton.setStrokeColorResource(R.color.status_connected)
            binding.micButton.setTextColor(ContextCompat.getColor(this, R.color.status_connected))
        } else {
            binding.micButton.text = "MIC"
            binding.micButton.setStrokeColorResource(R.color.text_secondary)
            binding.micButton.setTextColor(ContextCompat.getColor(this, R.color.text_primary))
            binding.partialTranscript.text = ""
            binding.lastCommandText.text = ""
        }
    }
    
    private fun observeVoiceState() {
        val engine = voiceEngine ?: return
        
        lifecycleScope.launch {
            engine.listeningState.collect { listening ->
                debugLog("State", if (listening) "LISTENING" else "STOPPED")
                updateMicUI(listening)
                isVoiceActive = listening
            }
        }
        
        lifecycleScope.launch {
            engine.partialResult.collect { partial ->
                if (partial.isNotBlank()) {
                    debugLog("Partial", partial)
                    binding.partialTranscript.text = partial
                }
            }
        }
        
        lifecycleScope.launch {
            engine.finalResult.collect { rawTranscript ->
                if (rawTranscript.isNotBlank()) {
                    // Strip timestamp suffix appended by VoskVoiceEngine for duplicate detection
                    val transcript = rawTranscript.replace(Regex("\\s+\\d{13,}$"), "")
                    debugLog("Final", "\"$transcript\"")
                    binding.partialTranscript.text = ""
                    val command = VoiceCommandParser.parse(transcript)
                    debugLog("Parsed", "$command")
                    executeCommand(command, transcript)
                }
            }
        }
        
        lifecycleScope.launch {
            engine.error.collect { err ->
                err?.let {
                    debugLog("ERROR", it)
                    showFeedback(it, false)
                }
            }
        }
    }
    
    private fun executeCommand(command: VoiceCommand, transcript: String) {
        val label = when (command) {
            is VoiceCommand.Start -> "Start"
            is VoiceCommand.Stop -> "Stop"
            is VoiceCommand.Faster -> "Faster"
            is VoiceCommand.Slower -> "Slower"
            is VoiceCommand.SetSpeed -> "Speed ${command.percent}%"
            is VoiceCommand.SetStroke -> "Stroke ${command.percent}%"
            is VoiceCommand.Preset -> "${command.speed} ${command.position}"
            is VoiceCommand.SetGlobalMin -> "G.Min ${command.percent}%"
            is VoiceCommand.SetGlobalMax -> "G.Max ${command.percent}%"
            is VoiceCommand.Unknown -> "\"$transcript\""
        }
        binding.lastCommandText.text = label
        
        if (command is VoiceCommand.Unknown) {
            binding.lastCommandText.setTextColor(
                ContextCompat.getColor(this, R.color.text_secondary)
            )
            return
        }
        
        binding.lastCommandText.setTextColor(
            ContextCompat.getColor(this, R.color.status_connected)
        )
        
        lifecycleScope.launch {
            when (command) {
                is VoiceCommand.Start -> {
                    if (api.start()) showFeedback("Started", true)
                }
                is VoiceCommand.Stop -> {
                    if (api.stop()) showFeedback("Stopped", true)
                }
                is VoiceCommand.Faster -> {
                    if (api.adjustSpeed(10)) {
                        showFeedback("Speed: ${api.currentSpeed.value}%", true)
                    }
                }
                is VoiceCommand.Slower -> {
                    if (api.adjustSpeed(-10)) {
                        showFeedback("Speed: ${api.currentSpeed.value}%", true)
                    }
                }
                is VoiceCommand.SetSpeed -> {
                    val scaled = applySpeedMultiplier(command.percent)
                    if (api.setSpeed(scaled)) {
                        currentBaseSpeed = command.percent
                        showFeedback("Speed: $scaled%", true)
                    }
                }
                is VoiceCommand.SetStroke -> {
                    val range = globalMax - globalMin
                    val target = globalMin + (command.percent * range / 100)
                    val min = (target - range / 4).coerceAtLeast(globalMin)
                    val max = (target + range / 4).coerceAtMost(globalMax)
                    if (api.setStrokeRange(min, max)) {
                        binding.strokeMinSlider.progress = min
                        binding.strokeMinInput.setText(min.toString())
                        binding.strokeMaxSlider.progress = max
                        binding.strokeMaxInput.setText(max.toString())
                        showFeedback("Stroke: $min-$max%", true)
                    }
                }
                is VoiceCommand.Preset -> {
                    applyPreset(command.speed, command.position)
                }
                is VoiceCommand.SetGlobalMin -> {
                    globalMin = command.percent.coerceIn(0, globalMax - 10)
                    saveSettings()
                    binding.globalMinSlider.progress = globalMin
                    binding.globalMinInput.setText(globalMin.toString())
                    binding.strokeMinSlider.min = globalMin
                    val newMin = binding.strokeMinSlider.progress.coerceAtLeast(globalMin)
                    binding.strokeMinSlider.progress = newMin
                    binding.strokeMinInput.setText(newMin.toString())
                    val curMax = binding.strokeMaxSlider.progress
                    api.setStrokeRange(newMin, curMax)
                    debugLog("Global", "min=$globalMin, stroke=$newMin-$curMax")
                    showFeedback("G.Min: $globalMin%", true)
                }
                is VoiceCommand.SetGlobalMax -> {
                    globalMax = command.percent.coerceIn(globalMin + 10, 100)
                    saveSettings()
                    binding.globalMaxSlider.progress = globalMax
                    binding.globalMaxInput.setText(globalMax.toString())
                    binding.strokeMaxSlider.max = globalMax
                    val newMax = binding.strokeMaxSlider.progress.coerceAtMost(globalMax)
                    binding.strokeMaxSlider.progress = newMax
                    binding.strokeMaxInput.setText(newMax.toString())
                    val curMin = binding.strokeMinSlider.progress
                    api.setStrokeRange(curMin, newMax)
                    debugLog("Global", "max=$globalMax, stroke=$curMin-$newMax")
                    showFeedback("G.Max: $globalMax%", true)
                }
                is VoiceCommand.Unknown -> { /* handled above */ }
            }
        }
    }
    
    override fun onResume() {
        super.onResume()
        // Re-initialize voice engine in case settings changed
        lifecycleScope.launch {
            initVoiceEngine()
        }
    }
    
    override fun onDestroy() {
        voiceEngine?.destroy()
        super.onDestroy()
    }
}
