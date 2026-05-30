package com.handyvoice.v2.ui

import android.Manifest
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
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
import com.handyvoice.v2.R
import com.handyvoice.v2.api.ConnectionState
import com.handyvoice.v2.api.HandyV2ApiClient
import com.handyvoice.v2.audio.AudioCapture
import com.handyvoice.v2.audio.Vad
import com.handyvoice.v2.data.PreferencesManager
import com.handyvoice.v2.dispatch.Dispatch
import com.handyvoice.v2.dispatch.PatternEngine
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
    }

    private lateinit var prefs: PreferencesManager
    private lateinit var store: TemplateStore
    private lateinit var engine: MfccDtwEngine
    private lateinit var capture: AudioCapture
    private lateinit var vad: Vad
    private val api = HandyV2ApiClient()
    private val patternEngine: PatternEngine by lazy { PatternEngine(api, lifecycleScope) }
    /** Short ack beep on a successful voice match. Released in onDestroy. */
    private var ackTone: ToneGenerator? = null

    /**
     * Held only while the listening loop is running so a sleeping screen
     * can't suspend the CPU mid-utterance. Paired with FLAG_KEEP_SCREEN_ON
     * (which stops the screen sleeping in the first place); the lock is the
     * belt-and-suspenders for a deliberate power-button press. Released the
     * moment listening stops and again in onDestroy. Reference counting is
     * off so acquire/release stay idempotent against the isHeld guard.
     */
    private var wakeLock: PowerManager.WakeLock? = null

    // Views
    private lateinit var setupPanel: LinearLayout
    private lateinit var controlPanel: LinearLayout
    private lateinit var modeTabs: TabLayout
    private lateinit var voicePanel: LinearLayout
    private lateinit var manualPanel: LinearLayout
    private lateinit var connectionKeyInput: EditText
    private lateinit var connectButton: MaterialButton
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
    private lateinit var voiceThunderButton: MaterialButton
    private lateinit var voiceRippleButton: MaterialButton
    private lateinit var voicePulseButton: MaterialButton
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

        prefs = PreferencesManager(applicationContext)
        store = TemplateStore(applicationContext.filesDir)
        engine = MfccDtwEngine()
        capture = AudioCapture()
        vad = Vad()
        ackTone = runCatching { ToneGenerator(AudioManager.STREAM_MUSIC, 80) }.getOrNull()

        bindViews()
        observeApi()
        bootstrap()
    }

    private fun bindViews() {
        setupPanel = findViewById(R.id.setupPanel)
        controlPanel = findViewById(R.id.controlPanel)
        modeTabs = findViewById(R.id.modeTabs)
        voicePanel = findViewById(R.id.voicePanel)
        manualPanel = findViewById(R.id.manualPanel)
        connectionKeyInput = findViewById(R.id.connectionKeyInput)
        connectButton = findViewById(R.id.connectButton)
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
        voiceThunderButton = findViewById(R.id.voiceThunderButton)
        voiceRippleButton = findViewById(R.id.voiceRippleButton)
        voicePulseButton = findViewById(R.id.voicePulseButton)
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
        voiceThunderButton.setOnClickListener { runVoiceShortcut("thunder") }
        voiceRippleButton.setOnClickListener  { runVoiceShortcut("ripple") }
        voicePulseButton.setOnClickListener   { runVoiceShortcut("pulse") }

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
                renderDebugLog(merged.joinToString("\n"))
            }
        }
    }

    private suspend fun adjustSpeedRespectingLimits(designDelta: Int) {
        val cap = globalMaxSpeed.coerceIn(1, 100)
        val scaledDelta = designDelta * cap / 100
        val target = (api.currentSpeed.value + scaledDelta).coerceIn(0, cap)
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
            val loaded = withContext(Dispatchers.IO) { store.loadAll() }
            engine.loadInto(loaded)
            updateLastMatchForTemplates()
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

            val reloaded = withContext(Dispatchers.IO) { store.loadAll() }
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
     * Hold the device awake while the listening loop runs. Keeps the screen
     * on (so it never idles out from under an active listen) and grabs a
     * partial wake lock (so a deliberate power-off doesn't suspend the CPU
     * and kill capture). Both are dropped the instant listening stops.
     */
    private fun keepAwake(on: Boolean) {
        if (on) {
            window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
            if (wakeLock == null) {
                val pm = getSystemService(Context.POWER_SERVICE) as PowerManager
                wakeLock = pm.newWakeLock(
                    PowerManager.PARTIAL_WAKE_LOCK,
                    "HandyVoiceV2:listening",
                ).apply { setReferenceCounted(false) }
            }
            if (wakeLock?.isHeld != true) wakeLock?.acquire()
        } else {
            window.clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
            if (wakeLock?.isHeld == true) wakeLock?.release()
        }
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

    /**
     * In button mode the mic acts as a continuous-listen toggle: first tap
     * starts the VAD-driven loop (same one always-on uses), second tap
     * stops it. Always-on mode keeps its hands-free behaviour and the
     * button stays a status indicator.
     */
    private fun onMicTapped() {
        if (listeningMode == PreferencesManager.MODE_ALWAYS_ON) {
            return
        }
        if (alwaysOnJob?.isActive == true) {
            stopAlwaysOn()
            voiceModeLabel.text = "Tap mic to listen"
            micButton.text = "MIC"
            return
        }
        ensureMicThenRun {
            voiceModeLabel.text = "Listening — tap to stop"
            micButton.text = "ON"
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
