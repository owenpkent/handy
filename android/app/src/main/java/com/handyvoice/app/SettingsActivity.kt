package com.handyvoice.app

import android.os.Bundle
import android.view.View
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import com.handyvoice.app.data.PreferencesManager
import com.handyvoice.app.databinding.ActivitySettingsBinding
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch

class SettingsActivity : AppCompatActivity() {
    
    private lateinit var binding: ActivitySettingsBinding
    private lateinit var prefsManager: PreferencesManager
    
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivitySettingsBinding.inflate(layoutInflater)
        setContentView(binding.root)
        
        prefsManager = PreferencesManager(this)
        
        loadSavedCredentials()
        setupSaveButton()
        setupVoiceEngineToggle()
    }
    
    private fun loadSavedCredentials() {
        lifecycleScope.launch {
            val apiKey = prefsManager.apiKey.first()
            val connectionKey = prefsManager.connectionKey.first()
            val deepgramKey = prefsManager.deepgramApiKey.first()
            val voiceEngine = prefsManager.voiceEngine.first()
            
            binding.apiKeyInput.setText(apiKey)
            binding.connectionKeyInput.setText(connectionKey)
            binding.deepgramKeyInput.setText(deepgramKey)
            
            when (voiceEngine) {
                "deepgram" -> {
                    binding.radioDeepgram.isChecked = true
                    binding.deepgramKeyLayout.visibility = View.VISIBLE
                }
                "android_stt" -> {
                    binding.radioAndroidStt.isChecked = true
                    binding.deepgramKeyLayout.visibility = View.GONE
                }
                else -> {
                    binding.radioVosk.isChecked = true
                    binding.deepgramKeyLayout.visibility = View.GONE
                }
            }
        }
    }
    
    private fun setupSaveButton() {
        binding.saveButton.setOnClickListener {
            val apiKey = binding.apiKeyInput.text.toString().trim()
            val connectionKey = binding.connectionKeyInput.text.toString().trim()
            
            if (apiKey.isBlank() || connectionKey.isBlank()) {
                Toast.makeText(this, "Both keys are required", Toast.LENGTH_SHORT).show()
                return@setOnClickListener
            }
            
            lifecycleScope.launch {
                prefsManager.saveCredentials(apiKey, connectionKey)
                Toast.makeText(this@SettingsActivity, "Settings saved", Toast.LENGTH_SHORT).show()
            }
        }
    }
    
    private fun setupVoiceEngineToggle() {
        binding.voiceEngineGroup.setOnCheckedChangeListener { _, checkedId ->
            when (checkedId) {
                R.id.radioDeepgram -> {
                    binding.deepgramKeyLayout.visibility = View.VISIBLE
                }
                else -> {
                    binding.deepgramKeyLayout.visibility = View.GONE
                }
            }
        }
        
        binding.saveVoiceButton.setOnClickListener {
            val engine = when {
                binding.radioDeepgram.isChecked -> "deepgram"
                binding.radioAndroidStt.isChecked -> "android_stt"
                else -> "vosk"
            }
            val deepgramKey = binding.deepgramKeyInput.text.toString().trim()
            
            if (engine == "deepgram" && deepgramKey.isBlank()) {
                Toast.makeText(this, "Deepgram API key required", Toast.LENGTH_SHORT).show()
                return@setOnClickListener
            }
            
            lifecycleScope.launch {
                prefsManager.saveVoiceEngine(engine)
                prefsManager.saveDeepgramApiKey(deepgramKey)
                Toast.makeText(this@SettingsActivity, "Voice settings saved", Toast.LENGTH_SHORT).show()
            }
        }
    }
}
