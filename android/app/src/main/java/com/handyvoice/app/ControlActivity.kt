package com.handyvoice.app

import android.content.Intent
import android.content.SharedPreferences
import android.os.Bundle
import android.text.Editable
import android.text.TextWatcher
import android.view.View
import android.widget.SeekBar
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import androidx.lifecycle.lifecycleScope
import com.handyvoice.app.api.ConnectionState
import com.handyvoice.app.api.HandyV2ApiClient
import com.handyvoice.app.databinding.ActivityControlBinding
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

class ControlActivity : AppCompatActivity() {
    
    private lateinit var binding: ActivityControlBinding
    private lateinit var prefs: SharedPreferences
    private val api = HandyV2ApiClient()
    
    private var speedMultiplier = 100
    private var globalMin = 0
    private var globalMax = 100
    private var currentBaseSpeed = 50
    
    private var feedbackJob: Job? = null
    
    // Preset values
    private val speedValues = mapOf("slow" to 25, "medium" to 50, "fast" to 80)
    private val positionValues = mapOf(
        "top" to Pair(60, 100),
        "middle" to Pair(20, 80)
    )
    
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityControlBinding.inflate(layoutInflater)
        setContentView(binding.root)
        
        prefs = getSharedPreferences("handy_prefs", MODE_PRIVATE)
        
        loadSettings()
        setupUI()
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
        setupPresetButton(binding.presetSlowMid, "slow", "middle")
        setupPresetButton(binding.presetMedMid, "medium", "middle")
        setupPresetButton(binding.presetFastMid, "fast", "middle")
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
        val speedVal = speedValues[speed] ?: return
        val posVal = positionValues[position] ?: return
        
        // Scale preset within global limits
        val globalRange = globalMax - globalMin
        val scaledMin = globalMin + (posVal.first * globalRange / 100)
        val scaledMax = globalMin + (posVal.second * globalRange / 100)
        
        binding.strokeMinSlider.progress = scaledMin
        binding.strokeMinInput.setText(scaledMin.toString())
        binding.strokeMaxSlider.progress = scaledMax
        binding.strokeMaxInput.setText(scaledMax.toString())
        
        currentBaseSpeed = speedVal
        val scaledSpeed = applySpeedMultiplier(speedVal)
        
        lifecycleScope.launch {
            api.setStrokeRange(scaledMin, scaledMax)
            api.setSpeed(scaledSpeed)
            api.start()
            showFeedback("$speed $position - Speed: $scaledSpeed%", true)
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
}
