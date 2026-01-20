/**
 * Main Application Logic
 * Coordinates UI, voice control, and API
 */

class HandyVoiceApp {
    constructor() {
        this.api = new HandyAPI();
        this.voiceWebSpeech = new VoiceController();
        this.voiceDeepgram = new DeepgramVoiceController();
        this.activeVoice = null;
        this.voiceEngine = 'deepgram'; // Default to Deepgram
        this.currentSpeed = 50;
        this.currentStrokeMin = 0;
        this.currentStrokeMax = 100;
        this.isDeviceRunning = false;
        
        this.initUI();
        this.loadCredentials();
        this.loadVoiceSettings();
        this.setupVoiceCallbacks();
        this.setupTextToSpeech();
    }

    /**
     * Initialize UI elements and event listeners
     */
    initUI() {
        // Credentials
        this.elements = {
            apiKey: document.getElementById('api-key'),
            connectionKey: document.getElementById('connection-key'),
            saveCredentials: document.getElementById('save-credentials'),
            
            // Voice settings
            voiceEngine: document.getElementById('voice-engine'),
            deepgramKey: document.getElementById('deepgram-key'),
            deepgramKeyGroup: document.getElementById('deepgram-key-group'),
            saveVoiceSettings: document.getElementById('save-voice-settings'),
            voiceEngineDisplay: document.getElementById('voice-engine-display'),
            
            // Status
            connectionStatus: document.getElementById('connection-status'),
            deviceStatus: document.getElementById('device-status'),
            voiceStatus: document.getElementById('voice-status'),
            speedStatus: document.getElementById('speed-status'),
            
            // Voice control
            toggleVoice: document.getElementById('toggle-voice'),
            voiceFeedback: document.getElementById('voice-feedback'),
            interimTranscript: document.getElementById('interim-transcript'),
            
            // Manual controls
            btnStart: document.getElementById('btn-start'),
            btnStop: document.getElementById('btn-stop'),
            speedSlider: document.getElementById('speed-slider'),
            speedDisplay: document.getElementById('speed-display'),
            strokeMinSlider: document.getElementById('stroke-min-slider'),
            strokeMinDisplay: document.getElementById('stroke-min-display'),
            strokeMaxSlider: document.getElementById('stroke-max-slider'),
            strokeMaxDisplay: document.getElementById('stroke-max-display')
        };

        // Event listeners
        this.elements.saveCredentials.addEventListener('click', () => this.saveCredentials());
        this.elements.saveVoiceSettings.addEventListener('click', () => this.saveVoiceSettings());
        this.elements.voiceEngine.addEventListener('change', (e) => this.handleVoiceEngineChange(e.target.value));
        this.elements.toggleVoice.addEventListener('click', () => this.toggleVoice());
        this.elements.btnStart.addEventListener('click', () => this.startDevice());
        this.elements.btnStop.addEventListener('click', () => this.stopDevice());
        
        this.elements.speedSlider.addEventListener('input', (e) => {
            this.currentSpeed = parseInt(e.target.value);
            this.elements.speedDisplay.textContent = this.currentSpeed;
            this.elements.speedSlider.setAttribute('aria-valuenow', this.currentSpeed);
        });
        
        this.elements.speedSlider.addEventListener('change', (e) => {
            this.setSpeed(parseInt(e.target.value));
        });

        this.elements.strokeMinSlider.addEventListener('input', (e) => {
            this.currentStrokeMin = parseInt(e.target.value);
            this.elements.strokeMinDisplay.textContent = this.currentStrokeMin;
            this.elements.strokeMinSlider.setAttribute('aria-valuenow', this.currentStrokeMin);
        });

        this.elements.strokeMinSlider.addEventListener('change', (e) => {
            this.updateStrokeRange();
        });

        this.elements.strokeMaxSlider.addEventListener('input', (e) => {
            this.currentStrokeMax = parseInt(e.target.value);
            this.elements.strokeMaxDisplay.textContent = this.currentStrokeMax;
            this.elements.strokeMaxSlider.setAttribute('aria-valuenow', this.currentStrokeMax);
        });

        this.elements.strokeMaxSlider.addEventListener('change', (e) => {
            this.updateStrokeRange();
        });

        // Check voice support
        if (!VoiceController.isSupported() && !DeepgramVoiceController.isSupported()) {
            this.elements.toggleVoice.disabled = true;
            this.elements.voiceFeedback.textContent = 'Voice recognition not supported in this browser.';
        }
        
        // Initialize voice engine display
        this.handleVoiceEngineChange(this.elements.voiceEngine.value);
    }

    /**
     * Setup text-to-speech for voice feedback
     */
    setupTextToSpeech() {
        this.speechSynthesis = window.speechSynthesis;
    }

    /**
     * Speak feedback
     */
    speak(text) {
        if (this.speechSynthesis) {
            this.speechSynthesis.cancel();
            const utterance = new SpeechSynthesisUtterance(text);
            utterance.rate = 1.2;
            utterance.pitch = 1.0;
            utterance.volume = 1.0;
            this.speechSynthesis.speak(utterance);
        }
    }

    /**
     * Load saved credentials
     */
    loadCredentials() {
        if (this.api.loadCredentials()) {
            this.elements.apiKey.value = this.api.apiKey;
            this.elements.connectionKey.value = this.api.connectionKey;
            this.updateConnectionStatus('Ready', 'success');
        }
    }

    /**
     * Save credentials
     */
    async saveCredentials() {
        const apiKey = this.elements.apiKey.value.trim();
        const connectionKey = this.elements.connectionKey.value.trim();

        if (!apiKey || !connectionKey) {
            alert('Please enter both API key and connection key');
            return;
        }

        this.api.setCredentials(apiKey, connectionKey);
        this.updateConnectionStatus('Connecting...', 'pending');

        try {
            await this.api.issueToken();
            const status = await this.api.getStatus();
            
            if (status.connected) {
                this.updateConnectionStatus('Connected', 'success');
                this.speak('Connected to device');
            } else {
                throw new Error('Failed to connect');
            }
        } catch (error) {
            console.error('Connection error:', error);
            this.updateConnectionStatus('Connection failed', 'error');
            alert(`Connection failed: ${error.message}`);
        }
    }

    /**
     * Load voice settings
     */
    loadVoiceSettings() {
        // Load saved engine preference
        const savedEngine = localStorage.getItem('voice_engine');
        if (savedEngine) {
            this.voiceEngine = savedEngine;
            this.elements.voiceEngine.value = savedEngine;
        }
        
        // Load Deepgram key
        if (this.voiceDeepgram.loadApiKey()) {
            this.elements.deepgramKey.value = this.voiceDeepgram.apiKey;
        }
        
        this.updateVoiceEngineDisplay();
    }
    
    /**
     * Save voice settings
     */
    saveVoiceSettings() {
        const engine = this.elements.voiceEngine.value;
        const deepgramKey = this.elements.deepgramKey.value.trim();
        
        if (engine === 'deepgram' && !deepgramKey) {
            alert('Please enter a Deepgram API key or switch to Web Speech API');
            return;
        }
        
        this.voiceEngine = engine;
        localStorage.setItem('voice_engine', engine);
        
        if (deepgramKey) {
            this.voiceDeepgram.setApiKey(deepgramKey);
        }
        
        this.updateVoiceEngineDisplay();
        this.speak('Voice settings saved');
        alert('Voice settings saved successfully!');
    }
    
    /**
     * Handle voice engine change
     */
    handleVoiceEngineChange(engine) {
        // Show/hide Deepgram key input
        if (engine === 'deepgram') {
            this.elements.deepgramKeyGroup.style.display = 'block';
        } else {
            this.elements.deepgramKeyGroup.style.display = 'none';
        }
    }
    
    /**
     * Update voice engine display
     */
    updateVoiceEngineDisplay() {
        const engineName = this.voiceEngine === 'deepgram' ? 'Deepgram AI' : 'Browser Speech API';
        this.elements.voiceEngineDisplay.textContent = `Using: ${engineName}`;
    }
    
    /**
     * Setup voice command callbacks
     */
    setupVoiceCallbacks() {
        // Setup callbacks for Web Speech API
        this.voiceWebSpeech.onCommand((command) => {
            this.handleVoiceCommand(command);
        });

        this.voiceWebSpeech.onStatus((message, type) => {
            this.elements.voiceStatus.textContent = message;
            this.elements.voiceFeedback.textContent = message;
        });
        
        // Setup callbacks for Deepgram
        this.voiceDeepgram.onCommand((command) => {
            this.handleVoiceCommand(command);
        });

        this.voiceDeepgram.onStatus((message, type) => {
            this.elements.voiceStatus.textContent = message;
            this.elements.voiceFeedback.textContent = message;
        });
        
        this.voiceDeepgram.onTranscript((transcript, isFinal) => {
            if (!isFinal) {
                // Show interim results
                this.elements.interimTranscript.textContent = transcript;
            } else {
                // Clear interim when final
                this.elements.interimTranscript.textContent = '';
            }
        });
    }

    /**
     * Handle voice command
     */
    async handleVoiceCommand(command) {
        console.log('Executing command:', command);

        try {
            switch (command.action) {
                case 'start':
                    await this.startDevice();
                    this.speak('Starting');
                    break;

                case 'stop':
                    await this.stopDevice();
                    this.speak('Stopping');
                    break;

                case 'speed_up':
                    const newSpeedUp = Math.min(100, this.currentSpeed + command.value);
                    await this.setSpeed(newSpeedUp);
                    this.speak(`Speed ${newSpeedUp} percent`);
                    break;

                case 'speed_down':
                    const newSpeedDown = Math.max(0, this.currentSpeed - command.value);
                    await this.setSpeed(newSpeedDown);
                    this.speak(`Speed ${newSpeedDown} percent`);
                    break;

                case 'set_speed':
                    await this.setSpeed(command.value);
                    this.speak(`Speed set to ${command.value} percent`);
                    break;

                case 'increase_stroke':
                    await this.adjustStroke(10);
                    this.speak('Stroke increased');
                    break;

                case 'decrease_stroke':
                    await this.adjustStroke(-10);
                    this.speak('Stroke decreased');
                    break;

                case 'full_stroke':
                    this.currentStrokeMin = 0;
                    this.currentStrokeMax = 100;
                    await this.updateStrokeRange();
                    this.speak('Full stroke range');
                    break;

                default:
                    console.warn('Unknown command:', command);
            }
        } catch (error) {
            console.error('Command execution error:', error);
            this.speak('Command failed');
        }
    }

    /**
     * Toggle voice recognition
     */
    async toggleVoice() {
        // Determine which engine to use
        if (this.voiceEngine === 'deepgram') {
            this.activeVoice = this.voiceDeepgram;
        } else {
            this.activeVoice = this.voiceWebSpeech;
        }
        
        if (this.activeVoice.isListening) {
            this.activeVoice.stop();
            this.elements.toggleVoice.textContent = '🎤 Start Listening';
            this.elements.toggleVoice.setAttribute('aria-pressed', 'false');
            this.elements.interimTranscript.textContent = '';
            this.speak('Voice control off');
        } else {
            const started = await this.activeVoice.start();
            if (started) {
                this.elements.toggleVoice.textContent = '🔴 Stop Listening';
                this.elements.toggleVoice.setAttribute('aria-pressed', 'true');
                this.speak('Voice control on');
            } else {
                alert('Failed to start voice recognition. Check your settings and microphone permissions.');
            }
        }
    }

    /**
     * Start device
     */
    async startDevice() {
        try {
            await this.api.start();
            this.isDeviceRunning = true;
            this.updateDeviceStatus('Running', 'success');
        } catch (error) {
            console.error('Start error:', error);
            alert(`Failed to start: ${error.message}`);
        }
    }

    /**
     * Stop device
     */
    async stopDevice() {
        try {
            await this.api.stop();
            this.isDeviceRunning = false;
            this.updateDeviceStatus('Stopped', 'stopped');
        } catch (error) {
            console.error('Stop error:', error);
            alert(`Failed to stop: ${error.message}`);
        }
    }

    /**
     * Set speed
     */
    async setSpeed(speed) {
        try {
            this.currentSpeed = speed;
            this.elements.speedSlider.value = speed;
            this.elements.speedDisplay.textContent = speed;
            this.elements.speedStatus.textContent = `${speed}%`;
            
            await this.api.setSpeed(speed);
        } catch (error) {
            console.error('Speed error:', error);
        }
    }

    /**
     * Update stroke range
     */
    async updateStrokeRange() {
        try {
            const min = this.currentStrokeMin / 100;
            const max = this.currentStrokeMax / 100;
            
            await this.api.setStrokeRange(min, max);
        } catch (error) {
            console.error('Stroke range error:', error);
        }
    }

    /**
     * Adjust stroke range
     */
    async adjustStroke(delta) {
        this.currentStrokeMin = Math.max(0, this.currentStrokeMin - delta);
        this.currentStrokeMax = Math.min(100, this.currentStrokeMax + delta);
        
        this.elements.strokeMinSlider.value = this.currentStrokeMin;
        this.elements.strokeMinDisplay.textContent = this.currentStrokeMin;
        this.elements.strokeMaxSlider.value = this.currentStrokeMax;
        this.elements.strokeMaxDisplay.textContent = this.currentStrokeMax;
        
        await this.updateStrokeRange();
    }

    /**
     * Update connection status
     */
    updateConnectionStatus(status, type) {
        this.elements.connectionStatus.textContent = status;
        this.elements.connectionStatus.className = `status-value status-${type}`;
    }

    /**
     * Update device status
     */
    updateDeviceStatus(status, type) {
        this.elements.deviceStatus.textContent = status;
        this.elements.deviceStatus.className = `status-value status-${type}`;
    }
}

// Initialize app when DOM is ready
document.addEventListener('DOMContentLoaded', () => {
    window.app = new HandyVoiceApp();
});
