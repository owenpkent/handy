package com.handyvoice.v2.ui

import android.content.Intent
import android.os.Bundle
import android.widget.EditText
import android.widget.RadioButton
import android.widget.RadioGroup
import android.widget.SeekBar
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import com.google.android.material.button.MaterialButton
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
    private lateinit var thresholdSlider: SeekBar
    private lateinit var thresholdValue: TextView
    private lateinit var retrainButton: MaterialButton
    private lateinit var saveButton: MaterialButton

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_settings)
        prefs = PreferencesManager(applicationContext)
        store = TemplateStore(applicationContext.filesDir)

        keyInput = findViewById(R.id.connectionKeyInput)
        modeGroup = findViewById(R.id.modeGroup)
        modeButton = findViewById(R.id.modeButton)
        modeAlwaysOn = findViewById(R.id.modeAlwaysOn)
        globalMaxSlider = findViewById(R.id.globalMaxSlider)
        globalMaxValue = findViewById(R.id.globalMaxValue)
        thresholdSlider = findViewById(R.id.thresholdSlider)
        thresholdValue = findViewById(R.id.thresholdValue)
        retrainButton = findViewById(R.id.retrainButton)
        saveButton = findViewById(R.id.saveButton)

        globalMaxSlider.setOnSeekBarChangeListener(simpleListener { p ->
            globalMaxValue.text = p.coerceAtLeast(1).toString()
        })
        thresholdSlider.setOnSeekBarChangeListener(simpleListener { p ->
            thresholdValue.text = p.toString()
        })

        saveButton.setOnClickListener { onSave() }
        retrainButton.setOnClickListener { onRetrain() }

        lifecycleScope.launch { populate() }
    }

    private suspend fun populate() {
        val snap = prefs.snapshot()
        keyInput.setText(snap.connectionKey)
        if (snap.listeningMode == PreferencesManager.MODE_ALWAYS_ON) modeAlwaysOn.isChecked = true
        else modeButton.isChecked = true
        globalMaxSlider.progress = snap.globalMaxSpeed
        globalMaxValue.text = snap.globalMaxSpeed.toString()
        thresholdSlider.progress = snap.matchThreshold.toInt()
        thresholdValue.text = snap.matchThreshold.toInt().toString()
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
            prefs.setMatchThreshold(thresholdSlider.progress.toFloat())
            Toast.makeText(this@SettingsActivity, "Saved", Toast.LENGTH_SHORT).show()
            finish()
        }
    }

    private fun onRetrain() {
        lifecycleScope.launch {
            withContext(Dispatchers.IO) { store.clearAll() }
            prefs.setOnboarded(false)
            startActivity(Intent(this@SettingsActivity, TrainingActivity::class.java))
            finish()
        }
    }

    private fun simpleListener(onChange: (Int) -> Unit) = object : SeekBar.OnSeekBarChangeListener {
        override fun onProgressChanged(sb: SeekBar?, p: Int, fromUser: Boolean) = onChange(p)
        override fun onStartTrackingTouch(sb: SeekBar?) {}
        override fun onStopTrackingTouch(sb: SeekBar?) {}
    }
}
