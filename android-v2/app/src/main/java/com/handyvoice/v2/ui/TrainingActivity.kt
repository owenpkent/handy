package com.handyvoice.v2.ui

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.media.AudioAttributes
import android.media.AudioFormat
import android.media.AudioManager
import android.media.AudioTrack
import android.media.ToneGenerator
import android.os.Bundle
import android.view.View
import android.view.WindowManager
import android.widget.PopupMenu
import android.widget.TextView
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import androidx.lifecycle.lifecycleScope
import com.google.android.material.button.MaterialButton
import com.handyvoice.v2.R
import androidx.appcompat.app.AlertDialog
import com.handyvoice.v2.audio.AudioCapture
import com.handyvoice.v2.audio.Vad
import com.handyvoice.v2.data.PreferencesManager
import com.handyvoice.v2.dispatch.Dispatch
import com.handyvoice.v2.voice.Dtw
import com.handyvoice.v2.voice.Mfcc
import com.handyvoice.v2.voice.TemplateStore
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import kotlin.coroutines.resume

/**
 * First-run onboarding: record N takes per command label.
 *
 * UX pattern inspired by Project Euphonia's training mode: one big prompt
 * card per label, a primary record button that morphs through Record ->
 * Stop -> Re-record, and explicit prev/next/skip controls so the user can
 * skip a label and come back.
 *
 * Per-take flow: tap Record -> 2.5 s capture -> auto-stop -> MFCC extracted
 * and persisted -> take counter advances -> on take 3, label is complete.
 * Play button replays the most recent capture so the user can verify.
 *
 * MFCCs are saved to disk immediately after each take so a crash mid-flow
 * keeps partial progress. After all takes are recorded (or skipped), the
 * Build button marks onboarding complete and returns to ControlActivity.
 */
class TrainingActivity : AppCompatActivity() {

    companion object {
        /** Optional intent extra (String[]): the exact labels to train this
         *  session. Absent/empty → the full command vocabulary. */
        const val EXTRA_LABELS = "com.handyvoice.v2.EXTRA_LABELS"

        private const val TAKES_PER_LABEL = 3
        // Capture window — must match ControlActivity.WINDOW_SECONDS so the
        // recognition path produces MFCC sequences the templates can align
        // with. Long enough that a slowly-spoken word ("thunder") isn't
        // truncated; short enough that "stop" doesn't pad with too much
        // silence.
        private const val WINDOW_SECONDS = 2.5
        // Pre-roll before the mic actually starts recording. Gives the
        // user a beat to focus once "Get ready..." appears so the first
        // syllable isn't clipped by reaction time.
        private const val PRE_ROLL_MS = 450L
        // Brief pauses inside the auto-recording session so the user can
        // breathe between utterances.
        private const val INTER_TAKE_DELAY_MS = 900L
        private const val INTER_LABEL_DELAY_MS = 1500L
        /**
         * After all takes of a label are captured, we score each take against
         * the others (DTW distance). If a take's mean distance to its siblings
         * exceeds the match threshold * this multiplier, we treat it as an
         * outlier and offer the user a one-tap re-record. 1.4 is loose enough
         * that mildly noisy takes don't trigger but a clear outlier does.
         */
        private const val OUTLIER_MULTIPLIER = 1.4
        private const val DTW_BAND_RATIO = 0.15

        private val ENCOURAGEMENTS = listOf(
            "Nice!",
            "Great job!",
            "Perfect!",
            "Got it!",
            "Sweet!",
            "Lovely!",
        )
    }

    private lateinit var prefs: PreferencesManager
    private lateinit var store: TemplateStore
    private lateinit var mfcc: Mfcc
    private lateinit var capture: AudioCapture
    private lateinit var vad: Vad
    /** Same ack beep as ControlActivity, confirms a captured take.
     *  Released in onDestroy. */
    private var ackTone: ToneGenerator? = null

    // Labels to train this session. Defaults to the full command vocabulary
    // (first-run onboarding / general re-record); Settings can launch us with
    // EXTRA_LABELS to train just a subset — e.g. only the reject/noise class.
    // Set in onCreate before loadProgress().
    private var labels: List<String> = Dispatch.LABELS
    private var labelIndex = 0
    private var takeCounts: IntArray = IntArray(labels.size)
    private var lastTake: ShortArray? = null
    private var isRecording = false
    /**
     * Auto-record session: once started, walks every label/take without the
     * user needing to tap Record again. Tapping the record button while
     * active cancels the session. Held alongside `isRecording` (the inner
     * capture window) so the UI can distinguish "between takes" from
     * "currently capturing".
     */
    private var sessionActive: Boolean = false
    private var sessionJob: Job? = null
    /**
     * Cached match threshold (from prefs) used to decide whether a take is
     * an outlier relative to its siblings. Re-read at the start of each
     * session so Settings changes take effect.
     */
    private var thresholdSnapshot: Float = PreferencesManager.DEFAULT_THRESHOLD

    // Views
    private lateinit var commandProgress: TextView
    private lateinit var promptText: TextView
    private lateinit var takeProgressText: TextView
    private lateinit var dots: List<View>
    private lateinit var peakBar: View
    private lateinit var recordButton: MaterialButton
    private lateinit var playButton: MaterialButton
    private lateinit var prevButton: MaterialButton
    private lateinit var nextButton: MaterialButton
    private lateinit var skipButton: MaterialButton
    private lateinit var finishButton: MaterialButton

    private val requestMic = registerForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
        if (granted) startSession() else showNoMicError()
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        // Recording sessions are hands-busy and quiet; don't let the screen
        // idle out mid-take. (The manifest's android:keepScreenOn on an
        // <activity> is silently ignored — this is the flag that works.)
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        setContentView(R.layout.activity_training)
        padForSystemBars()

        // Optional label subset (e.g. just the reject/noise class). Fall back
        // to the full command set when the extra is absent or empty.
        intent.getStringArrayExtra(EXTRA_LABELS)?.toList()?.takeIf { it.isNotEmpty() }?.let {
            labels = it
            takeCounts = IntArray(labels.size)
        }

        prefs = PreferencesManager(applicationContext)
        store = TemplateStore(applicationContext.filesDir)
        mfcc = Mfcc()
        capture = AudioCapture()
        vad = Vad()
        ackTone = runCatching { ToneGenerator(AudioManager.STREAM_MUSIC, 80) }.getOrNull()

        bindViews()
        loadProgress()
        render()
    }

    override fun onPause() {
        super.onPause()
        // Don't hold the mic in the background; cancel any in-flight session
        // so the user resumes from a clean state.
        cancelSession()
    }

    override fun onDestroy() {
        super.onDestroy()
        ackTone?.release()
        ackTone = null
    }

    private fun playAck() {
        runCatching { ackTone?.startTone(ToneGenerator.TONE_PROP_ACK, 150) }
    }

    private fun bindViews() {
        commandProgress = findViewById(R.id.commandProgressText)
        promptText = findViewById(R.id.promptText)
        takeProgressText = findViewById(R.id.takeProgressText)
        dots = listOf(findViewById(R.id.dot1), findViewById(R.id.dot2), findViewById(R.id.dot3))
        peakBar = findViewById(R.id.peakBar)
        recordButton = findViewById(R.id.recordButton)
        playButton = findViewById(R.id.playButton)
        prevButton = findViewById(R.id.prevButton)
        nextButton = findViewById(R.id.nextButton)
        skipButton = findViewById(R.id.skipButton)
        finishButton = findViewById(R.id.finishButton)

        recordButton.setOnClickListener { onRecordTapped() }
        playButton.setOnClickListener { onPlayTapped() }
        prevButton.setOnClickListener { advanceTo(labelIndex - 1) }
        nextButton.setOnClickListener { advanceTo(labelIndex + 1) }
        skipButton.setOnClickListener { advanceTo(labelIndex + 1) }
        finishButton.setOnClickListener { finishOnboarding() }
        findViewById<MaterialButton>(R.id.menuButton).setOnClickListener { v -> showTrainingMenu(v) }
    }

    /**
     * Training has its own menu because bailing out needs to flip onboarded=true
     * so ControlActivity's bootstrap gate doesn't immediately route the user
     * right back here. Without the flip, the user is trapped on this screen.
     */
    private fun showTrainingMenu(anchor: View) {
        val menu = PopupMenu(this, anchor)
        menu.menu.add(0, 1, 0, R.string.menu_voice)
        menu.menu.add(0, 3, 1, R.string.menu_settings)
        menu.setOnMenuItemClickListener { item ->
            val target: Class<*> = when (item.itemId) {
                1 -> ControlActivity::class.java
                3 -> SettingsActivity::class.java
                else -> return@setOnMenuItemClickListener false
            }
            lifecycleScope.launch {
                prefs.setOnboarded(true)
                val intent = Intent(this@TrainingActivity, target)
                if (target == ControlActivity::class.java) {
                    intent.addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP)
                }
                startActivity(intent)
                finish()
            }
            true
        }
        menu.show()
    }

    private fun loadProgress() {
        // Pick up any takes already on disk (e.g. retraining or resumed session).
        for ((i, label) in labels.withIndex()) {
            takeCounts[i] = store.takeCount(label)
        }
        // Start on the first label that still needs takes.
        labelIndex = labels.indices.firstOrNull { takeCounts[it] < TAKES_PER_LABEL } ?: 0
    }

    private fun render() {
        val label = labels[labelIndex]
        commandProgress.text = getString(R.string.training_command_progress, labelIndex + 1, labels.size)
        promptText.text = Dispatch.PROMPTS[label] ?: label

        val count = takeCounts[labelIndex]
        val activeTake = (count + 1).coerceAtMost(TAKES_PER_LABEL)
        takeProgressText.text = getString(R.string.training_take_progress, activeTake, TAKES_PER_LABEL)

        // Dots reflect the *active* take number, not completed takes: one dot
        // on take 1, two on take 2, three on take 3. The previous "filled =
        // already saved" rule made the first take show zero dots, which read
        // as "nothing's happening."
        for ((i, dot) in dots.withIndex()) {
            dot.setBackgroundResource(if (i < activeTake) R.drawable.dot_filled else R.drawable.dot_empty)
        }

        val complete = count >= TAKES_PER_LABEL
        recordButton.text = when {
            sessionActive -> getString(R.string.training_cancel)
            complete -> getString(R.string.training_rerecord)
            else -> getString(R.string.training_record)
        }
        recordButton.setBackgroundColor(
            ContextCompat.getColor(this, when {
                isRecording -> R.color.record_active
                sessionActive -> R.color.record_active
                complete -> R.color.record_done
                else -> R.color.record_idle
            })
        )

        // The only thing that has to be locked is the actual 2.5 s mic capture,
        // so navigation and Build templates stay available between takes even
        // while a session is running. Skip during inter-take pauses jumps the
        // session forward to the next label.
        playButton.isEnabled = lastTake != null && !isRecording
        prevButton.isEnabled = labelIndex > 0 && !isRecording
        nextButton.isEnabled = labelIndex < labels.size - 1 && !isRecording
        skipButton.isEnabled = !isRecording
        finishButton.isEnabled = !isRecording
    }

    private fun onRecordTapped() {
        if (sessionActive) {
            cancelSession()
            return
        }
        val granted = ContextCompat.checkSelfPermission(this, Manifest.permission.RECORD_AUDIO) ==
            PackageManager.PERMISSION_GRANTED
        if (!granted) {
            requestMic.launch(Manifest.permission.RECORD_AUDIO)
        } else {
            startSession()
        }
    }

    /**
     * Walk forwards through every (label, take) that still needs audio,
     * capturing each in turn with a brief breathing pause between takes.
     * If the user lands on a label that's already complete and taps Record,
     * we clear that label and refill it; subsequent complete labels are
     * left alone so a targeted re-record doesn't wipe the whole template
     * set.
     */
    private fun startSession() {
        if (sessionActive) return
        sessionActive = true
        val rerecordCurrent = takeCounts[labelIndex] >= TAKES_PER_LABEL
        render()
        sessionJob = lifecycleScope.launch {
            try {
                thresholdSnapshot = prefs.snapshot().matchThreshold
                if (rerecordCurrent) {
                    val label = labels[labelIndex]
                    withContext(Dispatchers.IO) { store.clearLabel(label) }
                    takeCounts[labelIndex] = 0
                    lastTake = null
                    render()
                }
                while (sessionActive && labelIndex < labels.size) {
                    if (takeCounts[labelIndex] >= TAKES_PER_LABEL) {
                        // Cross-val on the way out of this label. If the user
                        // chooses to re-record an outlier, the take count
                        // drops and we fall through to capture another take
                        // instead of advancing.
                        if (!validateLabelTakes(labels[labelIndex])) continue
                        if (labelIndex >= labels.size - 1) break
                        advanceTo(labelIndex + 1)
                        celebrateAdvance(labels[labelIndex])
                        delay(INTER_LABEL_DELAY_MS)
                        continue
                    }
                    val captured = captureOneTake()
                    if (!captured || !sessionActive) break
                    // Breathe between takes unless the label just hit full
                    // count (the next iteration runs cross-val + advance).
                    if (takeCounts[labelIndex] < TAKES_PER_LABEL) {
                        delay(INTER_TAKE_DELAY_MS)
                    }
                }
            } catch (_: CancellationException) {
                // User canceled; cleanup runs in finally.
            } finally {
                sessionActive = false
                isRecording = false
                peakBar.visibility = View.INVISIBLE
                render()
            }
        }
    }

    private fun cancelSession() {
        sessionActive = false
        sessionJob?.cancel()
        sessionJob = null
    }

    private fun celebrateAdvance(nextLabel: String) {
        val prompt = Dispatch.PROMPTS[nextLabel] ?: nextLabel
        val msg = "${ENCOURAGEMENTS.random()} Next: $prompt"
        Toast.makeText(this, msg, Toast.LENGTH_SHORT).show()
    }

    /**
     * Score every take against its siblings for [label] and, if the worst
     * one is far enough from the pack to look like an outlier, ask the user
     * to re-record it. Returns true if the label can move on, false if we
     * deleted a take and the session loop should capture another.
     */
    private suspend fun validateLabelTakes(label: String): Boolean {
        val takes = withContext(Dispatchers.IO) { store.loadTakes(label) }
        if (takes.size < 3) return true  // not enough to spot an outlier

        val meanDistances = DoubleArray(takes.size)
        var worstIdx = -1
        var worstDist = -1.0
        for (i in takes.indices) {
            var sum = 0.0
            var count = 0
            for (j in takes.indices) {
                if (i == j) continue
                sum += Dtw.distance(takes[i], takes[j], DTW_BAND_RATIO)
                count++
            }
            val mean = if (count > 0) sum / count else 0.0
            meanDistances[i] = mean
            if (mean > worstDist) {
                worstDist = mean
                worstIdx = i
            }
        }

        val limit = thresholdSnapshot * OUTLIER_MULTIPLIER
        if (worstDist <= limit || worstIdx < 0) return true

        val takeNumber = worstIdx + 1
        val rerecord = promptOutlierReRecord(label, takeNumber, worstDist)
        if (!rerecord) return true

        withContext(Dispatchers.IO) { store.deleteTake(label, worstIdx) }
        takeCounts[labelIndex] = store.takeCount(label)
        lastTake = null
        render()
        delay(INTER_TAKE_DELAY_MS)
        return false
    }

    private suspend fun promptOutlierReRecord(
        label: String,
        takeNumber: Int,
        distance: Double,
    ): Boolean = suspendCancellableCoroutine { cont ->
        val prompt = Dispatch.PROMPTS[label] ?: label
        val dialog = AlertDialog.Builder(this)
            .setTitle("Take $takeNumber of '$prompt' looks off")
            .setMessage(
                "Score ${"%.0f".format(distance)} — much higher than the other takes. " +
                    "Re-record this take so the engine can be more confident?"
            )
            .setPositiveButton("Re-record") { _, _ -> if (cont.isActive) cont.resume(true) }
            .setNegativeButton("Skip") { _, _ -> if (cont.isActive) cont.resume(false) }
            .setOnCancelListener { if (cont.isActive) cont.resume(false) }
            .setCancelable(true)
            .show()
        cont.invokeOnCancellation { runCatching { dialog.dismiss() } }
    }

    /**
     * Capture, validate, and persist a single take for the current label.
     * Returns false if the capture failed; in that case the session should
     * stop so the user can react instead of plowing through silent takes.
     */
    private suspend fun captureOneTake(): Boolean {
        val label = labels[labelIndex]
        // Pre-roll: surface "Get ready..." so the user can focus before the
        // mic actually starts. Without this the first ~200 ms of the capture
        // is reaction time and the start of the word ends up clipped.
        takeProgressText.text = "Get ready..."
        peakBar.visibility = View.INVISIBLE
        delay(PRE_ROLL_MS)
        if (!sessionActive) return false

        isRecording = true
        peakBar.scaleX = 0f
        peakBar.visibility = View.VISIBLE
        render()
        // render() resets takeProgressText; overwrite with the directive
        // once recording is actually open.
        takeProgressText.text = "Listening — speak now"

        val audio = withContext(Dispatchers.IO) {
            runCatching {
                capture.readWindow(WINDOW_SECONDS) { peak ->
                    val scale = (peak / 16000f).coerceIn(0f, 1f)
                    peakBar.post { peakBar.scaleX = scale }
                }
            }.getOrNull()
        }
        isRecording = false
        peakBar.visibility = View.INVISIBLE
        peakBar.scaleX = 0f

        if (audio == null) {
            showNoMicError()
            render()
            return false
        }

        lastTake = audio
        val peak = vad.peak(audio)
        if (peak < 800) {
            takeProgressText.text = "Very quiet — speak louder and re-record."
        }

        withContext(Dispatchers.IO) {
            val features = mfcc.extract(audio)
            if (features.isNotEmpty()) store.saveTake(label, features)
        }
        takeCounts[labelIndex] = store.takeCount(label)
        playAck()
        render()
        return true
    }

    private fun onPlayTapped() {
        val audio = lastTake ?: return
        lifecycleScope.launch(Dispatchers.IO) { playPcm(audio) }
    }

    private fun playPcm(samples: ShortArray) {
        val bufSize = AudioTrack.getMinBufferSize(
            AudioCapture.SAMPLE_RATE,
            AudioFormat.CHANNEL_OUT_MONO,
            AudioFormat.ENCODING_PCM_16BIT,
        ).coerceAtLeast(samples.size * 2)
        val track = AudioTrack.Builder()
            .setAudioAttributes(
                AudioAttributes.Builder()
                    .setUsage(AudioAttributes.USAGE_MEDIA)
                    .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH)
                    .build()
            )
            .setAudioFormat(
                AudioFormat.Builder()
                    .setEncoding(AudioFormat.ENCODING_PCM_16BIT)
                    .setSampleRate(AudioCapture.SAMPLE_RATE)
                    .setChannelMask(AudioFormat.CHANNEL_OUT_MONO)
                    .build()
            )
            .setBufferSizeInBytes(bufSize)
            .setTransferMode(AudioTrack.MODE_STATIC)
            .build()
        try {
            track.write(samples, 0, samples.size)
            track.play()
            // Wait for playback to finish (approx duration plus a little).
            val durationMs = (samples.size * 1000L / AudioCapture.SAMPLE_RATE) + 100
            Thread.sleep(durationMs)
        } finally {
            try { track.stop() } catch (_: IllegalStateException) {}
            track.release()
        }
    }

    private fun advanceTo(target: Int) {
        if (isRecording) return
        labelIndex = target.coerceIn(0, labels.size - 1)
        lastTake = null
        render()
    }

    private fun finishOnboarding() {
        cancelSession()
        lifecycleScope.launch {
            prefs.setOnboarded(true)
            finish()
        }
    }

    private fun showNoMicError() {
        takeProgressText.text = "Microphone permission required."
    }
}
