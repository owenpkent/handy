package com.handyvoice.v2.ui

import android.Manifest
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.content.res.ColorStateList
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
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import androidx.lifecycle.lifecycleScope
import com.google.android.material.button.MaterialButton
import com.handyvoice.v2.HandyVoiceApp
import com.handyvoice.v2.R
import com.handyvoice.v2.api.ConnectionState
import com.handyvoice.v2.api.HandyV2ApiClient
import com.handyvoice.v2.audio.AudioCapture
import com.handyvoice.v2.audio.Vad
import com.handyvoice.v2.data.PreferencesManager
import com.handyvoice.v2.dispatch.Dispatch
import com.handyvoice.v2.dispatch.PatternEngine
import com.handyvoice.v2.update.Updater
import com.handyvoice.v2.voice.MfccDtwEngine
import com.handyvoice.v2.voice.TemplateEngine
import com.handyvoice.v2.voice.TemplateStore
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
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
        // MUST match TrainingActivity.WINDOW_SECONDS — the matcher aligns
        // query MFCC frames against stored templates, and DTW's band ratio
        // can't bridge a 60-70% length mismatch. If you bump one, bump
        // both, then re-record samples.
        private const val WINDOW_SECONDS = 2.5
        private const val VAD_BLOCK_MS = 30
        private const val VAD_SPEECH_FRAMES_TO_TRIGGER = 4   // ~120 ms of voiced audio
        private const val COOLDOWN_MS = 1500L
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
    private lateinit var voiceDeeperButton: MaterialButton
    private lateinit var voiceShallowButton: MaterialButton
    private lateinit var voiceRandomButton: MaterialButton
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

    private var alwaysOnJob: Job? = null
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
        voiceDeeperButton = findViewById(R.id.voiceDeeperButton)
        voiceShallowButton = findViewById(R.id.voiceShallowButton)
        voiceRandomButton = findViewById(R.id.voiceRandomButton)
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

        connectButton.setOnClickListener { onConnectTapped() }
        // The gear goes straight to Settings — no intermediate menu. We're
        // already on the Voice/Manual home, so the menu's only other entry
        // was Settings anyway.
        settingsButton.setOnClickListener {
            startActivity(Intent(this, SettingsActivity::class.java))
        }
        micButton.setOnClickListener { onMicTapped() }
        startButton.setOnClickListener { lifecycleScope.launch { api.start() } }
        stopButton.setOnClickListener {
            lifecycleScope.launch {
                // STOP cancels any running pattern as well as the device — the
                // pattern engine would otherwise resume sending velocity on its
                // next tick.
                patternEngine.cancel()
                api.stop()
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
        voiceDeeperButton.setOnClickListener  { runVoiceShortcut("deeper") }
        voiceShallowButton.setOnClickListener { runVoiceShortcut("shallow") }
        voiceRandomButton.setOnClickListener  { runVoiceShortcut("random") }

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
                statusText.setText(R.string.status_connected)
                statusText.setTextColor(ContextCompat.getColor(this, R.color.status_connected))
                applyListeningMode()
            }
            ConnectionState.CONNECTING -> {
                statusText.text = "Connecting..."
                // Clear any red failure color from a previous attempt.
                statusText.setTextColor(ContextCompat.getColor(this, R.color.text_secondary))
                showConnecting(true)
            }
            ConnectionState.DISCONNECTED -> {
                showConnecting(false)
                controlPanel.visibility = View.GONE
                setupPanel.visibility = View.VISIBLE
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
            }
        }
    }

    private suspend fun adjustSpeedRespectingLimits(designDelta: Int) {
        val cap = globalMaxSpeed.coerceIn(1, 100)
        val scaledDelta = designDelta * cap / 100
        val target = (api.currentSpeed.value + scaledDelta).coerceIn(0, cap)
        // Smooth glide to the new speed (same ramp the voice path uses).
        api.rampSpeed(target)
    }

    private fun bootstrap() {
        lifecycleScope.launch {
            val snap = prefs.snapshot()
            globalMaxSpeed = snap.globalMaxSpeed
            globalMinStroke = snap.globalMinStroke
            globalMaxStroke = snap.globalMaxStroke
            threshold = snap.matchThreshold
            listeningMode = snap.listeningMode
            manualStrokeMin = snap.manualStrokeMin
            manualStrokeMax = snap.manualStrokeMax
            manualSpeed = snap.manualSpeed
            invertStroke = snap.invertStroke
            api.setInvertStroke(snap.invertStroke)
            api.setGlobalStrokeBand(snap.globalMinStroke, snap.globalMaxStroke)
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
            if (snap.connectionKey.isNotBlank() &&
                api.connectionState.value == ConnectionState.DISCONNECTED) {
                api.setConnectionKey(snap.connectionKey)
                api.connect()
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
            globalMaxSpeed = snap.globalMaxSpeed
            globalMinStroke = snap.globalMinStroke
            globalMaxStroke = snap.globalMaxStroke
            threshold = snap.matchThreshold
            listeningMode = snap.listeningMode
            manualStrokeMin = snap.manualStrokeMin
            manualStrokeMax = snap.manualStrokeMax
            manualSpeed = snap.manualSpeed
            invertStroke = snap.invertStroke
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
                    if (alwaysOnJob?.isActive != true) applyListeningMode()
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
        store.loadAll().filterKeys { it in Dispatch.LABELS }

    /** Show a hint in the match area that reflects current template state. */
    private fun updateLastMatchForTemplates() {
        val labels = engine.labels()
        lastMatchText.text = when {
            labels.isEmpty() -> "No voice samples yet — tap Re-record below."
            labels.size < Dispatch.LABELS.size -> "Trained: ${labels.size}/${Dispatch.LABELS.size} commands"
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
        ackTone?.release()
        ackTone = null
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
        lifecycleScope.launch {
            runCatching {
                Dispatch.dispatch(
                    label = "stop",
                    api = api,
                    globalMaxSpeed = globalMaxSpeed,
                    globalMinStroke = globalMinStroke,
                    globalMaxStroke = globalMaxStroke,
                    patternEngine = patternEngine,
                )
            }.onFailure { appLog("Idle-stop dispatch error: ${it.message}") }
        }
        stopAlwaysOn()
        if (listeningMode == PreferencesManager.MODE_ALWAYS_ON) {
            voiceModeLabel.text = "Paused (idle) — tap mic to resume"
        } else {
            voiceModeLabel.text = "Tap mic to listen"
        }
        setMicListening(false)
    }

    private fun onConnectTapped() {
        val key = connectionKeyInput.text.toString().trim()
        if (key.isBlank()) {
            toast("Connection key required.")
            return
        }
        // Flip the UI to its busy state immediately on tap, before the
        // suspend connect() even sets CONNECTING — closes the brief window
        // where a double-tap could fire two connect attempts.
        showConnecting(true)
        lifecycleScope.launch {
            prefs.setConnectionKey(key)
            api.setConnectionKey(key)
            val ok = api.connect()
            if (!ok) {
                // The DISCONNECTED state already paints the reason on the
                // status line; a toast makes the failure unmissable right
                // after the tap.
                toast(api.lastError.value ?: "Connection failed.")
            }
        }
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
    }

    /**
     * In button mode the mic acts as a continuous-listen toggle: first tap
     * starts the VAD-driven loop (same one always-on uses), second tap
     * stops it. Always-on mode normally self-manages, but after an idle
     * auto-stop the loop is down — a tap there brings it back without making
     * the user leave and re-enter the screen.
     */
    private fun onMicTapped() {
        if (listeningMode == PreferencesManager.MODE_ALWAYS_ON) {
            if (alwaysOnJob?.isActive != true) {
                ensureMicThenRun {
                    voiceModeLabel.text = "Always listening"
                    setMicListening(true)
                    startAlwaysOn()
                }
            }
            return
        }
        if (alwaysOnJob?.isActive == true) {
            stopAlwaysOn()
            voiceModeLabel.text = "Tap mic to listen"
            setMicListening(false)
            return
        }
        ensureMicThenRun {
            voiceModeLabel.text = "Listening — tap to stop"
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
                )
            }.onFailure { appLog("Dispatch error: ${it.message}") }
        }
    }

    private fun startAlwaysOn() {
        if (alwaysOnJob?.isActive == true) return
        keepAwake(true)
        armIdleTimeout()
        appLog("Always-on listening started.")
        alwaysOnJob = lifecycleScope.launch(Dispatchers.IO) {
            val blockSamples = AudioCapture.SAMPLE_RATE * VAD_BLOCK_MS / 1000
            val windowSamples = (AudioCapture.SAMPLE_RATE * WINDOW_SECONDS).toInt()
            val buffer = ShortArray(windowSamples)
            var written = 0
            var voicedRun = 0
            var lastFireAt = 0L
            // 0 = no snapshot pending. Set to the wall-clock time at which
            // we should grab the buffer once it contains the whole word.
            var pendingSnapshotAt = 0L

            capture.stream(blockSamples, shouldContinue = { alwaysOnJob?.isActive == true }) { block ->
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
                        lifecycleScope.launch { handleAudio(snapshot) }
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

    private fun stopAlwaysOn() {
        alwaysOnJob?.let {
            if (it.isActive) {
                it.cancel()
                appLog("Always-on stopped.")
            }
        }
        alwaysOnJob = null
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
                )
            }.onFailure { appLog("Shortcut $label error: ${it.message}") }
        }
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
                val ok = api.setSpeed(device)
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
