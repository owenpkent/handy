package com.handyvoice.v2.ui

import android.os.Bundle
import android.text.Editable
import android.text.TextWatcher
import android.view.View
import android.widget.EditText
import android.widget.SeekBar
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import com.google.android.material.button.MaterialButton
import com.handyvoice.v2.R
import com.handyvoice.v2.api.ConnectionState
import com.handyvoice.v2.api.HandyV2ApiClient
import com.handyvoice.v2.data.PreferencesManager
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.launch

/**
 * Manual remote-control screen. Tap-driven counterpart to ControlActivity's
 * voice-first surface. Ports v1 (`../android/`) parity: 6 sliders + 4x3
 * preset grid + start/stop, with the same v1 semantics:
 *
 *   - Min / Max:  immediate stroke-range slider, written via setSlide.
 *   - Speed:      immediate velocity, written via setVelocity. Clamped by
 *                 (globalMaxSpeed * speedLimit / 100).
 *   - G.Min/Max:  stroke-position floor and ceiling. Presets scale into
 *                 this band; e.g. with G.Min=20 G.Max=80, a "top" preset
 *                 (50-90) maps to (50,82).
 *   - Spd.Lim:    hard velocity cap (applied after the globalMaxSpeed
 *                 design-space scaling). Mirrors v1's speedLimit.
 *
 * The three "global" sliders persist to PreferencesManager so they affect
 * the voice path too (voice presets respect the same caps via Dispatch).
 *
 * Connection state is per-activity. We build a fresh HandyV2ApiClient and
 * reconnect on entry using the saved connectionKey, same as ControlActivity.
 */
class ManualActivity : AppCompatActivity() {

    private val api = HandyV2ApiClient()
    private lateinit var prefs: PreferencesManager

    private lateinit var statusText: TextView
    private lateinit var feedbackText: TextView

    private lateinit var strokeMinSlider: SeekBar
    private lateinit var strokeMinInput: EditText
    private lateinit var strokeMaxSlider: SeekBar
    private lateinit var strokeMaxInput: EditText
    private lateinit var speedSlider: SeekBar
    private lateinit var speedInput: EditText
    private lateinit var globalMinSlider: SeekBar
    private lateinit var globalMinInput: EditText
    private lateinit var globalMaxSlider: SeekBar
    private lateinit var globalMaxInput: EditText
    private lateinit var speedLimitSlider: SeekBar
    private lateinit var speedLimitInput: EditText

    // Current "design space" speed (before global scaling). Preset taps update this.
    private var baseSpeed: Int = 50
    private var globalMaxSpeed: Int = PreferencesManager.DEFAULT_GLOBAL_MAX_SPEED
    private var speedLimit: Int = PreferencesManager.DEFAULT_SPEED_LIMIT
    private var globalMinStroke: Int = PreferencesManager.DEFAULT_GLOBAL_MIN_STROKE
    private var globalMaxStroke: Int = PreferencesManager.DEFAULT_GLOBAL_MAX_STROKE

    private val speedValues = mapOf("slow" to 25, "medium" to 50, "fast" to 80)
    private val positionValues = mapOf(
        "top"  to (50 to 90),
        "mid"  to (30 to 70),
        "bot"  to (10 to 50),
        "full" to (0 to 100),
    )

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_manual)

        prefs = PreferencesManager(applicationContext)

        statusText = findViewById(R.id.statusText)
        feedbackText = findViewById(R.id.feedbackText)

        strokeMinSlider = findViewById(R.id.strokeMinSlider)
        strokeMinInput = findViewById(R.id.strokeMinInput)
        strokeMaxSlider = findViewById(R.id.strokeMaxSlider)
        strokeMaxInput = findViewById(R.id.strokeMaxInput)
        speedSlider = findViewById(R.id.speedSlider)
        speedInput = findViewById(R.id.speedInput)
        globalMinSlider = findViewById(R.id.globalMinSlider)
        globalMinInput = findViewById(R.id.globalMinInput)
        globalMaxSlider = findViewById(R.id.globalMaxSlider)
        globalMaxInput = findViewById(R.id.globalMaxInput)
        speedLimitSlider = findViewById(R.id.speedLimitSlider)
        speedLimitInput = findViewById(R.id.speedLimitInput)

        wireSliders()
        wirePresets()
        wireStartStop()
        observeApi()
        bootstrap()
    }

    private fun bootstrap() {
        lifecycleScope.launch {
            val snap = prefs.snapshot()
            globalMaxSpeed = snap.globalMaxSpeed
            speedLimit = snap.speedLimit
            globalMinStroke = snap.globalMinStroke
            globalMaxStroke = snap.globalMaxStroke

            // Seed slider positions from persisted caps; stroke-range and speed
            // start from sensible defaults each visit (they don't persist).
            setSliderValue(globalMinSlider, globalMinInput, globalMinStroke)
            setSliderValue(globalMaxSlider, globalMaxInput, globalMaxStroke)
            setSliderValue(speedLimitSlider, speedLimitInput, speedLimit)
            setSliderValue(strokeMinSlider, strokeMinInput, 10)
            setSliderValue(strokeMaxSlider, strokeMaxInput, 90)
            setSliderValue(speedSlider, speedInput, baseSpeed)

            if (snap.connectionKey.isNotBlank()) {
                api.setConnectionKey(snap.connectionKey)
                api.connect()
            } else {
                statusText.setText(R.string.status_disconnected)
                Toast.makeText(this@ManualActivity, "Connect from the main screen first.", Toast.LENGTH_LONG).show()
            }
        }
    }

    private fun observeApi() {
        lifecycleScope.launch {
            api.connectionState.collectLatest { state ->
                statusText.setText(
                    when (state) {
                        ConnectionState.CONNECTED -> R.string.status_connected
                        ConnectionState.CONNECTING -> R.string.status_disconnected
                        ConnectionState.DISCONNECTED -> R.string.status_disconnected
                    }
                )
            }
        }
        lifecycleScope.launch {
            api.lastError.collectLatest { err ->
                err?.let { feedbackText.text = it }
            }
        }
    }

    // --- Sliders ---------------------------------------------------------

    private fun wireSliders() {
        // Stroke min: immediate setSlide(min, currentMax)
        wireSlider(strokeMinSlider, strokeMinInput) { value ->
            val min = value.coerceIn(0, 100)
            val max = strokeMaxSlider.progress.coerceAtLeast(min)
            if (max != strokeMaxSlider.progress) setSliderValue(strokeMaxSlider, strokeMaxInput, max)
            lifecycleScope.launch {
                val ok = api.setStrokeRange(min, max)
                if (ok) feedbackText.text = "Stroke: $min-$max%"
            }
        }
        wireSlider(strokeMaxSlider, strokeMaxInput) { value ->
            val max = value.coerceIn(0, 100)
            val min = strokeMinSlider.progress.coerceAtMost(max)
            if (min != strokeMinSlider.progress) setSliderValue(strokeMinSlider, strokeMinInput, min)
            lifecycleScope.launch {
                val ok = api.setStrokeRange(min, max)
                if (ok) feedbackText.text = "Stroke: $min-$max%"
            }
        }
        // Speed: design-space value; scaled by globalMaxSpeed and clamped by speedLimit
        wireSlider(speedSlider, speedInput) { value ->
            baseSpeed = value.coerceIn(0, 100)
            val device = scaleSpeed(baseSpeed)
            lifecycleScope.launch {
                val ok = api.setSpeed(device)
                if (ok) feedbackText.text = "Speed: $device% (design $baseSpeed)"
            }
        }
        // Global caps: persist + recompute (don't push to device until next action)
        wireSlider(globalMinSlider, globalMinInput) { value ->
            globalMinStroke = value.coerceIn(0, 100)
            if (globalMinStroke > globalMaxStroke) {
                setSliderValue(globalMaxSlider, globalMaxInput, globalMinStroke)
            }
            lifecycleScope.launch { prefs.setGlobalMinStroke(globalMinStroke) }
            feedbackText.text = "Global stroke band: $globalMinStroke-$globalMaxStroke"
        }
        wireSlider(globalMaxSlider, globalMaxInput) { value ->
            globalMaxStroke = value.coerceIn(0, 100)
            if (globalMaxStroke < globalMinStroke) {
                setSliderValue(globalMinSlider, globalMinInput, globalMaxStroke)
            }
            lifecycleScope.launch { prefs.setGlobalMaxStroke(globalMaxStroke) }
            feedbackText.text = "Global stroke band: $globalMinStroke-$globalMaxStroke"
        }
        wireSlider(speedLimitSlider, speedLimitInput) { value ->
            speedLimit = value.coerceIn(1, 100)
            lifecycleScope.launch {
                prefs.setSpeedLimit(speedLimit)
                // Re-apply current speed under the new cap.
                val device = scaleSpeed(baseSpeed)
                val ok = api.setSpeed(device)
                if (ok) feedbackText.text = "Spd.Lim: $speedLimit% -> Speed: $device%"
            }
        }
    }

    private fun wireSlider(slider: SeekBar, input: EditText, onCommit: (Int) -> Unit) {
        slider.setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(seekBar: SeekBar?, progress: Int, fromUser: Boolean) {
                if (fromUser) input.setText(progress.toString())
            }
            override fun onStartTrackingTouch(seekBar: SeekBar?) {}
            override fun onStopTrackingTouch(seekBar: SeekBar?) {
                seekBar?.let { onCommit(it.progress) }
            }
        })
        input.addTextChangedListener(object : TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) {}
            override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) {}
            override fun afterTextChanged(s: Editable?) {
                val v = s?.toString()?.toIntOrNull() ?: return
                if (slider.progress != v) {
                    slider.progress = v
                    onCommit(v)
                }
            }
        })
    }

    private fun setSliderValue(slider: SeekBar, input: EditText, value: Int) {
        slider.progress = value
        input.setText(value.toString())
    }

    // --- Presets ---------------------------------------------------------

    private fun wirePresets() {
        bindPreset(R.id.presetSlowTop,  "slow",   "top")
        bindPreset(R.id.presetMedTop,   "medium", "top")
        bindPreset(R.id.presetFastTop,  "fast",   "top")
        bindPreset(R.id.presetSlowMid,  "slow",   "mid")
        bindPreset(R.id.presetMedMid,   "medium", "mid")
        bindPreset(R.id.presetFastMid,  "fast",   "mid")
        bindPreset(R.id.presetSlowBot,  "slow",   "bot")
        bindPreset(R.id.presetMedBot,   "medium", "bot")
        bindPreset(R.id.presetFastBot,  "fast",   "bot")
        bindPreset(R.id.presetSlowFull, "slow",   "full")
        bindPreset(R.id.presetMedFull,  "medium", "full")
        bindPreset(R.id.presetFastFull, "fast",   "full")
    }

    private fun bindPreset(id: Int, speed: String, position: String) {
        findViewById<MaterialButton>(id).setOnClickListener { applyPreset(speed, position) }
    }

    private fun applyPreset(speed: String, position: String) {
        val speedVal = speedValues[speed] ?: return
        val (posLo, posHi) = positionValues[position] ?: return

        // Squash the preset's [0..100] position band into the configured global band.
        val band = (globalMaxStroke - globalMinStroke).coerceAtLeast(0)
        val scaledMin = globalMinStroke + posLo * band / 100
        val scaledMax = globalMinStroke + posHi * band / 100

        setSliderValue(strokeMinSlider, strokeMinInput, scaledMin)
        setSliderValue(strokeMaxSlider, strokeMaxInput, scaledMax)
        setSliderValue(speedSlider, speedInput, speedVal)
        baseSpeed = speedVal

        val device = scaleSpeed(speedVal)
        lifecycleScope.launch {
            // Order: slide -> start -> velocity. See HANDY_API_NOTES.
            val strokeOk = api.setStrokeRange(scaledMin, scaledMax)
            val startOk = if (!api.isRunning.value) api.start() else true
            val speedOk = api.setSpeed(device)
            feedbackText.text = if (strokeOk && startOk && speedOk) {
                "$speed $position - Speed: $device%"
            } else {
                "Preset failed (stroke=$strokeOk start=$startOk speed=$speedOk)"
            }
        }
    }

    // --- Start / Stop ---------------------------------------------------

    private fun wireStartStop() {
        findViewById<MaterialButton>(R.id.startButton).setOnClickListener {
            lifecycleScope.launch {
                val ok = api.start()
                if (ok) feedbackText.text = "Started"
            }
        }
        findViewById<MaterialButton>(R.id.stopButton).setOnClickListener {
            lifecycleScope.launch {
                val ok = api.stop()
                if (ok) feedbackText.text = "Stopped"
            }
        }
    }

    // --- Scaling --------------------------------------------------------

    private fun scaleSpeed(designSpeed: Int): Int {
        val scaled = designSpeed * globalMaxSpeed / 100
        return scaled.coerceIn(0, speedLimit)
    }
}
