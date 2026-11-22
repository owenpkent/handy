package com.handyvoice.v2.ui

import android.content.res.ColorStateList
import android.graphics.Color
import android.view.View
import android.view.ViewGroup
import android.widget.GridLayout
import android.widget.SeekBar
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import androidx.lifecycle.lifecycleScope
import com.google.android.material.button.MaterialButton
import com.handyvoice.v2.R
import com.handyvoice.v2.api.ConnectionState
import com.handyvoice.v2.api.HandyV2ApiClient
import com.handyvoice.v2.data.PreferencesManager
import com.handyvoice.v2.dispatch.HumanizeEngine
import com.handyvoice.v2.dispatch.PatternEngine
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.launch

/**
 * The dedicated Lovense toy page (layout: panel_toy.xml). Owned and wired by
 * [ControlActivity], which shows it instead of the voice-first home screen
 * while the device type is Lovense.
 *
 * No motion logic of its own: START / STOP mirror the Manual tab's handlers,
 * intensity changes route like the Manual speed slider (humanize when
 * enabled, a glide otherwise), patterns run through [PatternEngine] the way
 * the Manual tap-pattern grid does. The pure maths lives in [ToyIntensity].
 */
class ToyPanel(
    private val activity: AppCompatActivity,
    private val api: HandyV2ApiClient,
    private val patternEngine: PatternEngine,
    private val humanizeEngine: HumanizeEngine,
    private val prefs: PreferencesManager,
    private val host: Host,
) {
    /** What the panel needs from the activity that owns it. */
    interface Host {
        fun onConnect()
        fun onSettings()
        fun onPip()
        fun onMore()
        val connectInFlight: Boolean
        val globalMaxSpeed: Int
        val globalMinStroke: Int
        val globalMaxStroke: Int
        val pipSupported: Boolean
    }

    private data class Pattern(val name: String, val label: String, val rangeMin: Int, val rangeMax: Int)

    private val patterns = listOf(
        Pattern("pulse", "Pulse", 0, 100),
        Pattern("ripple", "Ripple", 0, 100),
        Pattern("thunder", "Thunder", 0, 100),
        Pattern("tick", "Tick", 40, 60),
        Pattern("surge", "Surge", 0, 100),
        Pattern("breathe", "Breathe", 0, 100),
        Pattern("tease", "Tease", 0, 50),
        Pattern("edge", "Edge", 70, 90),
        Pattern("rush", "Rush", 0, 100),
        Pattern("throb", "Throb", 40, 70),
        Pattern("bounce", "Bounce", 20, 80),
        Pattern("climax", "Climax", 30, 90),
    )

    val root: View = activity.findViewById(R.id.toyPanel)
    private val statusText: TextView = root.findViewById(R.id.toyStatusText)
    private val warningText: TextView = root.findViewById(R.id.toyWarningText)
    private val connectGroup: View = root.findViewById(R.id.toyConnectGroup)
    private val connectButton: MaterialButton = root.findViewById(R.id.toyConnectButton)
    private val errorText: TextView = root.findViewById(R.id.toyErrorText)
    private val controlGroup: View = root.findViewById(R.id.toyControlGroup)
    private val startStopButton: MaterialButton = root.findViewById(R.id.toyStartStopButton)
    private val intensityText: TextView = root.findViewById(R.id.toyIntensityText)
    private val slider: SeekBar = root.findViewById(R.id.toyIntensitySlider)
    private val pipButton: MaterialButton = root.findViewById(R.id.toyPipButton)
    private val patternGrid: GridLayout = root.findViewById(R.id.toyPatternGrid)
    private val patternButtons = LinkedHashMap<String, MaterialButton>()

    /** Design-space intensity, 0..100 in steps of 5. */
    var intensity: Int = PreferencesManager.DEFAULT_TOY_INTENSITY
        private set
    private var syncingSlider = false

    init {
        buildPatternButtons()
        connectButton.setOnClickListener { host.onConnect() }
        root.findViewById<View>(R.id.toySettingsButton).setOnClickListener { host.onSettings() }
        pipButton.setOnClickListener { host.onPip() }
        root.findViewById<View>(R.id.toyMoreButton).setOnClickListener { host.onMore() }
        startStopButton.setOnClickListener { onStartStopTapped() }
        root.findViewById<View>(R.id.toyMinusButton).setOnClickListener { commitIntensity(ToyIntensity.step(intensity, -1)) }
        root.findViewById<View>(R.id.toyPlusButton).setOnClickListener { commitIntensity(ToyIntensity.step(intensity, 1)) }
        slider.setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(seekBar: SeekBar?, progress: Int, fromUser: Boolean) {
                // Live readout while dragging; the device only hears the commit.
                if (fromUser && !syncingSlider) intensityText.text = "${ToyIntensity.fromSlider(progress)}%"
            }
            override fun onStartTrackingTouch(seekBar: SeekBar?) {}
            override fun onStopTrackingTouch(seekBar: SeekBar?) {
                seekBar?.let { commitIntensity(ToyIntensity.fromSlider(it.progress)) }
            }
        })
        renderIntensity()
        observeApi()
        refresh()
    }

    private fun observeApi() {
        val scope = activity.lifecycleScope
        scope.launch { api.connectionState.collectLatest { refresh() } }
        scope.launch { api.isRunning.collectLatest { refresh() } }
        scope.launch { api.battery.collectLatest { refresh() } }
        scope.launch { api.deviceName.collectLatest { refresh() } }
        scope.launch { api.warning.collectLatest { refresh() } }
        scope.launch { api.lastError.collectLatest { refresh() } }
    }

    /** Adopt the persisted intensity (prefs load / resume). Never touches the device. */
    fun setIntensity(design: Int) {
        intensity = ToyIntensity.snap(design)
        renderIntensity()
    }

    /** Repaint everything from the client's current state. Cheap and idempotent. */
    fun refresh() {
        val state = api.connectionState.value
        val connected = state == ConnectionState.CONNECTED
        val connecting = state == ConnectionState.CONNECTING ||
            (state == ConnectionState.DISCONNECTED && host.connectInFlight)
        val warning = api.warning.value

        statusText.text = when {
            connected -> {
                val name = api.deviceName.value
                val battery = api.battery.value
                buildString {
                    append(if (name != null) "Connected: $name" else "Connected")
                    if (battery != null) append(", battery ").append(battery).append("%")
                }
            }
            connecting -> "Connecting..."
            else -> "Disconnected"
        }
        statusText.setTextColor(color(if (connected) R.color.status_connected else R.color.text_secondary))
        warningText.text = warning
        warningText.visibility = if (warning != null) View.VISIBLE else View.GONE

        connectGroup.visibility = if (connected) View.GONE else View.VISIBLE
        controlGroup.visibility = if (connected) View.VISIBLE else View.GONE
        connectButton.isEnabled = !connecting
        connectButton.text = if (connecting) "Connecting..." else "Connect"
        val reason = api.lastError.value
        errorText.text = reason
        errorText.visibility = if (!connected && !connecting && reason != null) View.VISIBLE else View.GONE
        pipButton.visibility = if (connected && host.pipSupported) View.VISIBLE else View.GONE

        val running = api.isRunning.value
        startStopButton.text = if (running) "STOP" else "START"
        startStopButton.backgroundTintList =
            ColorStateList.valueOf(color(if (running) R.color.emergency_red else R.color.status_connected))
        startStopButton.setTextColor(color(if (running) R.color.text_primary else R.color.on_primary))

        val active = if (patternEngine.isRunning) patternEngine.current else null
        patternButtons.forEach { (name, button) -> paintPattern(button, name == active) }
    }

    private fun renderIntensity() {
        intensityText.text = "$intensity%"
        syncingSlider = true
        slider.progress = ToyIntensity.toSlider(intensity)
        syncingSlider = false
    }

    private fun device(design: Int = intensity) = ToyIntensity.toDevice(design, host.globalMaxSpeed)

    /** START: same as the Manual START handler. */
    private fun onStartStopTapped() {
        if (api.isRunning.value) {
            stopAll()
            return
        }
        activity.lifecycleScope.launch {
            val device = device()
            // When humanize is on, START brings up modulated motion at the
            // current intensity; at 0 there is nothing to modulate.
            if (humanizeEngine.enabled && device > 0) humanizeEngine.start(device)
            // Speed AFTER start: the client refuses positive speed until a start.
            else if (device > 0) { if (api.start()) api.setSpeed(device) }
            // Intensity at zero: write 0 BEFORE start so start cannot resume a
            // remembered level (the toy keeps one across stop). Zero is allowed
            // before start.
            else { api.setSpeed(0); api.start() }
            refresh()
        }
    }

    /** STOP: same as the Manual STOP handler (cancel loops, then the wind-down). */
    private fun stopAll() {
        activity.lifecycleScope.launch {
            humanizeEngine.cancel()
            patternEngine.cancel()
            api.rampStop()
            refresh()
        }
    }

    /** Store a new intensity; push it to the device only while running. */
    private fun commitIntensity(design: Int) {
        intensity = ToyIntensity.snap(design)
        renderIntensity()
        activity.lifecycleScope.launch { prefs.setToyIntensity(intensity) }
        // Stopped: only the stored value changes. The client refuses positive
        // speed before start, and START applies the stored intensity.
        if (!api.isRunning.value) return
        val device = device()
        activity.lifecycleScope.launch {
            val pattern = if (patternEngine.isRunning) patterns.firstOrNull { it.name == patternEngine.current } else null
            when {
                // A pattern's peak is fixed when it starts: restart it at the new peak.
                pattern != null && device > 0 -> startPattern(pattern)
                device == 0 -> {
                    humanizeEngine.cancel()
                    patternEngine.cancel()
                    api.rampStop()
                }
                humanizeEngine.enabled -> humanizeEngine.start(device)
                else -> api.rampSpeed(device)
            }
            refresh()
        }
    }

    private fun onPatternTapped(pattern: Pattern) {
        if (patternEngine.isRunning && patternEngine.current == pattern.name) {
            stopAll()
            return
        }
        if (device() == 0) {
            Toast.makeText(activity, "Raise the intensity first.", Toast.LENGTH_SHORT).show()
            return
        }
        activity.lifecycleScope.launch {
            startPattern(pattern)
            refresh()
        }
    }

    /** Same path as the Manual tap-pattern grid: cancel humanize, set the range, start the envelope. */
    private suspend fun startPattern(pattern: Pattern) {
        if (api.connectionState.value != ConnectionState.CONNECTED) return
        val band = (host.globalMaxStroke - host.globalMinStroke).coerceAtLeast(0)
        val sMin = host.globalMinStroke + pattern.rangeMin * band / 100
        val sMax = host.globalMinStroke + pattern.rangeMax * band / 100
        humanizeEngine.cancel()
        if (!api.setStrokeRange(sMin, sMax)) return
        patternEngine.start(pattern.name, device())
    }

    private fun buildPatternButtons() {
        val dp = activity.resources.displayMetrics.density
        val gap = (4 * dp).toInt()
        patterns.forEach { pattern ->
            val button = MaterialButton(
                activity, null, com.google.android.material.R.attr.materialButtonOutlinedStyle,
            ).apply {
                text = pattern.label
                textSize = 20f
                isAllCaps = false
                minHeight = (64 * dp).toInt()
                minimumHeight = (64 * dp).toInt()
                strokeColor = ColorStateList.valueOf(color(R.color.accent_primary))
                setOnClickListener { onPatternTapped(pattern) }
            }
            val lp = GridLayout.LayoutParams(
                GridLayout.spec(GridLayout.UNDEFINED),
                GridLayout.spec(GridLayout.UNDEFINED, 1f),
            ).apply {
                width = 0
                height = ViewGroup.LayoutParams.WRAP_CONTENT
                setMargins(gap, gap, gap, gap)
            }
            patternGrid.addView(button, lp)
            patternButtons[pattern.name] = button
            paintPattern(button, false)
        }
    }

    private fun paintPattern(button: MaterialButton, active: Boolean) {
        button.backgroundTintList = ColorStateList.valueOf(
            if (active) color(R.color.accent_primary) else Color.TRANSPARENT,
        )
        button.setTextColor(color(if (active) R.color.on_primary else R.color.accent_primary))
    }

    private fun color(res: Int) = ContextCompat.getColor(activity, res)
}
