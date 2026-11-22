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
import com.handyvoice.v2.dispatch.Dispatch
import com.handyvoice.v2.service.BatteryOptimization
import com.handyvoice.v2.update.Updater
import com.handyvoice.v2.voice.TemplateStore
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

class SettingsActivity : AppCompatActivity() {

    private lateinit var prefs: PreferencesManager
    private lateinit var store: TemplateStore

    private lateinit var keyInput: EditText
    private lateinit var deviceTypeGroup: RadioGroup
    private lateinit var deviceHandy: RadioButton
    private lateinit var deviceLovense: RadioButton
    private lateinit var handyConnectionGroup: View
    private lateinit var lovenseGroup: View
    private lateinit var lovenseToyText: TextView
    private lateinit var forgetToyButton: MaterialButton
    private lateinit var batteryOptButton: MaterialButton
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
    private lateinit var recordNoiseButton: MaterialButton
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
        deviceTypeGroup = findViewById(R.id.deviceTypeGroup)
        deviceHandy = findViewById(R.id.deviceHandy)
        deviceLovense = findViewById(R.id.deviceLovense)
        handyConnectionGroup = findViewById(R.id.handyConnectionGroup)
        lovenseGroup = findViewById(R.id.lovenseGroup)
        lovenseToyText = findViewById(R.id.lovenseToyText)
        forgetToyButton = findViewById(R.id.forgetToyButton)
        batteryOptButton = findViewById(R.id.batteryOptButton)
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
        recordNoiseButton = findViewById(R.id.recordNoiseButton)
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
        deviceTypeGroup.setOnCheckedChangeListener { _, checkedId ->
            val lovense = checkedId == R.id.deviceLovense
            showDeviceGroup(lovense)
            if (!ready) return@setOnCheckedChangeListener
            persist {
                prefs.setDeviceType(
                    if (lovense) PreferencesManager.DEVICE_LOVENSE else PreferencesManager.DEVICE_HANDY
                )
                // Each device keeps its own max speed; show the new device's.
                val max = prefs.snapshot().globalMaxSpeed
                globalMaxSlider.progress = max
                globalMaxValue.text = max.toString()
            }
        }
        forgetToyButton.setOnClickListener {
            persist {
                prefs.clearLovenseToy()
                lovenseToyText.text = NO_TOY
            }
        }
        findViewById<MaterialButton>(R.id.toyLabButton).setOnClickListener {
            startActivity(Intent(this, ToyLabActivity::class.java))
        }
        batteryOptButton.setOnClickListener {
            if (!BatteryOptimization.isExempt(this)) {
                runCatching { startActivity(BatteryOptimization.requestIntent(this)) }
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
        recordNoiseButton.setOnClickListener { onRecordNoise() }
        clearRecordingsButton.setOnClickListener { confirmClearAll() }

        findViewById<TextView>(R.id.versionText).text = "Version ${BuildConfig.VERSION_NAME}"
        findViewById<MaterialButton>(R.id.checkUpdatesButton).setOnClickListener {
            Updater(this).check(manual = true)
        }

        lifecycleScope.launch { populate() }
    }

    override fun onResume() {
        super.onResume()
        refreshBatteryOptButton()
    }

    /** Lovense-only row: shows whether the app is already exempt from battery optimization. */
    private fun refreshBatteryOptButton() {
        if (!::batteryOptButton.isInitialized) return
        val exempt = BatteryOptimization.isExempt(this)
        batteryOptButton.text = if (exempt) "Background use allowed" else "Allow background use"
        batteryOptButton.isEnabled = !exempt
    }

    override fun onPause() {
        super.onPause()
        // Backstop for the connection key: persist whatever is typed when the
        // screen leaves the foreground, in case the field still had focus.
        persistKey()
    }

    private companion object {
        const val NO_TOY = "No toy paired yet"
    }

    private suspend fun populate() {
        val snap = prefs.snapshot()
        keyInput.setText(snap.connectionKey)
        if (snap.isLovense) deviceLovense.isChecked = true else deviceHandy.isChecked = true
        showDeviceGroup(snap.isLovense)
        lovenseToyText.text = if (snap.lovenseAddress.isBlank()) NO_TOY
        else "Paired: ${snap.lovenseName.ifBlank { snap.lovenseAddress }}"
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

    /** Handy shows the connection key; Lovense shows the paired toy. */
    private fun showDeviceGroup(lovense: Boolean) {
        handyConnectionGroup.visibility = if (lovense) View.GONE else View.VISIBLE
        lovenseGroup.visibility = if (lovense) View.VISIBLE else View.GONE
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
     * Train only the reject/noise class: launch Training scoped to
     * [Dispatch.NOISE_LABEL] so the user records the device's running sound.
     * Any later window that matches this class best is dropped instead of
     * dispatched — the fix for the motor noise being heard as "stop". Best
     * done with the device already running so the captured takes are the real
     * noise (see the hint under the button).
     */
    private fun onRecordNoise() {
        startActivity(
            Intent(this, TrainingActivity::class.java)
                .putExtra(TrainingActivity.EXTRA_LABELS, arrayOf(Dispatch.NOISE_LABEL))
        )
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
