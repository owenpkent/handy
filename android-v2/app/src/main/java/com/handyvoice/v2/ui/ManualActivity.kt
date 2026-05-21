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
import com.handyvoice.v2.dispatch.Dispatch
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.launch

/**
 * Manual remote-control screen. Tap-driven counterpart to ControlActivity's
 * voice-first surface.
 *
 *   - Min / Max:  immediate stroke-range slider, written via setSlide.
 *   - Speed:      immediate velocity, scaled by globalMaxSpeed and written
 *                 via setVelocity. Presets scale into the configured
 *                 globalMinStroke..globalMaxStroke band.
 *
 * Global caps (max speed, stroke band) come from Settings.
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

    // Current "design space" speed (before global scaling). Preset taps update this.
    private var baseSpeed: Int = 50
    private var globalMaxSpeed: Int = PreferencesManager.DEFAULT_GLOBAL_MAX_SPEED
    private var globalMinStroke: Int = PreferencesManager.DEFAULT_GLOBAL_MIN_STROKE
    private var globalMaxStroke: Int = PreferencesManager.DEFAULT_GLOBAL_MAX_STROKE

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_manual)

        prefs = PreferencesManager(applicationContext)

        statusText = findViewById(R.id.statusText)
        feedbackText = findViewById(R.id.feedbackText)

        findViewById<MaterialButton>(R.id.menuButton).setOnClickListener { v -> showMainMenu(v) }

        strokeMinSlider = findViewById(R.id.strokeMinSlider)
        strokeMinInput = findViewById(R.id.strokeMinInput)
        strokeMaxSlider = findViewById(R.id.strokeMaxSlider)
        strokeMaxInput = findViewById(R.id.strokeMaxInput)
        speedSlider = findViewById(R.id.speedSlider)
        speedInput = findViewById(R.id.speedInput)

        wireSliders()
        wirePatterns()
        wireStartStop()
        observeApi()
        bootstrap()
    }

    private fun bootstrap() {
        lifecycleScope.launch {
            val snap = prefs.snapshot()
            globalMaxSpeed = snap.globalMaxSpeed
            globalMinStroke = snap.globalMinStroke
            globalMaxStroke = snap.globalMaxStroke

            // Stroke-range and speed start from sensible defaults each visit
            // (they don't persist); global caps come from prefs, edited in Settings.
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
        // Speed: design-space value; scaled by globalMaxSpeed.
        wireSlider(speedSlider, speedInput) { value ->
            baseSpeed = value.coerceIn(0, 100)
            val device = scaleSpeed(baseSpeed)
            lifecycleScope.launch {
                val ok = api.setSpeed(device)
                if (ok) feedbackText.text = "Speed: $device% (design $baseSpeed)"
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

    // --- Patterns --------------------------------------------------------

    /**
     * The first 5 patterns share labels with the voice dispatch table, so
     * tapping them and saying the word produce identical device state. The
     * remaining 7 are tap-only patterns defined inline below.
     */
    private val tapOnlyPatterns: Map<Int, Triple<Int, Int, Int>> = mapOf(
        // id to (rangeMin, rangeMax, designSpeed)
        R.id.patternPulse  to Triple(40, 60, 70),
        R.id.patternTease  to Triple(0,  50, 20),
        R.id.patternEdge   to Triple(70, 90, 15),
        R.id.patternRush   to Triple(0, 100, 100),
        R.id.patternThrob  to Triple(40, 70, 60),
        R.id.patternBounce to Triple(20, 80, 60),
        R.id.patternClimax to Triple(30, 90, 90),
    )

    private val voicePatternIds: Map<Int, String> = mapOf(
        R.id.patternHeartbeat to "heartbeat",
        R.id.patternWave      to "wave",
        R.id.patternSurge     to "surge",
        R.id.patternBreathe   to "breathe",
        R.id.patternStorm     to "storm",
    )

    private fun wirePatterns() {
        voicePatternIds.forEach { (id, label) ->
            findViewById<MaterialButton>(id).setOnClickListener { runVoicePattern(label) }
        }
        tapOnlyPatterns.forEach { (id, spec) ->
            val name = findViewById<MaterialButton>(id).text.toString()
            findViewById<MaterialButton>(id).setOnClickListener {
                runTapPattern(name, spec.first, spec.second, spec.third)
            }
        }
    }

    private fun runVoicePattern(label: String) {
        lifecycleScope.launch {
            val ok = runCatching {
                Dispatch.dispatch(
                    label = label,
                    api = api,
                    globalMaxSpeed = globalMaxSpeed,
                    globalMinStroke = globalMinStroke,
                    globalMaxStroke = globalMaxStroke,
                )
            }.getOrDefault(false)
            feedbackText.text = if (ok) "Pattern: ${label.replaceFirstChar { it.uppercase() }}"
                                else "Pattern $label failed"
        }
    }

    /**
     * Same shape as Dispatch's Preset action: squash the position band into
     * the configured global stroke band, scale the design speed by
     * globalMaxSpeed, then slide -> start -> velocity. Tap-only so it
     * doesn't need a voice label.
     */
    private fun runTapPattern(name: String, rangeMin: Int, rangeMax: Int, designSpeed: Int) {
        val band = (globalMaxStroke - globalMinStroke).coerceAtLeast(0)
        val sMin = globalMinStroke + rangeMin * band / 100
        val sMax = globalMinStroke + rangeMax * band / 100
        val device = scaleSpeed(designSpeed)
        lifecycleScope.launch {
            val strokeOk = api.setStrokeRange(sMin, sMax)
            val startOk = if (!api.isRunning.value) api.start() else true
            val speedOk = api.setSpeed(device)
            feedbackText.text = if (strokeOk && startOk && speedOk) {
                "Pattern: $name (speed $device%)"
            } else {
                "Pattern $name failed"
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
        return (designSpeed * globalMaxSpeed / 100).coerceIn(0, 100)
    }
}
