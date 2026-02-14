package com.handyvoice.app

import android.os.Bundle
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
    }
    
    private fun loadSavedCredentials() {
        lifecycleScope.launch {
            val apiKey = prefsManager.apiKey.first()
            val connectionKey = prefsManager.connectionKey.first()
            
            binding.apiKeyInput.setText(apiKey)
            binding.connectionKeyInput.setText(connectionKey)
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
                finish()
            }
        }
    }
}
