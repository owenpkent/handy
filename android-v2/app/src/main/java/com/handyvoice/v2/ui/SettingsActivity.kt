package com.handyvoice.v2.ui

import android.content.Intent
import android.os.Bundle
import android.view.View
import android.view.WindowManager
import android.widget.EditText
import android.widget.RadioButton
import android.widget.RadioGroup
import android.widget.SeekBar
import android.widget.TextView
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import com.google.android.material.button.MaterialButton
import com.google.android.material.materialswitch.MaterialSwitch
import com.handyvoice.v2.BuildConfig
import com.handyvoice.v2.R
import com.handyvoice.v2.data.PreferencesManager
import com.handyvoice.v2.update.Updater
import com.handyvoice.v2.voice.TemplateStore
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

class SettingsActivity : AppCompatActivity() {

    private lateinit var prefs: PreferencesManager
    private lateinit var store: TemplateStore

    private lateinit var keyInput: EditText
    private lateinit var modeGroup: RadioGroup
    private lateinit var modeButton: RadioButton
    private lateinit var modeAlwaysOn: RadioButton
    private lateinit var globalMaxSlider: SeekBar
    private lateinit var globalMaxValue: TextView
    private lateinit var humanizeSlider: SeekBar
    private lateinit var humanizeValue: TextView
    private lateinit var globalMinStrokeSlider: SeekBar
    private lateinit var globalMinStrokeValue: TextView
    private lateinit var globalMaxStrokeSlider: SeekBar
    private lateinit var globalMaxStrokeValue: TextView
    private lateinit var invertSwitch: MaterialSwitch
    private lateinit var thresholdSlider: SeekBar
    private lateinit var thresholdValue: TextView
    private lateinit var idleTimeoutSlider: SeekBar
    private lateinit var idleTimeoutValue: TextView
    private lateinit var retrainButton: MaterialButton
    private lateinit var clearRecordingsButton: MaterialButton

    // Guards the change-listeners so the initial populate() (which sets radio
    // / switch / slider state programmatically) doesn't write straight back to
    // DataStore. Flipped true once populate() has loaded the saved values.
    private var ready = false

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_settings)
        // Keep the screen on while the app is in the foreground, same as
        // ControlActivity and TrainingActivity. Honored only while visible,
        // so it costs nothing once the user navigates away.
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        prefs = PreferencesManager(applicationContext)
        store = TemplateStore(applicationContext.filesDir)

        findViewById<MaterialButton>(R.id.homeButton).setOnClickListener { goHome() }

        keyInput = findViewById(R.id.connectionKeyInput)
        modeGroup = findViewById(R.id.modeGroup)
        modeButton = findViewById(R.id.modeButton)
        modeAlwaysOn = findViewById(R.id.modeAlwaysOn)
        globalMaxSlider = findViewById(R.id.globalMaxSlider)
        globalMaxValue = findViewById(R.id.globalMaxValue)
        humanizeSlider = findViewById(R.id.humanizeSlider)
        humanizeValue = findViewById(R.id.humanizeValue)
        globalMinStrokeSlider = findViewById(R.id.globalMinStrokeSlider)
        globalMinStrokeValue = findViewById(R.id.globalMinStrokeValue)
        globalMaxStrokeSlider = findViewById(R.id.globalMaxStrokeSlider)
        globalMaxStrokeValue = findViewById(R.id.globalMaxStrokeValue)
        invertSwitch = findViewById(R.id.invertSwitch)
        thresholdSlider = findViewById(R.id.thresholdSlider)
        thresholdValue = findViewById(R.id.thresholdValue)
        idleTimeoutSlider = findViewById(R.id.idleTimeoutSlider)
        idleTimeoutValue = findViewById(R.id.idleTimeoutValue)
        retrainButton = findViewById(R.id.retrainButton)
        clearRecordingsButton = findViewById(R.id.clearRecordingsButton)
        // Each control auto-applies on change: sliders persist on release
        // (onStopTrackingTouch) so a drag doesn't spam DataStore; radio and
        // switch persist on toggle; the key persists on focus-loss and onPause.
        globalMaxSlider.setOnSeekBarChangeListener(sliderListener(
            onProgress = { p -> globalMaxValue.text = p.coerceAtLeast(1).toString() },
            onCommit = { persist { prefs.setGlobalMaxSpeed(globalMaxSlider.progress.coerceAtLeast(1)) } },
        ))
        humanizeSlider.setOnSeekBarChangeListener(sliderListener(
            onProgress = { p -> humanizeValue.text = p.toString() },
            onCommit = { persist { prefs.setHumanizeStrength(humanizeSlider.progress) } },
        ))
        // Stroke band: keep min <= max as the user drags either thumb, and
        // persist both on release since adjusting one thumb can move the other.
        globalMinStrokeSlider.setOnSeekBarChangeListener(sliderListener(
            onProgress = { p ->
                globalMinStrokeValue.text = p.toString()
                if (p > globalMaxStrokeSlider.progress) {
                    globalMaxStrokeSlider.progress = p
                    globalMaxStrokeValue.text = p.toString()
                }
            },
            onCommit = { persistStrokeBand() },
        ))
        globalMaxStrokeSlider.setOnSeekBarChangeListener(sliderListener(
            onProgress = { p ->
                globalMaxStrokeValue.text = p.toString()
                if (p < globalMinStrokeSlider.progress) {
                    globalMinStrokeSlider.progress = p
                    globalMinStrokeValue.text = p.toString()
                }
            },
            onCommit = { persistStrokeBand() },
        ))
        thresholdSlider.setOnSeekBarChangeListener(sliderListener(
            onProgress = { p -> thresholdValue.text = p.toString() },
            onCommit = { persist { prefs.setMatchThreshold(thresholdSlider.progress.toFloat()) } },
        ))
        // SeekBar min is 1 in the layout, so progress is already in minutes.
        idleTimeoutSlider.setOnSeekBarChangeListener(sliderListener(
            onProgress = { p -> idleTimeoutValue.text = p.toString() },
            onCommit = { persist { prefs.setIdleTimeoutMin(idleTimeoutSlider.progress) } },
        ))

        modeGroup.setOnCheckedChangeListener { _, checkedId ->
            if (!ready) return@setOnCheckedChangeListener
            persist {
                prefs.setListeningMode(
                    if (checkedId == R.id.modeAlwaysOn) PreferencesManager.MODE_ALWAYS_ON
                    else PreferencesManager.MODE_BUTTON
                )
            }
        }
        invertSwitch.setOnCheckedChangeListener { _, isChecked ->
            if (!ready) return@setOnCheckedChangeListener
            persist { prefs.setInvertStroke(isChecked) }
        }
        // Commit the typed key when the field loses focus (e.g. the user taps
        // a slider next). onPause is the backstop if focus is never lost.
        keyInput.setOnFocusChangeListener { _, hasFocus ->
            if (!hasFocus) persistKey()
        }

        retrainButton.setOnClickListener { onRetrain() }
        clearRecordingsButton.setOnClickListener { confirmClearAll() }

        findViewById<TextView>(R.id.versionText).text = "Version ${BuildConfig.VERSION_NAME}"
        findViewById<MaterialButton>(R.id.checkUpdatesButton).setOnClickListener {
            Updater(this).check(manual = true)
        }

        lifecycleScope.launch { populate() }
    }

    override fun onPause() {
        super.onPause()
        // Backstop for the connection key: persist whatever is typed when the
        // screen leaves the foreground, in case the field still had focus.
        persistKey()
    }

    private suspend fun populate() {
        val snap = prefs.snapshot()
        keyInput.setText(snap.connectionKey)
        if (snap.listeningMode == PreferencesManager.MODE_ALWAYS_ON) modeAlwaysOn.isChecked = true
        else modeButton.isChecked = true
        globalMaxSlider.progress = snap.globalMaxSpeed
        globalMaxValue.text = snap.globalMaxSpeed.toString()
        humanizeSlider.progress = snap.humanizeStrength
        humanizeValue.text = snap.humanizeStrength.toString()
        globalMinStrokeSlider.progress = snap.globalMinStroke
        globalMinStrokeValue.text = snap.globalMinStroke.toString()
        globalMaxStrokeSlider.progress = snap.globalMaxStroke
        globalMaxStrokeValue.text = snap.globalMaxStroke.toString()
        invertSwitch.isChecked = snap.invertStroke
        thresholdSlider.progress = snap.matchThreshold.toInt()
        thresholdValue.text = snap.matchThreshold.toInt().toString()
        idleTimeoutSlider.progress = snap.idleTimeoutMin
        idleTimeoutValue.text = snap.idleTimeoutMin.toString()
        // Saved values are in place; from here, listener changes are the user's.
        ready = true
    }

    /** Persist the global stroke band (both thumbs) after a band edit. */
    private fun persistStrokeBand() = persist {
        prefs.setGlobalMinStroke(globalMinStrokeSlider.progress.coerceIn(0, 100))
        prefs.setGlobalMaxStroke(globalMaxStrokeSlider.progress.coerceIn(0, 100))
    }

    private fun persistKey() {
        if (!ready) return
        persist { prefs.setConnectionKey(keyInput.text.toString().trim()) }
    }

    /** Fire-and-forget a DataStore write on the lifecycle scope. */
    private fun persist(block: suspend () -> Unit) {
        lifecycleScope.launch { block() }
    }

    /**
     * Open Training without touching saved takes. The user can re-record
     * individual labels via the per-label record button there, or use Skip
     * / Next to leave existing ones alone. Earlier this button silently
     * wiped everything, which was a footgun.
     */
    private fun onRetrain() {
        startActivity(Intent(this, TrainingActivity::class.java))
        finish()
    }

    /**
     * Confirm, then wipe every saved take and force onboarding. Routes back
     * to Training (the first-run flow) since the engine has nothing to match
     * against until at least one label is recorded.
     */
    private fun confirmClearAll() {
        AlertDialog.Builder(this)
            .setTitle("Clear all voice recordings?")
            .setMessage("Every saved take is deleted. You'll be taken to the recording screen to start over.")
            .setNegativeButton("Cancel", null)
            .setPositiveButton("Clear") { _, _ -> clearAll() }
            .show()
    }

    private fun clearAll() {
        lifecycleScope.launch {
            withContext(Dispatchers.IO) { store.clearAll() }
            prefs.setOnboarded(false)
            startActivity(Intent(this@SettingsActivity, TrainingActivity::class.java))
            finish()
        }
    }

    private fun goHome() {
        startActivity(
            Intent(this, ControlActivity::class.java)
                .addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP)
        )
        finish()
    }

    /**
     * SeekBar listener that updates the value label live ([onProgress]) and
     * persists the committed value when the user lifts their finger
     * ([onCommit]). Committing on release (not on every progress tick) keeps a
     * drag from firing a DataStore write per pixel. Programmatic
     * `progress = ...` in populate() never triggers onStopTrackingTouch, so it
     * can't write the loaded values straight back.
     */
    private fun sliderListener(onProgress: (Int) -> Unit, onCommit: (Int) -> Unit) =
        object : SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(sb: SeekBar?, p: Int, fromUser: Boolean) = onProgress(p)
            override fun onStartTrackingTouch(sb: SeekBar?) {}
            override fun onStopTrackingTouch(sb: SeekBar?) { onCommit(sb?.progress ?: 0) }
        }
}
