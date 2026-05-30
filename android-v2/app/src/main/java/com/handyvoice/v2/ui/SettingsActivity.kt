package com.handyvoice.v2.ui

import android.content.Intent
import android.os.Bundle
import android.view.View
import android.widget.EditText
import android.widget.RadioButton
import android.widget.RadioGroup
import android.widget.SeekBar
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import com.google.android.material.button.MaterialButton
import com.google.android.material.materialswitch.MaterialSwitch
import com.handyvoice.v2.R
import com.handyvoice.v2.data.PreferencesManager
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
    private lateinit var saveButton: MaterialButton

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_settings)
        prefs = PreferencesManager(applicationContext)
        store = TemplateStore(applicationContext.filesDir)

        findViewById<MaterialButton>(R.id.homeButton).setOnClickListener { goHome() }

        keyInput = findViewById(R.id.connectionKeyInput)
        modeGroup = findViewById(R.id.modeGroup)
        modeButton = findViewById(R.id.modeButton)
        modeAlwaysOn = findViewById(R.id.modeAlwaysOn)
        globalMaxSlider = findViewById(R.id.globalMaxSlider)
        globalMaxValue = findViewById(R.id.globalMaxValue)
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
        saveButton = findViewById(R.id.saveButton)

        globalMaxSlider.setOnSeekBarChangeListener(simpleListener { p ->
            globalMaxValue.text = p.coerceAtLeast(1).toString()
        })
        // Stroke band: keep min <= max as the user drags either thumb.
        globalMinStrokeSlider.setOnSeekBarChangeListener(simpleListener { p ->
            globalMinStrokeValue.text = p.toString()
            if (p > globalMaxStrokeSlider.progress) {
                globalMaxStrokeSlider.progress = p
                globalMaxStrokeValue.text = p.toString()
            }
        })
        globalMaxStrokeSlider.setOnSeekBarChangeListener(simpleListener { p ->
            globalMaxStrokeValue.text = p.toString()
            if (p < globalMinStrokeSlider.progress) {
                globalMinStrokeSlider.progress = p
                globalMinStrokeValue.text = p.toString()
            }
        })
        thresholdSlider.setOnSeekBarChangeListener(simpleListener { p ->
            thresholdValue.text = p.toString()
        })
        // SeekBar min is 1 in the layout, so progress is already in minutes.
        idleTimeoutSlider.setOnSeekBarChangeListener(simpleListener { p ->
            idleTimeoutValue.text = p.toString()
        })

        saveButton.setOnClickListener { onSave() }
        retrainButton.setOnClickListener { onRetrain() }
        clearRecordingsButton.setOnClickListener { confirmClearAll() }

        lifecycleScope.launch { populate() }
    }

    private suspend fun populate() {
        val snap = prefs.snapshot()
        keyInput.setText(snap.connectionKey)
        if (snap.listeningMode == PreferencesManager.MODE_ALWAYS_ON) modeAlwaysOn.isChecked = true
        else modeButton.isChecked = true
        globalMaxSlider.progress = snap.globalMaxSpeed
        globalMaxValue.text = snap.globalMaxSpeed.toString()
        globalMinStrokeSlider.progress = snap.globalMinStroke
        globalMinStrokeValue.text = snap.globalMinStroke.toString()
        globalMaxStrokeSlider.progress = snap.globalMaxStroke
        globalMaxStrokeValue.text = snap.globalMaxStroke.toString()
        invertSwitch.isChecked = snap.invertStroke
        thresholdSlider.progress = snap.matchThreshold.toInt()
        thresholdValue.text = snap.matchThreshold.toInt().toString()
        idleTimeoutSlider.progress = snap.idleTimeoutMin
        idleTimeoutValue.text = snap.idleTimeoutMin.toString()
    }

    private fun onSave() {
        lifecycleScope.launch {
            prefs.setConnectionKey(keyInput.text.toString().trim())
            prefs.setListeningMode(
                if (modeGroup.checkedRadioButtonId == R.id.modeAlwaysOn)
                    PreferencesManager.MODE_ALWAYS_ON
                else
                    PreferencesManager.MODE_BUTTON
            )
            prefs.setGlobalMaxSpeed(globalMaxSlider.progress.coerceAtLeast(1))
            prefs.setGlobalMinStroke(globalMinStrokeSlider.progress.coerceIn(0, 100))
            prefs.setGlobalMaxStroke(globalMaxStrokeSlider.progress.coerceIn(0, 100))
            prefs.setInvertStroke(invertSwitch.isChecked)
            prefs.setMatchThreshold(thresholdSlider.progress.toFloat())
            prefs.setIdleTimeoutMin(idleTimeoutSlider.progress)
            Toast.makeText(this@SettingsActivity, "Saved", Toast.LENGTH_SHORT).show()
            finish()
        }
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

    private fun simpleListener(onChange: (Int) -> Unit) = object : SeekBar.OnSeekBarChangeListener {
        override fun onProgressChanged(sb: SeekBar?, p: Int, fromUser: Boolean) = onChange(p)
        override fun onStartTrackingTouch(sb: SeekBar?) {}
        override fun onStopTrackingTouch(sb: SeekBar?) {}
    }
}
