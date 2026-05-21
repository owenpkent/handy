package com.handyvoice.v2.ui

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.media.AudioAttributes
import android.media.AudioFormat
import android.media.AudioTrack
import android.os.Bundle
import android.view.View
import android.widget.PopupMenu
import android.widget.TextView
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import androidx.lifecycle.lifecycleScope
import com.google.android.material.button.MaterialButton
import com.handyvoice.v2.R
import com.handyvoice.v2.audio.AudioCapture
import com.handyvoice.v2.audio.Vad
import com.handyvoice.v2.data.PreferencesManager
import com.handyvoice.v2.dispatch.Dispatch
import com.handyvoice.v2.voice.Mfcc
import com.handyvoice.v2.voice.TemplateStore
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * First-run onboarding: record N takes per command label.
 *
 * UX pattern inspired by Project Euphonia's training mode: one big prompt
 * card per label, a primary record button that morphs through Record ->
 * Stop -> Re-record, and explicit prev/next/skip controls so the user can
 * skip a label and come back.
 *
 * Per-take flow: tap Record -> 1.5 s capture -> auto-stop -> MFCC extracted
 * and persisted -> take counter advances -> on take 3, label is complete.
 * Play button replays the most recent capture so the user can verify.
 *
 * MFCCs are saved to disk immediately after each take so a crash mid-flow
 * keeps partial progress. After all takes are recorded (or skipped), the
 * Build button marks onboarding complete and returns to ControlActivity.
 */
class TrainingActivity : AppCompatActivity() {

    companion object {
        private const val TAKES_PER_LABEL = 3
        private const val WINDOW_SECONDS = 1.5
    }

    private lateinit var prefs: PreferencesManager
    private lateinit var store: TemplateStore
    private lateinit var mfcc: Mfcc
    private lateinit var capture: AudioCapture
    private lateinit var vad: Vad

    private val labels = Dispatch.LABELS
    private var labelIndex = 0
    private val takeCounts: IntArray = IntArray(labels.size)
    private var lastTake: ShortArray? = null
    private var isRecording = false

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
        if (granted) startRecording() else showNoMicError()
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_training)

        prefs = PreferencesManager(applicationContext)
        store = TemplateStore(applicationContext.filesDir)
        mfcc = Mfcc()
        capture = AudioCapture()
        vad = Vad()

        bindViews()
        loadProgress()
        render()
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
        menu.menu.add(0, 2, 1, R.string.menu_manual)
        menu.menu.add(0, 3, 2, R.string.menu_settings)
        menu.setOnMenuItemClickListener { item ->
            val target: Class<*> = when (item.itemId) {
                1 -> ControlActivity::class.java
                2 -> ManualActivity::class.java
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

        for ((i, dot) in dots.withIndex()) {
            dot.setBackgroundResource(if (i < count) R.drawable.dot_filled else R.drawable.dot_empty)
        }

        val complete = count >= TAKES_PER_LABEL
        recordButton.text = when {
            isRecording -> getString(R.string.training_stop)
            complete -> getString(R.string.training_rerecord)
            else -> getString(R.string.training_record)
        }
        recordButton.setBackgroundColor(
            ContextCompat.getColor(this, when {
                isRecording -> R.color.record_active
                complete -> R.color.record_done
                else -> R.color.record_idle
            })
        )

        playButton.isEnabled = lastTake != null && !isRecording

        prevButton.isEnabled = labelIndex > 0 && !isRecording
        nextButton.isEnabled = labelIndex < labels.size - 1 && !isRecording
        skipButton.isEnabled = !isRecording

        // Enabled when every label has at least one take.
        finishButton.isEnabled = takeCounts.all { it >= 1 }
    }

    private fun onRecordTapped() {
        if (isRecording) return  // capture auto-stops; tapping during is ignored
        val granted = ContextCompat.checkSelfPermission(this, Manifest.permission.RECORD_AUDIO) ==
            PackageManager.PERMISSION_GRANTED
        if (!granted) {
            requestMic.launch(Manifest.permission.RECORD_AUDIO)
        } else {
            startRecording()
        }
    }

    private fun startRecording() {
        if (isRecording) return
        val label = labels[labelIndex]
        isRecording = true
        peakBar.visibility = View.VISIBLE
        render()

        lifecycleScope.launch {
            val audio = withContext(Dispatchers.IO) {
                runCatching { capture.readWindow(WINDOW_SECONDS) }.getOrNull()
            }
            isRecording = false
            peakBar.visibility = View.INVISIBLE

            if (audio == null) {
                showNoMicError()
                render()
                return@launch
            }

            lastTake = audio
            val peak = vad.peak(audio)
            if (peak < 800) {
                // Almost-silent take; warn but still save so the user can retry.
                takeProgressText.text = "Very quiet — speak louder and re-record."
            }

            withContext(Dispatchers.IO) {
                val features = mfcc.extract(audio)
                if (features.isNotEmpty()) store.saveTake(label, features)
            }
            takeCounts[labelIndex] = store.takeCount(label)
            render()

            if (takeCounts[labelIndex] >= TAKES_PER_LABEL && labelIndex < labels.size - 1) {
                delay(400)
                advanceTo(labelIndex + 1)
            }
        }
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
        lifecycleScope.launch {
            prefs.setOnboarded(true)
            finish()
        }
    }

    private fun showNoMicError() {
        takeProgressText.text = "Microphone permission required."
    }
}
