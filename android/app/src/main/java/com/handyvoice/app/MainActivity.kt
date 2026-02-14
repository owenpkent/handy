package com.handyvoice.app

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Bundle
import android.speech.tts.TextToSpeech
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import androidx.lifecycle.lifecycleScope
import com.handyvoice.app.api.ConnectionState
import com.handyvoice.app.api.HandyApiClient
import com.handyvoice.app.data.PreferencesManager
import com.handyvoice.app.databinding.ActivityMainBinding
import com.handyvoice.app.voice.VoiceCommand
import com.handyvoice.app.voice.VoiceRecognitionService
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import java.util.Locale

class MainActivity : AppCompatActivity(), TextToSpeech.OnInitListener {
    
    private lateinit var binding: ActivityMainBinding
    private lateinit var voiceService: VoiceRecognitionService
    private lateinit var handyClient: HandyApiClient
    private lateinit var prefsManager: PreferencesManager
    private var tts: TextToSpeech? = null
    
    private val requestPermissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { isGranted ->
        if (isGranted) {
            startVoiceRecognition()
        } else {
            Toast.makeText(this, R.string.error_voice_permission, Toast.LENGTH_LONG).show()
        }
    }
    
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityMainBinding.inflate(layoutInflater)
        setContentView(binding.root)
        
        // Initialize components
        voiceService = VoiceRecognitionService(this)
        handyClient = HandyApiClient()
        prefsManager = PreferencesManager(this)
        tts = TextToSpeech(this, this)
        
        setupUI()
        observeVoiceState()
        observeDeviceState()
        loadCredentialsAndConnect()
    }
    
    private fun setupUI() {
        // Mic button - toggle listening
        binding.micButton.setOnClickListener {
            if (voiceService.listeningState.value) {
                stopVoiceRecognition()
            } else {
                checkPermissionAndStartListening()
            }
        }
        
        // Settings button
        binding.settingsButton.setOnClickListener {
            startActivity(Intent(this, SettingsActivity::class.java))
        }
        
        // Emergency stop - always works
        binding.emergencyStopButton.setOnClickListener {
            emergencyStop()
        }
    }
    
    private fun observeVoiceState() {
        lifecycleScope.launch {
            voiceService.listeningState.collect { isListening ->
                updateListeningUI(isListening)
            }
        }
        
        lifecycleScope.launch {
            voiceService.partialResult.collect { partial ->
                if (partial.isNotBlank()) {
                    binding.transcriptText.text = partial
                }
            }
        }
        
        lifecycleScope.launch {
            voiceService.finalResult.collect { transcript ->
                if (transcript.isNotBlank()) {
                    binding.transcriptText.text = transcript
                }
            }
        }
        
        lifecycleScope.launch {
            voiceService.recognizedCommand.collect { command ->
                handleVoiceCommand(command)
            }
        }
        
        lifecycleScope.launch {
            voiceService.error.collect { error ->
                error?.let {
                    // Don't show transient errors in continuous mode
                    if (!voiceService.listeningState.value) {
                        Toast.makeText(this@MainActivity, it, Toast.LENGTH_SHORT).show()
                    }
                }
            }
        }
    }
    
    private fun observeDeviceState() {
        lifecycleScope.launch {
            handyClient.connectionState.collect { state ->
                updateConnectionUI(state)
            }
        }
        
        lifecycleScope.launch {
            handyClient.currentSpeed.collect { speed ->
                binding.speedValue.text = "$speed%"
            }
        }
        
        lifecycleScope.launch {
            handyClient.currentStroke.collect { stroke ->
                binding.strokeValue.text = "$stroke%"
            }
        }
        
        lifecycleScope.launch {
            handyClient.lastError.collect { error ->
                error?.let {
                    Toast.makeText(this@MainActivity, it, Toast.LENGTH_SHORT).show()
                }
            }
        }
    }
    
    private fun loadCredentialsAndConnect() {
        lifecycleScope.launch {
            val apiKey = prefsManager.apiKey.first()
            val connectionKey = prefsManager.connectionKey.first()
            
            if (apiKey.isNotBlank() && connectionKey.isNotBlank()) {
                handyClient.setCredentials(apiKey, connectionKey)
                handyClient.connect()
            }
        }
    }
    
    private fun updateListeningUI(isListening: Boolean) {
        if (isListening) {
            binding.micButton.setImageResource(android.R.drawable.ic_media_pause)
            binding.micLabel.setText(R.string.btn_stop_listening)
            binding.listeningLabel.setText(R.string.listening_for_commands)
            binding.statusIndicator.setBackgroundColor(
                ContextCompat.getColor(this, R.color.status_listening)
            )
        } else {
            binding.micButton.setImageResource(android.R.drawable.ic_btn_speak_now)
            binding.micLabel.setText(R.string.btn_start_listening)
            binding.transcriptText.text = ""
        }
    }
    
    private fun updateConnectionUI(state: ConnectionState) {
        when (state) {
            ConnectionState.DISCONNECTED -> {
                binding.statusText.setText(R.string.status_disconnected)
                binding.statusIndicator.setBackgroundColor(
                    ContextCompat.getColor(this, R.color.status_disconnected)
                )
            }
            ConnectionState.CONNECTING -> {
                binding.statusText.text = "Connecting..."
            }
            ConnectionState.CONNECTED -> {
                binding.statusText.setText(R.string.status_connected)
                binding.statusIndicator.setBackgroundColor(
                    ContextCompat.getColor(this, R.color.status_connected)
                )
            }
        }
    }
    
    private fun checkPermissionAndStartListening() {
        when {
            ContextCompat.checkSelfPermission(
                this, Manifest.permission.RECORD_AUDIO
            ) == PackageManager.PERMISSION_GRANTED -> {
                startVoiceRecognition()
            }
            else -> {
                requestPermissionLauncher.launch(Manifest.permission.RECORD_AUDIO)
            }
        }
    }
    
    private fun startVoiceRecognition() {
        voiceService.startListening(continuous = true)
    }
    
    private fun stopVoiceRecognition() {
        voiceService.stopListening()
    }
    
    private fun handleVoiceCommand(command: VoiceCommand) {
        lifecycleScope.launch {
            val feedback = when (command) {
                is VoiceCommand.Start -> {
                    if (handyClient.start()) {
                        getString(R.string.device_started)
                    } else null
                }
                is VoiceCommand.Stop -> {
                    if (handyClient.stop()) {
                        getString(R.string.device_stopped)
                    } else null
                }
                is VoiceCommand.Faster -> {
                    if (handyClient.adjustSpeed(10)) {
                        getString(R.string.speed_set_to, handyClient.currentSpeed.value)
                    } else null
                }
                is VoiceCommand.Slower -> {
                    if (handyClient.adjustSpeed(-10)) {
                        getString(R.string.speed_set_to, handyClient.currentSpeed.value)
                    } else null
                }
                is VoiceCommand.SetSpeed -> {
                    if (handyClient.setSpeed(command.percent)) {
                        getString(R.string.speed_set_to, command.percent)
                    } else null
                }
                is VoiceCommand.SetStroke -> {
                    if (handyClient.setStroke(command.percent)) {
                        getString(R.string.stroke_set_to, command.percent)
                    } else null
                }
                is VoiceCommand.Unknown -> null
            }
            
            feedback?.let {
                binding.lastCommandText.text = "✓ $it"
                speak(it)
            }
        }
    }
    
    private fun emergencyStop() {
        lifecycleScope.launch {
            handyClient.stop()
            voiceService.stopListening()
            speak(getString(R.string.device_stopped))
            binding.lastCommandText.text = "✓ ${getString(R.string.device_stopped)}"
        }
    }
    
    private fun speak(text: String) {
        tts?.speak(text, TextToSpeech.QUEUE_FLUSH, null, null)
    }
    
    override fun onInit(status: Int) {
        if (status == TextToSpeech.SUCCESS) {
            tts?.language = Locale.US
        }
    }
    
    override fun onResume() {
        super.onResume()
        // Reload credentials in case changed in settings
        loadCredentialsAndConnect()
    }
    
    override fun onDestroy() {
        super.onDestroy()
        voiceService.destroy()
        tts?.shutdown()
    }
}
