package com.handyvoice.v2.ui

import android.Manifest
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.content.res.ColorStateList
import android.content.res.Configuration
import android.media.AudioManager
import android.media.ToneGenerator
import android.os.Bundle
import android.os.PowerManager
import android.view.View
import android.view.WindowManager
import android.text.Editable
import android.text.TextWatcher
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.SeekBar
import android.widget.TextView
import android.widget.Toast
import com.google.android.material.tabs.TabLayout
import androidx.activity.OnBackPressedCallback
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import androidx.lifecycle.lifecycleScope
import com.google.android.material.button.MaterialButton
import com.handyvoice.v2.HandyVoiceApp
import com.handyvoice.v2.R
import com.handyvoice.v2.api.ConnectionState
import com.handyvoice.v2.api.HandyV2ApiClient
import com.handyvoice.v2.api.LovenseBle
import com.handyvoice.v2.audio.AudioCapture
import com.handyvoice.v2.audio.Vad
import com.handyvoice.v2.data.PreferencesManager
import com.handyvoice.v2.dispatch.Dispatch
import com.handyvoice.v2.dispatch.HumanizeEngine
import com.handyvoice.v2.dispatch.PatternEngine
import com.handyvoice.v2.update.Updater
import com.handyvoice.v2.voice.MfccDtwEngine
import com.handyvoice.v2.voice.TemplateEngine
import com.handyvoice.v2.voice.TemplateStore
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * Main control screen.
 *
 * Two listening modes, picked in Settings:
 *   - "button"    (default) — tap mic, capture 2.5 s, match, dispatch.
 *   - "always_on"           — continuous capture; VAD opens a 2.5 s window
 *                             when speech is detected, matcher scores it,
 *                             dispatch fires on a confident match.
 *
 * On first run (no saved templates), we punt to TrainingActivity so the
 * user records each command before the engine is usable.
 */
class ControlActivity : AppCompatActivity() {

    companion object {
        /** Longest a device swap waits for an in-flight connect to settle. */
        private const val DEVICE_SWAP_WAIT_MS = 30_000L

        // MUST match TrainingActivity.WINDOW_SECONDS — the matcher aligns
        // query MFCC frames against stored templates, and DTW's band ratio
        // can't bridge a 60-70% length mismatch. If you bump one, bump
        // both, then re-record samples.
        private const val WINDOW_SECONDS = 2.5
        private const val VAD_BLOCK_MS = 30
        private const val VAD_SPEECH_FRAMES_TO_TRIGGER = 4   // ~120 ms of voiced audio
        /**
         * Minimum gap between two captures. Kept short so a "begin" spoken
         * right after a "stop" isn't swallowed: the stamp is set at snapshot
         * time regardless of whether the match passed, so this window is dead
         * air with no capture and no feedback. It used to be 1500 ms, which —
         * stacked on the 1.8 s post-VAD wait and the ~1.5 s stop wind-down —
         * left a ~3.3 s hole after every stop where a restart word was lost.
         * It no longer has to cover the wind-down: a fresh command now
         * supersedes an in-flight ramp at the client (velocityEpoch), so this
         * is just a light debounce against a single utterance double-firing.
         */
        private const val COOLDOWN_MS = 600L
        /**
         * After VAD detects the start of speech, we wait this long before
         * snapshotting the rolling buffer. The trigger fires only ~120 ms
         * into the word, so snapshotting immediately captures mostly the
         * pre-speech silence. Waiting ~1.8 s lets the buffer slide forward
         * far enough that the full word sits inside the 2.5 s window
         * (matching where it sits in the training takes).
         */
        private const val POST_VAD_WAIT_MS = 1800L
        /**
         * The winning label's score has to beat the runner-up by at least
         * this fraction of the *winning score* for a match to count.
         * Relative (not absolute) because DTW distances scale with the
         * audio's noise floor — a quiet room produces winner-scores around
         * 8-10, a noisier one 20+. An absolute margin makes the gate too
         * strict in the quiet case and too lenient in the noisy one. With
         * only one label trained, runnerUp is +inf and the gate is a no-op.
         */
        private const val MIN_RELATIVE_MARGIN = 0.05

        // Once-per-process guard so the quiet launch-time update check fires
        // only on the first ControlActivity creation, not on every rotation
        // or return from Settings.
        @Volatile
        private var launchUpdateChecked = false
    }

    private lateinit var prefs: PreferencesManager
    private lateinit var store: TemplateStore
    private lateinit var engine: MfccDtwEngine
    private lateinit var capture: AudioCapture
    private lateinit var vad: Vad
    // Shared with SplashActivity (held on the Application) so a connection
    // the splash started while animating carries over here intact.
    private val api: HandyV2ApiClient by lazy { (application as HandyVoiceApp).api }
    private val patternEngine: PatternEngine by lazy { PatternEngine(api, lifecycleScope) }
    /** Adds organic speed + depth drift to regular (non-pattern) motion so a
     *  steady speed doesn't feel robotic. Strength (0 = off) comes from prefs. */
    private val humanizeEngine: HumanizeEngine by lazy { HumanizeEngine(api, lifecycleScope) }
    /** Picture-in-Picture: builds the floating control bar and routes its
     *  taps back through the same dispatch path as voice / tap shortcuts. */
    private val pip: PipController by lazy { PipController(this) { label -> onPipControl(label) } }
    /** Short ack beep on a successful voice match. Released in onDestroy. */
    private var ackTone: ToneGenerator? = null

    /**
     * Held only while the listening loop is running so a sleeping screen
     * can't suspend the CPU mid-utterance. The screen is kept on for the
     * whole foreground lifetime by FLAG_KEEP_SCREEN_ON (set in onCreate);
     * this lock is the belt-and-suspenders for a deliberate power-button
     * press that darkens the screen while voice control is still wanted.
     * Released the moment listening stops and again in onDestroy. Reference
     * counting is off so acquire/release stay idempotent against the isHeld
     * guard.
     */
    private var wakeLock: PowerManager.WakeLock? = null

    /**
     * Inactivity watcher. Armed while listening; on expiry we stop the
     * device and stop listening (see onIdleTimeout). Null when not
     * listening. Rather than restart the coroutine on every touch, activity
     * just stamps `lastActivityAt` and the watcher re-checks — cheap even
     * when touches stream in from a slider drag.
     */
    private var idleTimeoutJob: Job? = null
    private var lastActivityAt: Long = 0L

    /**
     * Auto-sleep window: stop the device + listening after this long with no
     * voice command and no touch, so the phone can sleep. User-configurable
     * in Settings (minutes); cached here in millis, refreshed on every
     * onResume, and read by the watcher on each re-check so a change applies
     * without restart.
     */
    private var idleTimeoutMs: Long =
        PreferencesManager.DEFAULT_IDLE_TIMEOUT_MIN * 60_000L

    // Views
    private lateinit var setupPanel: LinearLayout
    private lateinit var controlPanel: LinearLayout
    private lateinit var modeTabs: TabLayout
    private lateinit var voicePanel: LinearLayout
    private lateinit var manualPanel: LinearLayout
    private lateinit var connectionKeyInput: EditText
    private lateinit var connectButton: MaterialButton
    private lateinit var connectProgressRow: View
    private lateinit var settingsButton: MaterialButton
    private lateinit var pipButton: MaterialButton
    private lateinit var mainScroll: View
    // Simplified home screen (one listen button). mainScroll is the
    // "More controls" screen behind it.
    private lateinit var homePanel: View
    private lateinit var homeStatusText: TextView
    private lateinit var homeListenButton: MaterialButton
    private lateinit var homeHeardText: TextView
    private var showingMore = false
    /** Dedicated Lovense page (panel_toy.xml); replaces homePanel while a toy is selected. */
    private lateinit var toyPanel: ToyPanel
    /** True while the toy page (not More) is on screen: voice must not auto-arm then. */
    private val toyPageShowing: Boolean get() = deviceIsLovense && !showingMore
    private val connectGuard = ConnectGuard()
    /** Mirrors the mic button's on/off look so the home button matches it. */
    private var micOn = false
    private val backToHome = object : OnBackPressedCallback(false) {
        override fun handleOnBackPressed() = showMore(false)
    }
    private lateinit var pipOverlay: View
    private lateinit var pipStatusText: TextView
    private lateinit var pipHintText: TextView
    private lateinit var statusText: TextView
    private lateinit var statusDot: View
    private lateinit var voiceModeLabel: TextView
    private lateinit var micButton: MaterialButton
    private lateinit var lastMatchText: TextView
    private lateinit var voicePeakBar: View
    private lateinit var startStopRow: LinearLayout
    private lateinit var startButton: MaterialButton
    private lateinit var stopButton: MaterialButton
    private lateinit var slowerButton: MaterialButton
    private lateinit var fasterButton: MaterialButton
    private lateinit var voiceBeginButton: MaterialButton
    private lateinit var voiceStopButton: MaterialButton
    private lateinit var debugToggleButton: MaterialButton
    private lateinit var debugPanel: View
    private lateinit var debugScrollView: ScrollView
    private lateinit var debugLogText: TextView
    private lateinit var debugCopyButton: MaterialButton
    private lateinit var debugClearButton: MaterialButton

    // Manual tab views
    private lateinit var strokeMinSlider: SeekBar
    private lateinit var strokeMinInput: EditText
    private lateinit var strokeMaxSlider: SeekBar
    private lateinit var strokeMaxInput: EditText
    private lateinit var speedSlider: SeekBar
    private lateinit var speedInput: EditText
    private lateinit var manualFeedbackText: TextView

    private val log: MutableList<String> = mutableListOf()
    private val timestamp = SimpleDateFormat("HH:mm:ss", Locale.US)

    private val listening = ListeningSession(lifecycleScope)
    private var globalMaxSpeed: Int = PreferencesManager.DEFAULT_GLOBAL_MAX_SPEED
    private var globalMinStroke: Int = PreferencesManager.DEFAULT_GLOBAL_MIN_STROKE
    private var globalMaxStroke: Int = PreferencesManager.DEFAULT_GLOBAL_MAX_STROKE
    private var threshold: Float = PreferencesManager.DEFAULT_THRESHOLD
    private var listeningMode: String = PreferencesManager.MODE_BUTTON
    private var invertStroke: Boolean = PreferencesManager.DEFAULT_INVERT_STROKE

    // Last positions of the Manual tab sliders. Restored on resume so the
    // tab feels stateful instead of snapping back every time the activity
    // re-enters foreground. Persisted to DataStore on commit so they also
    // survive process death. These are *UI* state only: restoring a slider
    // position does not auto-push the value to the device.
    private var manualStrokeMin: Int = PreferencesManager.DEFAULT_MANUAL_STROKE_MIN
    private var manualStrokeMax: Int = PreferencesManager.DEFAULT_MANUAL_STROKE_MAX
    private var manualSpeed: Int = PreferencesManager.DEFAULT_MANUAL_SPEED
    private var humanizeStrength: Int = PreferencesManager.DEFAULT_HUMANIZE_STRENGTH

    /** True when Settings selects a Lovense toy (no connection key needed). */
    private var deviceIsLovense = false

    /** Cancel + join of the motion engines after a lost connection. Reconnect and device swap wait for it. */
    private var motionTeardown: Job? = null

    private val requestBle = registerForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) { grants ->
        if (grants.values.all { it }) {
            onConnectTapped()
        } else {
            toast("Bluetooth permission is required to connect to the toy.")
        }
    }

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

        // Keep the screen on the whole time this screen is in the foreground,
        // not just while listening. The OS only honors this flag while the
        // window is visible, so it costs nothing when the app is backgrounded
        // and navigating away (Home / Settings) lets the screen sleep
        // normally. This is the "keep the phone awake while the app is
        // running" behavior; the partial wake lock (keepAwake) is a separate,
        // listening-only concern for when the screen is deliberately off.
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)

        prefs = PreferencesManager(applicationContext)
        store = TemplateStore(applicationContext.filesDir)
        engine = MfccDtwEngine()
        capture = AudioCapture()
        vad = Vad()
        ackTone = runCatching { ToneGenerator(AudioManager.STREAM_MUSIC, 80) }.getOrNull()

        bindViews()
        observeApi()
        bootstrap()

        // Start listening for PiP control-bar taps. Kept registered for the
        // whole activity lifetime (the receiver only fires from our own
        // not-exported PendingIntents), so it works while the PiP window is up.
        pip.register()

        // Quiet check against the public releases repo; prompts only if a
        // newer build is published. Once per process so it can't nag.
        if (!launchUpdateChecked) {
            launchUpdateChecked = true
            Updater(this).check(manual = false)
        }
    }

    private fun bindViews() {
        setupPanel = findViewById(R.id.setupPanel)
        controlPanel = findViewById(R.id.controlPanel)
        modeTabs = findViewById(R.id.modeTabs)
        voicePanel = findViewById(R.id.voicePanel)
        manualPanel = findViewById(R.id.manualPanel)
        connectionKeyInput = findViewById(R.id.connectionKeyInput)
        connectButton = findViewById(R.id.connectButton)
        connectProgressRow = findViewById(R.id.connectProgressRow)
        settingsButton = findViewById(R.id.settingsButton)
        pipButton = findViewById(R.id.pipButton)
        mainScroll = findViewById(R.id.mainScroll)
        homePanel = findViewById(R.id.homePanel)
        homeStatusText = findViewById(R.id.homeStatusText)
        homeListenButton = findViewById(R.id.homeListenButton)
        homeHeardText = findViewById(R.id.homeHeardText)
        pipOverlay = findViewById(R.id.pipOverlay)
        pipStatusText = findViewById(R.id.pipStatusText)
        pipHintText = findViewById(R.id.pipHintText)
        statusText = findViewById(R.id.statusText)
        statusDot = findViewById(R.id.statusDot)
        voiceModeLabel = findViewById(R.id.voiceModeLabel)
        micButton = findViewById(R.id.micButton)
        lastMatchText = findViewById(R.id.lastMatchText)
        voicePeakBar = findViewById(R.id.voicePeakBar)
        startStopRow = findViewById(R.id.startStopRow)
        startButton = findViewById(R.id.startButton)
        stopButton = findViewById(R.id.stopButton)
        slowerButton = findViewById(R.id.slowerButton)
        fasterButton = findViewById(R.id.fasterButton)
        voiceBeginButton = findViewById(R.id.voiceBeginButton)
        voiceStopButton = findViewById(R.id.voiceStopButton)
        debugToggleButton = findViewById(R.id.debugToggleButton)
        debugPanel = findViewById(R.id.debugPanel)
        debugScrollView = findViewById(R.id.debugScrollView)
        debugLogText = findViewById(R.id.debugLogText)
        debugCopyButton = findViewById(R.id.debugCopyButton)
        debugClearButton = findViewById(R.id.debugClearButton)

        strokeMinSlider = findViewById(R.id.strokeMinSlider)
        strokeMinInput = findViewById(R.id.strokeMinInput)
        strokeMaxSlider = findViewById(R.id.strokeMaxSlider)
        strokeMaxInput = findViewById(R.id.strokeMaxInput)
        speedSlider = findViewById(R.id.speedSlider)
        speedInput = findViewById(R.id.speedInput)
        manualFeedbackText = findViewById(R.id.manualFeedbackText)

        wireTabs()
        wireManualSliders()
        wireManualPatterns()
        toyPanel = ToyPanel(this, api, patternEngine, humanizeEngine, prefs, object : ToyPanel.Host {
            override fun onConnect() = onConnectTapped()
            override fun onSettings() = startActivity(Intent(this@ControlActivity, SettingsActivity::class.java))
            override fun onPip() = pip.enter()
            override fun onMore() = showMore(true)
            override val connectInFlight get() = connectGuard.inFlight
            override val globalMaxSpeed get() = this@ControlActivity.globalMaxSpeed
            override val globalMinStroke get() = this@ControlActivity.globalMinStroke
            override val globalMaxStroke get() = this@ControlActivity.globalMaxStroke
            override val pipSupported get() = pip.isSupported
        })

        connectButton.setOnClickListener { onConnectTapped() }
        // The gear goes straight to Settings — no intermediate menu. We're
        // already on the Voice/Manual home, so the menu's only other entry
        // was Settings anyway.
        settingsButton.setOnClickListener {
            startActivity(Intent(this, SettingsActivity::class.java))
        }
        // Float into a PiP window on demand. (Pressing Home while running also
        // auto-floats; see onUserLeaveHint.)
        pipButton.setOnClickListener { pip.enter() }
        micButton.setOnClickListener { onMicTapped() }
        homeListenButton.setOnClickListener { onHomeListenTapped() }
        findViewById<View>(R.id.homeMoreButton).setOnClickListener { showMore(true) }
        findViewById<View>(R.id.homeBackButton).setOnClickListener { showMore(false) }
        onBackPressedDispatcher.addCallback(this, backToHome)
        // Shrink the big button in short windows (split screen) so the status
        // line, readout and More controls usually fit without scrolling; the
        // home panel still scrolls as a fallback.
        homePanel.addOnLayoutChangeListener { _, _, top, _, bottom, _, _, _, _ ->
            val dp = resources.displayMetrics.density
            val size = ((bottom - top) - (220 * dp).toInt())
                .coerceIn((140 * dp).toInt(), (260 * dp).toInt())
            val lp = homeListenButton.layoutParams
            if (lp.width != size) {
                // Posted: changing layout params inside a layout pass is
                // deferred (and logged) by the framework.
                homeListenButton.post {
                    lp.width = size
                    lp.height = size
                    homeListenButton.layoutParams = lp
                    homeListenButton.cornerRadius = size / 2
                }
            }
        }
        // The home screen shows the same "Heard:" readout as the Voice tab;
        // mirror it rather than touching every place that sets it.
        lastMatchText.addTextChangedListener(object : TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) {}
            override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) {}
            override fun afterTextChanged(s: Editable?) { homeHeardText.text = s }
        })
        startButton.setOnClickListener {
            lifecycleScope.launch {
                // When humanize is on, START brings up modulated motion at the
                // current Manual speed (parity with the slider / voice path);
                // at speed 0 there's nothing to modulate, so just start.
                val device = (manualSpeed * globalMaxSpeed.coerceIn(1, 100) / 100).coerceIn(0, 100)
                if (humanizeEngine.enabled && device > 0) humanizeEngine.start(device)
                // Speed AFTER start: the client refuses positive speed until a
                // start, so a slider move made before START never reached the device.
                else if (device > 0) { if (api.start()) api.setSpeed(device) }
                // Slider at zero: write 0 BEFORE start so start can't resume a
                // remembered level (Lovense keeps one across stop; the Handy
                // keeps its last velocity). Zero is allowed before start.
                else { api.setSpeed(0); api.start() }
            }
        }
        stopButton.setOnClickListener {
            lifecycleScope.launch {
                // STOP cancels any running pattern / humanize modulation as
                // well as the device — either loop would otherwise resume
                // sending velocity on its next tick. rampStop then eases
                // velocity to 0 rather than slamming to a halt (same wind-down
                // as the voice "stop").
                humanizeEngine.cancel()
                patternEngine.cancel()
                api.rampStop()
            }
        }
        slowerButton.setOnClickListener {
            lifecycleScope.launch { adjustSpeedRespectingLimits(-Dispatch.DEFAULT_STEP) }
        }
        fasterButton.setOnClickListener {
            lifecycleScope.launch { adjustSpeedRespectingLimits(Dispatch.DEFAULT_STEP) }
        }
        // Voice-command shortcut buttons: tap acts exactly like the voice
        // path so the user has a one-tap fallback when speech isn't an
        // option (or when they're still recording their templates).
        voiceBeginButton.setOnClickListener   { runVoiceShortcut("begin") }
        voiceStopButton.setOnClickListener    { runVoiceShortcut("stop") }

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
            renderDebugLog("")
            api.clearLog()
        }
    }

    private fun observeApi() {
        // Apply the current state once, synchronously, so the very first
        // frame already reflects a connection the splash started — otherwise
        // the Connect button flashes until the collector's first emission.
        applyConnectionState(api.connectionState.value)
        lifecycleScope.launch {
            api.connectionState.collectLatest { state -> applyConnectionState(state) }
        }
        lifecycleScope.launch {
            api.debugLog.collectLatest { lines ->
                // The API client maintains its own ring buffer; just mirror it.
                val merged = (lines + log).distinct().take(80)
                renderDebugLog(merged.joinToString("\n"))
            }
        }
        // Keep the PiP status readout (speed + running/stopped) live. Cheap;
        // only visible while floating, but always kept current so the window
        // is correct the instant it appears.
        lifecycleScope.launch {
            api.isRunning.collectLatest { updatePipStatus() }
        }
        // Lovense: keep the "Connected: name, battery N%" line current.
        lifecycleScope.launch { api.battery.collectLatest { refreshConnectedStatus() } }
        lifecycleScope.launch { api.deviceName.collectLatest { refreshConnectedStatus() } }
        lifecycleScope.launch { api.warning.collectLatest { refreshConnectedStatus() } }
        lifecycleScope.launch {
            api.currentSpeed.collectLatest { updatePipStatus() }
        }
    }

    /** Drive the setup/control panels and the connecting spinner off one
     *  connection state. Called both synchronously on create (for the first
     *  frame) and from the state collector. */
    private fun applyConnectionState(state: ConnectionState) {
        when (state) {
            ConnectionState.CONNECTED -> {
                showConnecting(false)
                setupPanel.visibility = View.GONE
                controlPanel.visibility = View.VISIBLE
                setConnectedStatusText()
                statusText.setTextColor(ContextCompat.getColor(this, R.color.status_connected))
                // Offer the float button only once connected (and only where
                // the device actually supports PiP).
                pipButton.visibility = if (pip.isSupported) View.VISIBLE else View.GONE
                applyListeningMode()
            }
            ConnectionState.CONNECTING -> {
                statusText.text = "Connecting..."
                // Clear any red failure color from a previous attempt.
                statusText.setTextColor(ContextCompat.getColor(this, R.color.text_secondary))
                pipButton.visibility = View.GONE
                showConnecting(true)
            }
            ConnectionState.DISCONNECTED -> {
                showConnecting(false)
                controlPanel.visibility = View.GONE
                setupPanel.visibility = View.VISIBLE
                pipButton.visibility = View.GONE
                // If we landed here because an attempt failed, show *why*
                // (e.g. "Device not connected. Make sure your Handy is
                // online.") in red rather than a bare "Disconnected". This
                // covers both the manual Connect tap and the splash-time
                // auto-connect, since both end in DISCONNECTED with the
                // reason cached on the client. A clean disconnect clears
                // lastError, so it falls back to the generic label.
                val reason = api.lastError.value
                if (reason != null) {
                    statusText.text = reason
                    statusText.setTextColor(
                        ContextCompat.getColor(this, R.color.status_disconnected)
                    )
                } else {
                    statusText.setText(R.string.status_disconnected)
                    statusText.setTextColor(
                        ContextCompat.getColor(this, R.color.text_secondary)
                    )
                }
                stopAlwaysOn()
                // A lost (or ended) connection must not leave a humanize /
                // pattern loop alive: it would drive the device again the
                // moment a reconnect lands. Cancel and join both; reconnect
                // and device swaps wait on this job.
                motionTeardown = lifecycleScope.launch {
                    humanizeEngine.cancel()
                    patternEngine.cancel()
                }
            }
        }
        homeStatusText.text = statusText.text
        homeStatusText.setTextColor(statusText.currentTextColor)
        updateHomeButton()
    }

    /** "Connected", or for a Lovense toy "Connected: <name>, battery N%". */
    private fun setConnectedStatusText() {
        val name = api.deviceName.value
        val battery = api.battery.value
        val warning = api.warning.value
        if (api.transportKind != "lovense" || name == null) {
            statusText.setText(R.string.status_connected)
            if (warning != null) statusText.text = "${statusText.text}\n$warning"
            return
        }
        statusText.text = buildString {
            append("Connected: ").append(name)
            if (battery != null) append(", battery ").append(battery).append("%")
            if (warning != null) append("\n").append(warning)
        }
    }

    /** Re-render only the connected status line (battery / name changed). */
    private fun refreshConnectedStatus() {
        if (api.connectionState.value != ConnectionState.CONNECTED) return
        setConnectedStatusText()
        homeStatusText.text = statusText.text
    }

    /** Show the connection-key field only for the Handy. */
    private fun applyDeviceTypeUi() {
        val keyVisibility = if (deviceIsLovense) View.GONE else View.VISIBLE
        connectionKeyInput.visibility = keyVisibility
        findViewById<View>(R.id.connectionKeyLabel).visibility = keyVisibility
    }

    /**
     * Pick the transport the prefs ask for. Returns true if the installed
     * transport now matches [snap]; only then does the caller adopt the selected
     * device's speed cap (the old device keeps its cap until the swap succeeds).
     * A connect still in flight is waited out first; a live connection is wound
     * down and disconnected, since the transport can only be swapped while
     * disconnected. If the swap cannot happen, nothing about the screen changes
     * and the next onResume retries.
     */
    private suspend fun syncDevice(snap: PreferencesManager.Snapshot): Boolean {
        val app = application as HandyVoiceApp
        if (app.deviceChanged(snap)) {
            if (api.connectionState.value == ConnectionState.CONNECTING) {
                val settled = withTimeoutOrNull(DEVICE_SWAP_WAIT_MS) {
                    api.connectionState.first { it != ConnectionState.CONNECTING }
                }
                if (settled == null) return false
            }
            if (api.connectionState.value == ConnectionState.CONNECTED) {
                humanizeEngine.cancel()
                patternEngine.cancel()
                stopAlwaysOn()
                api.disconnect()
            }
            motionTeardown?.join()
            humanizeEngine.cancel()
            patternEngine.cancel()
        }
        if (!app.configureDevice(snap)) return false
        deviceIsLovense = snap.isLovense
        applyDeviceTypeUi()
        updateHomeButton()
        // Swap between the voice home screen and the toy page.
        showMore(showingMore)
        return true
    }

    /** Switch between the one-button home screen and "More controls". */
    private fun showMore(more: Boolean) {
        showingMore = more
        mainScroll.visibility = if (more) View.VISIBLE else View.GONE
        homePanel.visibility = if (more || deviceIsLovense) View.GONE else View.VISIBLE
        toyPanel.root.visibility = if (!more && deviceIsLovense) View.VISIBLE else View.GONE
        // System Back returns home from More; on home it behaves normally.
        backToHome.isEnabled = more
    }

    /**
     * The home button is the listen toggle once connected (same path as the
     * Voice tab's mic). Before that it doubles as Connect, so a failed
     * auto-connect can be retried without leaving home; with no saved key it
     * opens More, where the key field is.
     */
    private fun onHomeListenTapped() {
        when (api.connectionState.value) {
            ConnectionState.CONNECTED -> onMicTapped()
            ConnectionState.CONNECTING -> Unit
            ConnectionState.DISCONNECTED -> when {
                connectGuard.inFlight -> Unit
                !deviceIsLovense && connectionKeyInput.text.isNullOrBlank() -> showMore(true)
                else -> onConnectTapped()
            }
        }
    }

    private fun updateHomeButton() {
        val state = api.connectionState.value
        val connecting = state == ConnectionState.CONNECTING ||
            (state == ConnectionState.DISCONNECTED && connectGuard.inFlight)
        val (label, color) = when {
            connecting -> "Connecting…" to R.color.card_dark
            state == ConnectionState.CONNECTED ->
                if (micOn) "Stop\nlistening" to R.color.emergency_red
                else "Start\nlistening" to R.color.primary
            else -> "Connect" to R.color.primary
        }
        if (::toyPanel.isInitialized) toyPanel.refresh()
        homeListenButton.isEnabled = !connecting
        homeListenButton.text = label
        homeListenButton.backgroundTintList =
            ColorStateList.valueOf(ContextCompat.getColor(this, color))
        homeListenButton.setTextColor(ContextCompat.getColor(
            this,
            if (color == R.color.card_dark) R.color.text_secondary else R.color.on_primary,
        ))
    }

    private suspend fun adjustSpeedRespectingLimits(designDelta: Int) {
        val cap = globalMaxSpeed.coerceIn(1, 100)
        val scaledDelta = designDelta * cap / 100
        if (humanizeEngine.isRunning) {
            // Retarget the humanize loop's stable base (mirrors Dispatch's
            // AdjustSpeed path so tap and voice faster/slower behave the same).
            val target = (humanizeEngine.currentTarget + scaledDelta).coerceIn(0, cap)
            humanizeEngine.setTarget(target)
        } else {
            val target = (api.currentSpeed.value + scaledDelta).coerceIn(0, cap)
            // Smooth glide to the new speed (same ramp the voice path uses).
            api.rampSpeed(target)
        }
    }

    private fun bootstrap() {
        lifecycleScope.launch {
            val snap = prefs.snapshot()
            globalMinStroke = snap.globalMinStroke
            globalMaxStroke = snap.globalMaxStroke
            threshold = snap.matchThreshold
            listeningMode = snap.listeningMode
            manualStrokeMin = snap.manualStrokeMin
            manualStrokeMax = snap.manualStrokeMax
            manualSpeed = snap.manualSpeed
            toyPanel.setIntensity(snap.toyIntensity)
            invertStroke = snap.invertStroke
            humanizeStrength = snap.humanizeStrength
            humanizeEngine.setStrength(snap.humanizeStrength)
            api.setInvertStroke(snap.invertStroke)
            api.setGlobalStrokeBand(snap.globalMinStroke, snap.globalMaxStroke)
            if (syncDevice(snap)) globalMaxSpeed = snap.globalMaxSpeed
            applyManualCaps()

            // Onboarding gate: only force training on first run. After the user
            // has been through (or deliberately bailed out of) onboarding, trust
            // the flag — they can re-record from Settings whenever they want.
            if (!snap.onboarded) {
                appLog("First run; launching onboarding.")
                startActivity(Intent(this@ControlActivity, TrainingActivity::class.java))
                return@launch
            }

            // Load templates into the matcher (may be empty if user bailed onboarding).
            val loaded = withContext(Dispatchers.IO) { loadKnownTemplates() }
            engine.loadInto(loaded)
            updateLastMatchForTemplates()
            appLog("Engine ready (${loaded.size} labels: ${loaded.keys.joinToString(",")})")

            // Restore connection key. The splash already kicks off the
            // connect while it animates, so only start one here if it didn't
            // (no key at splash time, or the shared client is still idle).
            connectionKeyInput.setText(snap.connectionKey)
            if (api.connectionState.value == ConnectionState.DISCONNECTED) {
                if (snap.isLovense) {
                    // Toy: no key. Auto-connect only if BLE permission is
                    // already granted; otherwise the first tap asks for it.
                    if (LovenseBle.hasPermissions(this@ControlActivity)) api.connect()
                } else if (snap.connectionKey.isNotBlank()) {
                    api.setConnectionKey(snap.connectionKey)
                    api.connect()
                }
            }
        }
    }

    override fun onResume() {
        super.onResume()
        // Re-load prefs and any newly-recorded templates so changes made in
        // Settings or Training take effect without restarting the activity.
        lifecycleScope.launch {
            val snap = prefs.snapshot()
            val modeChanged = listeningMode != snap.listeningMode
            val invertChanged = invertStroke != snap.invertStroke
            val bandChanged = globalMinStroke != snap.globalMinStroke ||
                globalMaxStroke != snap.globalMaxStroke
            globalMinStroke = snap.globalMinStroke
            globalMaxStroke = snap.globalMaxStroke
            threshold = snap.matchThreshold
            listeningMode = snap.listeningMode
            manualStrokeMin = snap.manualStrokeMin
            manualStrokeMax = snap.manualStrokeMax
            manualSpeed = snap.manualSpeed
            toyPanel.setIntensity(snap.toyIntensity)
            invertStroke = snap.invertStroke
            humanizeStrength = snap.humanizeStrength
            // Live-apply a strength change from Settings. If the user turned
            // humanize off mid-motion, cancel the loop so the device settles
            // back onto the plain constant speed / user band.
            humanizeEngine.setStrength(snap.humanizeStrength)
            if (snap.humanizeStrength == 0 && humanizeEngine.isRunning) humanizeEngine.cancel()
            if (syncDevice(snap)) globalMaxSpeed = snap.globalMaxSpeed
            api.setInvertStroke(snap.invertStroke)
            api.setGlobalStrokeBand(snap.globalMinStroke, snap.globalMaxStroke)
            applyManualCaps()

            val reloaded = withContext(Dispatchers.IO) { loadKnownTemplates() }
            engine.loadInto(reloaded)
            updateLastMatchForTemplates()

            // The stroke band / invert only reach the device the next time a
            // range is pushed (a pattern, a preset, a manual-slider commit), so
            // changing them in Settings used to feel like it "only worked for
            // patterns". Re-assert the range now so the change lands right away,
            // including for a plain Begin / faster.
            //   - Band changed: push the new full band (gMin..gMax). This is
            //     exactly what a pattern's squashRange(0,100) sends, so it
            //     matches what the user saw "apply" when they ran a pattern,
            //     and it works in both directions (widening or narrowing).
            //   - Invert only: re-push the current user-coord range so the
            //     orientation flips while keeping whatever range is active.
            // setStrokeRange applies invert internally, so the band push also
            // covers a simultaneous invert change.
            if ((bandChanged || invertChanged) &&
                api.connectionState.value == ConnectionState.CONNECTED) {
                if (bandChanged) {
                    api.setStrokeRange(snap.globalMinStroke, snap.globalMaxStroke)
                } else {
                    api.setStrokeRange(api.strokeMin.value, api.strokeMax.value)
                }
            }

            // Re-arm listening when we come back to the foreground. Two cases
            // matter here:
            //   - Always-on: the loop may have been torn down by a real
            //     navigate-away (Home/Settings), so restart it if it isn't
            //     already running. (If it survived a screen-off on the wake
            //     lock, the isActive guard makes this a no-op.)
            //   - Button mode: only re-apply when the mode actually changed,
            //     since button listening is tap-gated and must not auto-start.
            if (api.connectionState.value == ConnectionState.CONNECTED) {
                if (listeningMode == PreferencesManager.MODE_ALWAYS_ON) {
                    // Never auto-arm voice while the toy page is showing.
                    if (!listening.isActive && !toyPageShowing) applyListeningMode()
                } else if (modeChanged) {
                    applyListeningMode()
                }
            }
        }
    }

    /**
     * Load on-disk templates, dropping any folder whose label isn't in the
     * current vocabulary. A prior build's words (ripple / pulse / thunder, or
     * the old "start" before it became "begin") otherwise linger on disk and
     * keep winning matches even though nothing dispatches them, polluting the
     * match space. Filtering here (rather than in TemplateStore) keeps the
     * store free of a dispatch-layer dependency. The stale folders stay on
     * disk but are simply ignored; "Clear all recordings" removes them.
     */
    private fun loadKnownTemplates(): Map<String, List<Array<DoubleArray>>> =
        store.loadAll().filterKeys { it in Dispatch.LABELS || it == Dispatch.NOISE_LABEL }

    /** Show a hint in the match area that reflects current template state.
     *  Counts command labels only — the reject/noise class isn't a command. */
    private fun updateLastMatchForTemplates() {
        val cmdCount = engine.labels().count { it in Dispatch.LABELS }
        lastMatchText.text = when {
            cmdCount == 0 -> "No voice samples yet — tap Re-record below."
            cmdCount < Dispatch.LABELS.size -> "Trained: $cmdCount/${Dispatch.LABELS.size} commands"
            else -> ""
        }
    }

    override fun onStop() {
        super.onStop()
        // Distinguish a screen-off from a genuine navigate-away. When the
        // screen goes dark (power button or — absent our flag — an idle
        // timeout) the device is non-interactive but the user still wants
        // voice control, so we keep the loop alive on the partial wake lock.
        // When the user actually leaves (Home, or opening Settings) the
        // screen is still interactive: stop listening then to free the mic
        // and stop burning battery. onResume re-arms always-on on return.
        val pm = getSystemService(Context.POWER_SERVICE) as PowerManager
        if (pm.isInteractive) {
            stopAlwaysOn()
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        keepAwake(false)
        pip.unregister()
        ackTone?.release()
        ackTone = null
    }

    /**
     * Pressing Home / Recents while the device is actively running auto-floats
     * the app into a PiP window, so Stop and the speed controls stay one tap
     * away in the system overlay while the user is in another app. We gate on
     * `isRunning` deliberately: if nothing is moving, Home should just send the
     * app to the background normally. The header float button covers the
     * explicit, while-idle case. Not called when opening Settings / Training
     * (those are in-app startActivity transitions, not a user-leave).
     */
    override fun onUserLeaveHint() {
        super.onUserLeaveHint()
        if (pip.isSupported &&
            !isInPictureInPictureMode &&
            api.connectionState.value == ConnectionState.CONNECTED &&
            api.isRunning.value) {
            pip.enter()
        }
    }

    /**
     * Swap between the full UI and a clean PiP status readout. The floating
     * window is too small for the real layout, and its controls are the system
     * RemoteAction bar (see PipController) rather than on-screen buttons, so we
     * hide the scroll view and show just speed + running state.
     */
    override fun onPictureInPictureModeChanged(
        isInPictureInPictureMode: Boolean,
        newConfig: Configuration,
    ) {
        super.onPictureInPictureModeChanged(isInPictureInPictureMode, newConfig)
        if (isInPictureInPictureMode) {
            mainScroll.visibility = View.GONE
            homePanel.visibility = View.GONE
            toyPanel.root.visibility = View.GONE
            pipOverlay.visibility = View.VISIBLE
            pipHintText.text = "Tap to open"
            updatePipStatus()
        } else {
            pipOverlay.visibility = View.GONE
            showMore(showingMore)
        }
    }

    /**
     * Hold a partial wake lock while the listening loop runs so a
     * deliberately darkened screen (power button) can't suspend the CPU and
     * kill capture mid-utterance. The screen itself is kept on for the whole
     * foreground lifetime by the FLAG_KEEP_SCREEN_ON set in onCreate, so this
     * only manages the CPU lock; it's dropped the instant listening stops.
     */
    private fun keepAwake(on: Boolean) {
        if (on) {
            if (wakeLock == null) {
                val pm = getSystemService(Context.POWER_SERVICE) as PowerManager
                wakeLock = pm.newWakeLock(
                    PowerManager.PARTIAL_WAKE_LOCK,
                    "HandyVoiceV2:listening",
                ).apply { setReferenceCounted(false) }
            }
            if (wakeLock?.isHeld != true) wakeLock?.acquire()
        } else {
            if (wakeLock?.isHeld == true) wakeLock?.release()
        }
    }

    /**
     * Any touch dispatched to the activity counts as activity, so a user
     * working the Manual sliders (no voice command involved) won't get the
     * device stopped out from under them. Voice matches reset the timer
     * separately in handleAudio.
     */
    override fun onUserInteraction() {
        super.onUserInteraction()
        noteActivity()
    }

    /** Mark activity so the idle watcher pushes its deadline forward. Cheap. */
    private fun noteActivity() {
        lastActivityAt = System.currentTimeMillis()
    }

    /**
     * Start the inactivity watcher from now. It sleeps until the deadline,
     * and if activity happened in the meantime (lastActivityAt moved) it
     * sleeps again for the remaining time instead of firing. Only stops the
     * session once the full idleTimeoutMs has elapsed with no activity.
     */
    private fun armIdleTimeout() {
        idleTimeoutJob?.cancel()
        noteActivity()
        idleTimeoutJob = lifecycleScope.launch {
            while (true) {
                val idleFor = System.currentTimeMillis() - lastActivityAt
                val remaining = idleTimeoutMs - idleFor
                if (remaining <= 0) break
                delay(remaining)
            }
            idleTimeoutJob = null
            onIdleTimeout()
        }
    }

    /**
     * Fired after idleTimeoutMs of no activity. Stop the device (same path
     * as a "stop" command — cancels any running pattern, then HAMP stop),
     * then stop listening, which drops the keep-awake so the phone sleeps
     * normally. The user restarts with a mic tap (or always-on re-arms on
     * the next resume).
     */
    private fun onIdleTimeout() {
        appLog("Idle ${idleTimeoutMs / 60000} min — stopping device and listening.")
        stopAlwaysOn(stopSource = "Idle-stop")
        if (listeningMode == PreferencesManager.MODE_ALWAYS_ON) {
            voiceModeLabel.text = "Paused (idle) — tap mic to resume"
        } else {
            voiceModeLabel.text = "Tap mic to listen"
        }
        setMicListening(false)
    }

    /**
     * Halt the device through the same path as a spoken "stop": cancels any
     * running pattern / humanize loop, then the rampStop wind-down. Used when
     * listening ends (idle timeout, mic toggled off) so the device never keeps
     * moving with no voice control left to stop it.
     *
     * Runs via [ListeningSession.cancelThen] (see [stopAlwaysOn]), so the
     * cancelled session, and any recognition job mid-dispatch, has finished
     * before this runs; a stale "begin" can't land during the wind-down.
     */
    private suspend fun dispatchStop(source: String) {
        runCatching {
            Dispatch.dispatch(
                label = "stop",
                api = api,
                globalMaxSpeed = globalMaxSpeed,
                globalMinStroke = globalMinStroke,
                globalMaxStroke = globalMaxStroke,
                patternEngine = patternEngine,
                humanizeEngine = humanizeEngine,
            )
        }.onFailure { appLog("$source dispatch error: ${it.message}") }
    }

    private fun onConnectTapped() {
        val key = connectionKeyInput.text.toString().trim()
        if (!deviceIsLovense && key.isBlank()) {
            toast("Connection key required.")
            return
        }
        if (deviceIsLovense && !LovenseBle.hasPermissions(this)) {
            requestBle.launch(LovenseBle.requiredPermissions())
            return
        }
        // Flip the UI to its busy state immediately on tap, before the
        // suspend connect() even sets CONNECTING — closes the brief window
        // where a double-tap could fire two connect attempts.
        // connectGuard claims its slot synchronously, before the prefs write
        // suspends, so a second tap (from either Connect button) is dropped
        // rather than racing a parallel connect.
        val started = connectGuard.launch(
            lifecycleScope,
            onDone = {
                if (api.connectionState.value != ConnectionState.CONNECTING) showConnecting(false)
                updateHomeButton()
            },
        ) {
            motionTeardown?.join()
            if (!deviceIsLovense) {
                prefs.setConnectionKey(key)
                api.setConnectionKey(key)
            }
            val ok = api.connect()
            if (!ok) {
                // The DISCONNECTED state already paints the reason on the
                // status line; a toast makes the failure unmissable right
                // after the tap.
                toast(api.lastError.value ?: "Connection failed.")
            }
        }
        if (started) showConnecting(true)
    }

    /**
     * Toggle the "busy connecting" UI: swap the Connect button for a spinner
     * and lock the button + key field so an impatient tap can't queue a
     * second connect attempt. Idempotent; driven by both the tap handler and
     * the connectionState observer.
     */
    private fun showConnecting(connecting: Boolean) {
        connectProgressRow.visibility = if (connecting) View.VISIBLE else View.GONE
        connectButton.visibility = if (connecting) View.GONE else View.VISIBLE
        connectButton.isEnabled = !connecting
        connectionKeyInput.isEnabled = !connecting
        updateHomeButton()
    }

    private fun ensureMicThenRun(block: () -> Unit) {
        val granted = ContextCompat.checkSelfPermission(this, Manifest.permission.RECORD_AUDIO) ==
            PackageManager.PERMISSION_GRANTED
        if (granted) block() else requestMic.launch(Manifest.permission.RECORD_AUDIO)
    }

    private fun applyListeningMode() {
        stopAlwaysOn()
        when (listeningMode) {
            // The toy page is not voice-first: even in always-on mode the mic
            // stays off until the user opens More and taps it.
            PreferencesManager.MODE_ALWAYS_ON -> if (toyPageShowing) {
                voiceModeLabel.text = "Tap mic to listen"
                setMicListening(false)
            } else {
                voiceModeLabel.text = "Always listening"
                setMicListening(true)
                ensureMicThenRun { startAlwaysOn() }
            }
            else -> {
                voiceModeLabel.text = "Tap mic to listen"
                setMicListening(false)
            }
        }
    }

    /**
     * Reflect the listening state on the mic button: red ("ON") while the
     * loop is live, brand blue ("MIC") when idle. Centralized so every
     * call site flips both the label and the colour together — a red button
     * with "MIC" on it (or vice-versa) can't happen.
     */
    private fun setMicListening(listening: Boolean) {
        micButton.text = if (listening) "ON" else "MIC"
        val tint = ContextCompat.getColor(
            this,
            if (listening) R.color.emergency_red else R.color.primary,
        )
        micButton.backgroundTintList = ColorStateList.valueOf(tint)
        micOn = listening
        updateHomeButton()
    }

    /**
     * The mic is a listen toggle in both modes: a tap while listening stops
     * the loop and the device; a tap while stopped starts the VAD-driven
     * loop. In always-on mode that pause lasts until the next tap or resume
     * (onResume re-arms it), the same as after an idle auto-stop.
     */
    private fun onMicTapped() {
        val alwaysOn = listeningMode == PreferencesManager.MODE_ALWAYS_ON
        if (listening.isActive) {
            // Turning listening off also stops motion: once the mic is off
            // there's no voice "stop" left, so the device shouldn't keep going.
            appLog("Mic off — stopping device and listening.")
            stopAlwaysOn(stopSource = "Mic-off stop")
            voiceModeLabel.text = if (alwaysOn) "Paused — tap mic to resume" else "Tap mic to listen"
            setMicListening(false)
            return
        }
        ensureMicThenRun {
            voiceModeLabel.text = if (alwaysOn) "Always listening" else "Listening — tap to stop"
            setMicListening(true)
            startAlwaysOn()
        }
    }

    private suspend fun handleAudio(audio: ShortArray) {
        val peak = vad.peak(audio)
        appLog("Captured peak=$peak")
        val match = withContext(Dispatchers.Default) { engine.match(audio) }
        if (match == null) {
            lastMatchText.text = if (engine.labels().isEmpty()) {
                "No voice samples yet — tap Re-record below."
            } else {
                "Didn't catch that — try again."
            }
            return
        }
        val passesScore = match.score <= threshold
        val requiredMargin = kotlin.math.max(match.score, 1.0) * MIN_RELATIVE_MARGIN
        val passesMargin = match.runnerUp == Double.POSITIVE_INFINITY ||
            match.margin >= requiredMargin
        val passes = passesScore && passesMargin
        // Reject class: the winning template is the device's own motor sound /
        // background noise, not a command. Drop it (no dispatch, no beep, no
        // idle-timer reset) so the device can't false-trigger itself — this is
        // the fix for the motor noise being heard as "stop".
        if (passes && match.label == Dispatch.NOISE_LABEL) {
            appLog("Ignored: matched noise class (score ${"%.2f".format(match.score)}).")
            lastMatchText.text = "· ignored (background noise)"
            return
        }
        val reason = when {
            !passesScore -> "below threshold"
            !passesMargin -> "low confidence (margin ${"%.1f".format(match.margin)})"
            else -> "ok"
        }
        val line = "Heard: ${match.label}  score=${"%.2f".format(match.score)} margin=${"%.2f".format(match.margin)}"
        appLog(if (passes) line else "$line  ($reason)")
        lastMatchText.text = when {
            passes -> "✓ ${match.label}"
            !passesScore -> "? ${match.label} (score ${"%.1f".format(match.score)})"
            else -> "? close call — try again"
        }
        if (passes) {
            noteActivity()
            playAck()
            runCatching {
                Dispatch.dispatch(
                    label = match.label,
                    api = api,
                    globalMaxSpeed = globalMaxSpeed,
                    globalMinStroke = globalMinStroke,
                    globalMaxStroke = globalMaxStroke,
                    patternEngine = patternEngine,
                    humanizeEngine = humanizeEngine,
                )
            }.onFailure { appLog("Dispatch error: ${it.message}") }
        }
    }

    private fun startAlwaysOn() {
        if (listening.isActive) return
        keepAwake(true)
        armIdleTimeout()
        appLog("Always-on listening started.")
        listening.start(Dispatchers.IO) {
            val blockSamples = AudioCapture.SAMPLE_RATE * VAD_BLOCK_MS / 1000
            val windowSamples = (AudioCapture.SAMPLE_RATE * WINDOW_SECONDS).toInt()
            val buffer = ShortArray(windowSamples)
            var written = 0
            var voicedRun = 0
            var lastFireAt = 0L
            // 0 = no snapshot pending. Set to the wall-clock time at which
            // we should grab the buffer once it contains the whole word.
            var pendingSnapshotAt = 0L
            // Recognition runs as a child of this session, so stopAlwaysOn
            // cancels a pending match along with capture and a stop can join it.
            val session = this

            capture.stream(blockSamples, shouldContinue = { session.isActive }) { block ->
                // Sliding-window: copy block into a circular buffer; the
                // last `windowSamples` of audio are always at hand.
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

                val now = System.currentTimeMillis()

                // Fire a deferred snapshot once the post-VAD wait elapses.
                // VAD events while a snapshot is pending are ignored so we
                // don't restart the timer mid-utterance.
                if (pendingSnapshotAt != 0L) {
                    if (now >= pendingSnapshotAt && written >= windowSamples) {
                        pendingSnapshotAt = 0L
                        lastFireAt = now
                        voicedRun = 0
                        val snapshot = buffer.copyOf()
                        session.launch(Dispatchers.Main) { handleAudio(snapshot) }
                    }
                    return@stream
                }

                if (vad.isSpeech(block)) voicedRun++ else voicedRun = 0

                // Live peak meter so the user can confirm the mic is hot
                // even when no command has fired yet. Computed off the
                // raw block; sent to the UI thread to update the bar.
                val peak = blockPeak(block)
                val scale = (peak / 16000f).coerceIn(0f, 1f)
                voicePeakBar.post { voicePeakBar.scaleX = scale }

                if (voicedRun >= VAD_SPEECH_FRAMES_TO_TRIGGER &&
                    written >= windowSamples &&
                    now - lastFireAt >= COOLDOWN_MS) {
                    // Don't snapshot yet — wait long enough for the rolling
                    // buffer to actually contain the full utterance.
                    pendingSnapshotAt = now + POST_VAD_WAIT_MS
                }
            }
        }
    }

    private fun blockPeak(block: ShortArray): Int {
        var p = 0
        for (s in block) {
            val a = if (s < 0) -s.toInt() else s.toInt()
            if (a > p) p = a
        }
        return p
    }

    /**
     * Tear down listening synchronously (session, idle watcher, wake lock,
     * meter). With [stopSource], also stop the device once the old session has
     * joined (see [ListeningSession.cancelThen]).
     */
    private fun stopAlwaysOn(stopSource: String? = null) {
        if (listening.isActive) appLog("Always-on stopped.")
        if (stopSource != null) {
            listening.cancelThen { dispatchStop(stopSource) }
        } else {
            listening.cancel()
        }
        idleTimeoutJob?.cancel()
        idleTimeoutJob = null
        keepAwake(false)
        voicePeakBar.post { voicePeakBar.scaleX = 0f }
    }

    private fun appLog(message: String) {
        val line = "[${timestamp.format(Date())}] $message"
        log.add(0, line)
        if (log.size > 100) log.removeLast()
        renderDebugLog(log.joinToString("\n"))
    }

    /**
     * Newest-first text + a snap-to-top so the latest entry is always
     * visible even if the user previously scrolled the panel. `post` lets
     * the text re-layout finish before we change the scroll offset.
     */
    private fun renderDebugLog(text: String) {
        debugLogText.text = text
        debugScrollView.post { debugScrollView.scrollTo(0, 0) }
    }

    /**
     * Tap shortcut for a voice label: runs the exact same dispatch path the
     * spoken word would, so behavior stays in sync regardless of input.
     * Logged to the debug panel so the user can see tap dispatches alongside
     * voice matches.
     */
    private fun runVoiceShortcut(label: String) {
        appLog("Tap shortcut: $label")
        lifecycleScope.launch {
            runCatching {
                Dispatch.dispatch(
                    label = label,
                    api = api,
                    globalMaxSpeed = globalMaxSpeed,
                    globalMinStroke = globalMinStroke,
                    globalMaxStroke = globalMaxStroke,
                    patternEngine = patternEngine,
                    humanizeEngine = humanizeEngine,
                )
            }.onFailure { appLog("Shortcut $label error: ${it.message}") }
        }
    }

    /**
     * Handle a tap on a PiP control-bar button. Arrives as a broadcast (PiP
     * windows can't deliver touches to our views), so unlike a screen tap it
     * never reaches onUserInteraction — we note activity here ourselves so a
     * PiP tap still pushes back the idle auto-stop. Routes through the same
     * Dispatch path as voice and the in-app shortcuts, so caps / ramp / pattern
     * cancellation all match. Beeps for the same audible confirmation the voice
     * path gives, since the icons are silent.
     */
    private fun onPipControl(label: String) {
        appLog("PiP control: $label")
        noteActivity()
        playAck()
        lifecycleScope.launch {
            runCatching {
                Dispatch.dispatch(
                    label = label,
                    api = api,
                    globalMaxSpeed = globalMaxSpeed,
                    globalMinStroke = globalMinStroke,
                    globalMaxStroke = globalMaxStroke,
                    patternEngine = patternEngine,
                    humanizeEngine = humanizeEngine,
                )
            }.onFailure { appLog("PiP $label error: ${it.message}") }
        }
    }

    /** Repaint the floating-window status line (speed + running/stopped). Safe
     *  to call before the views exist (early state emissions) and when not in
     *  PiP — it just writes to a hidden TextView. */
    private fun updatePipStatus() {
        if (!::pipStatusText.isInitialized) return
        val connected = api.connectionState.value == ConnectionState.CONNECTED
        val running = api.isRunning.value
        val speed = api.currentSpeed.value
        val (text, colorRes) = when {
            !connected -> "Disconnected" to R.color.text_secondary
            running -> "▶ $speed%" to R.color.status_connected
            else -> "■ Stopped" to R.color.emergency_red
        }
        pipStatusText.text = text
        pipStatusText.setTextColor(ContextCompat.getColor(this, colorRes))
    }

    private fun toast(s: String) = Toast.makeText(this, s, Toast.LENGTH_SHORT).show()

    /** Brief ack beep on a confirmed voice match. Safe to call on UI thread. */
    private fun playAck() {
        runCatching { ackTone?.startTone(ToneGenerator.TONE_PROP_ACK, 150) }
    }

    // ---------------------------------------------------------------
    // Tabs + Manual control (ported from the former ManualActivity).
    // ---------------------------------------------------------------

    private fun wireTabs() {
        modeTabs.addOnTabSelectedListener(object : TabLayout.OnTabSelectedListener {
            override fun onTabSelected(tab: TabLayout.Tab) {
                val voice = tab.position == 0
                voicePanel.visibility = if (voice) View.VISIBLE else View.GONE
                manualPanel.visibility = if (voice) View.GONE else View.VISIBLE
                // The big START/STOP pair only shows on Manual. Voice has its
                // own Begin / Stop shortcut buttons.
                startStopRow.visibility = if (voice) View.GONE else View.VISIBLE
            }
            override fun onTabUnselected(tab: TabLayout.Tab?) {}
            override fun onTabReselected(tab: TabLayout.Tab?) {}
        })
    }

    /**
     * Bind slider ranges to the global safety caps so the user physically
     * cannot drag past the configured limits, then restore the last-known
     * slider positions (clamped to the current band). Restoring does not
     * push to the device — the API call only happens on a touch commit.
     */
    private fun applyManualCaps() {
        val gMin = globalMinStroke.coerceIn(0, 100)
        val gMax = globalMaxStroke.coerceIn(gMin, 100)
        rebound(strokeMinSlider, gMin, gMax)
        rebound(strokeMaxSlider, gMin, gMax)
        // Speed slider stays in design space 0..100 and is scaled by
        // globalMaxSpeed before being sent to the device. The actual device
        // velocity is shown in the feedback line so the user can see both.
        rebound(speedSlider, 0, 100)
        val sMin = manualStrokeMin.coerceIn(gMin, gMax)
        val sMax = manualStrokeMax.coerceIn(sMin, gMax)
        val spd = manualSpeed.coerceIn(0, 100)
        // Update in-memory state so commits below (and the next persistence
        // write) reflect any clamping the global band forced on us.
        manualStrokeMin = sMin
        manualStrokeMax = sMax
        manualSpeed = spd
        setSliderValue(strokeMinSlider, strokeMinInput, sMin)
        setSliderValue(strokeMaxSlider, strokeMaxInput, sMax)
        setSliderValue(speedSlider, speedInput, spd)
    }

    private fun rebound(slider: SeekBar, min: Int, max: Int) {
        slider.min = 0
        slider.max = max
        slider.min = min
    }

    private fun setSliderValue(slider: SeekBar, input: EditText, value: Int) {
        slider.progress = value
        input.setText(value.toString())
    }

    private fun wireManualSliders() {
        wireManualSlider(strokeMinSlider, strokeMinInput) { value ->
            val min = value.coerceIn(globalMinStroke, globalMaxStroke)
            if (min != value) setSliderValue(strokeMinSlider, strokeMinInput, min)
            val max = strokeMaxSlider.progress.coerceAtLeast(min)
            if (max != strokeMaxSlider.progress) setSliderValue(strokeMaxSlider, strokeMaxInput, max)
            manualStrokeMin = min
            manualStrokeMax = max
            lifecycleScope.launch {
                prefs.setManualStrokeMin(min)
                prefs.setManualStrokeMax(max)
                val ok = api.setStrokeRange(min, max)
                if (ok) manualFeedbackText.text = "Stroke: $min-$max%"
            }
        }
        wireManualSlider(strokeMaxSlider, strokeMaxInput) { value ->
            val max = value.coerceIn(globalMinStroke, globalMaxStroke)
            if (max != value) setSliderValue(strokeMaxSlider, strokeMaxInput, max)
            val min = strokeMinSlider.progress.coerceAtMost(max)
            if (min != strokeMinSlider.progress) setSliderValue(strokeMinSlider, strokeMinInput, min)
            manualStrokeMin = min
            manualStrokeMax = max
            lifecycleScope.launch {
                prefs.setManualStrokeMin(min)
                prefs.setManualStrokeMax(max)
                val ok = api.setStrokeRange(min, max)
                if (ok) manualFeedbackText.text = "Stroke: $min-$max%"
            }
        }
        wireManualSlider(speedSlider, speedInput) { value ->
            val design = value.coerceIn(0, 100)
            if (design != value) setSliderValue(speedSlider, speedInput, design)
            manualSpeed = design
            // Scale design-space (0..100) to device velocity, capped at
            // globalMaxSpeed. Slider 100 = globalMaxSpeed at the device.
            val device = (design * globalMaxSpeed.coerceIn(1, 100) / 100).coerceIn(0, 100)
            lifecycleScope.launch {
                prefs.setManualSpeed(design)
                // Route through the humanize loop when it's on (same as the
                // voice path) so the Manual slider drives the drift centre; a
                // slide to 0 tears the loop down and stops.
                val ok = when {
                    device == 0 && humanizeEngine.isRunning -> { humanizeEngine.cancel(); api.stop() }
                    humanizeEngine.enabled && device > 0 -> humanizeEngine.start(device)
                    else -> api.setSpeed(device)
                }
                if (ok) manualFeedbackText.text = "Speed: $device% (design $design)"
            }
        }
    }

    private fun wireManualSlider(slider: SeekBar, input: EditText, onCommit: (Int) -> Unit) {
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

    // Tap-only pattern buttons. Each one runs a [PatternEngine] envelope,
    // bracketed by a stroke-range set so the pattern's position feels
    // distinct (rangeMin / rangeMax in user-percent of the global band).
    private data class TapPattern(
        val patternName: String,
        val displayName: String,
        val rangeMin: Int,
        val rangeMax: Int,
    )

    private val tapOnlyPatterns: Map<Int, TapPattern> = mapOf(
        // The old "pulse" metronome was renamed to "tick" so the "pulse"
        // name could move to the (formerly heartbeat) lub-dub envelope used
        // by the voice command.
        R.id.patternTick    to TapPattern("tick",    "Tick",    40, 60),
        // Surge and Breathe used to be voice commands but were dropped from
        // the spoken set for acoustic distinctness. They keep their envelopes
        // here as tap-only patterns with the same wide range Dispatch used.
        R.id.patternSurge   to TapPattern("surge",   "Surge",   0,  100),
        R.id.patternBreathe to TapPattern("breathe", "Breathe", 0,  100),
        R.id.patternTease   to TapPattern("tease",   "Tease",   0,  50),
        R.id.patternEdge    to TapPattern("edge",    "Edge",    70, 90),
        R.id.patternRush    to TapPattern("rush",    "Rush",    0,  100),
        R.id.patternThrob   to TapPattern("throb",   "Throb",   40, 70),
        R.id.patternBounce  to TapPattern("bounce",  "Bounce",  20, 80),
        R.id.patternClimax  to TapPattern("climax",  "Climax",  30, 90),
    )

    /**
     * Manual-grid buttons that route through [Dispatch] using a voice label.
     * Tapping them produces exactly the same device state as the voice match
     * would, including the full stroke band and globalMaxSpeed scaling.
     */
    private val voicePatternIds: Map<Int, String> = mapOf(
        R.id.patternThunder to "thunder",
        R.id.patternRipple  to "ripple",
        R.id.patternPulse   to "pulse",
    )

    private fun wireManualPatterns() {
        voicePatternIds.forEach { (id, label) ->
            findViewById<MaterialButton>(id).setOnClickListener { runVoicePattern(label) }
        }
        tapOnlyPatterns.forEach { (id, spec) ->
            findViewById<MaterialButton>(id).setOnClickListener { runTapPattern(spec) }
        }
    }

    private fun runVoicePattern(label: String) {
        lifecycleScope.launch {
            if (api.connectionState.value != ConnectionState.CONNECTED) {
                manualFeedbackText.text = "Not connected"
                return@launch
            }
            val result = runCatching {
                Dispatch.dispatch(
                    label = label,
                    api = api,
                    globalMaxSpeed = globalMaxSpeed,
                    globalMinStroke = globalMinStroke,
                    globalMaxStroke = globalMaxStroke,
                    patternEngine = patternEngine,
                    humanizeEngine = humanizeEngine,
                )
            }
            val ok = result.getOrDefault(false)
            val err = result.exceptionOrNull()
            manualFeedbackText.text = when {
                ok -> "Pattern: ${label.replaceFirstChar { it.uppercase() }}"
                err != null -> "Pattern $label error: ${err.message ?: err::class.simpleName}"
                else -> "Pattern $label failed (start refused?)"
            }
            if (err != null) appLog("Pattern $label threw: ${err.message}")
            else if (!ok) appLog("Pattern $label dispatch returned false.")
        }
    }

    private fun runTapPattern(spec: TapPattern) {
        val band = (globalMaxStroke - globalMinStroke).coerceAtLeast(0)
        val sMin = globalMinStroke + spec.rangeMin * band / 100
        val sMax = globalMinStroke + spec.rangeMax * band / 100
        val peak = globalMaxSpeed.coerceIn(1, 100)
        lifecycleScope.launch {
            if (api.connectionState.value != ConnectionState.CONNECTED) {
                manualFeedbackText.text = "Not connected"
                return@launch
            }
            // A pattern owns the device; stop humanize modulating first so it
            // doesn't fight the pattern's velocity / range writes.
            humanizeEngine.cancel()
            val strokeOk = api.setStrokeRange(sMin, sMax)
            if (!strokeOk) {
                manualFeedbackText.text = "Pattern ${spec.displayName}: stroke set failed"
                return@launch
            }
            val ok = patternEngine.start(spec.patternName, peak)
            manualFeedbackText.text = if (ok) {
                "Pattern: ${spec.displayName} (peak $peak%)"
            } else {
                "Pattern ${spec.displayName} failed (start refused?)"
            }
        }
    }
}
