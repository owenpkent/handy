/**
 * Voice Recognition Handler
 * Uses Web Speech API for voice commands
 */

class VoiceController {
    constructor() {
        this.recognition = null;
        this.isListening = false;
        this.onCommandCallback = null;
        this.onStatusCallback = null;
        
        this.initRecognition();
    }

    /**
     * Initialize Web Speech API
     */
    initRecognition() {
        if (!('webkitSpeechRecognition' in window) && !('SpeechRecognition' in window)) {
            console.error('Web Speech API not supported');
            return false;
        }

        const SpeechRecognition = window.SpeechRecognition || window.webkitSpeechRecognition;
        this.recognition = new SpeechRecognition();
        
        this.recognition.continuous = true;
        this.recognition.interimResults = false;
        this.recognition.lang = 'en-US';
        this.recognition.maxAlternatives = 3;

        this.recognition.onstart = () => {
            this.isListening = true;
            this.updateStatus('Listening...', 'listening');
        };

        this.recognition.onend = () => {
            if (this.isListening) {
                // Restart if we're supposed to be listening
                this.recognition.start();
            } else {
                this.updateStatus('Inactive', 'inactive');
            }
        };

        this.recognition.onerror = (event) => {
            console.error('Speech recognition error:', event.error);
            
            if (event.error === 'no-speech') {
                this.updateStatus('No speech detected', 'error');
            } else if (event.error === 'not-allowed') {
                this.updateStatus('Microphone access denied', 'error');
                this.stop();
            } else {
                this.updateStatus(`Error: ${event.error}`, 'error');
            }
        };

        this.recognition.onresult = (event) => {
            const last = event.results.length - 1;
            const transcript = event.results[last][0].transcript.trim().toLowerCase();
            const confidence = event.results[last][0].confidence;

            console.log('Heard:', transcript, 'Confidence:', confidence);
            
            this.updateStatus(`Heard: "${transcript}"`, 'processing');
            this.processCommand(transcript);
        };

        return true;
    }

    /**
     * Start listening
     */
    start() {
        if (!this.recognition) {
            this.updateStatus('Voice recognition not available', 'error');
            return false;
        }

        try {
            this.isListening = true;
            this.recognition.start();
            return true;
        } catch (error) {
            console.error('Failed to start recognition:', error);
            return false;
        }
    }

    /**
     * Stop listening
     */
    stop() {
        if (this.recognition) {
            this.isListening = false;
            this.recognition.stop();
        }
    }

    /**
     * Process voice command
     */
    processCommand(transcript) {
        const command = this.parseCommand(transcript);
        
        if (command) {
            this.updateStatus(`Command: ${command.action}`, 'success');
            
            if (this.onCommandCallback) {
                this.onCommandCallback(command);
            }
        } else {
            this.updateStatus('Command not recognized', 'error');
        }
    }

    /**
     * Parse transcript into command
     */
    parseCommand(transcript) {
        const t = transcript.toLowerCase();

        // Start commands
        if (t.match(/\b(start|begin|go)\b/)) {
            return { action: 'start' };
        }

        // Stop commands
        if (t.match(/\b(stop|halt|pause|emergency stop)\b/)) {
            return { action: 'stop' };
        }

        // Speed up
        if (t.match(/\b(speed up|faster|increase speed)\b/)) {
            return { action: 'speed_up', value: 10 };
        }

        // Slow down
        if (t.match(/\b(slow down|slower|decrease speed)\b/)) {
            return { action: 'speed_down', value: 10 };
        }

        // Set specific speed
        const speedMatch = t.match(/\b(?:set speed to|speed)\s+(\d+)\b/);
        if (speedMatch) {
            return { action: 'set_speed', value: parseInt(speedMatch[1]) };
        }

        // Full speed
        if (t.match(/\b(full speed|maximum speed|max speed)\b/)) {
            return { action: 'set_speed', value: 100 };
        }

        // Half speed
        if (t.match(/\b(half speed|fifty percent)\b/)) {
            return { action: 'set_speed', value: 50 };
        }

        // Minimum speed
        if (t.match(/\b(minimum speed|min speed|slowest)\b/)) {
            return { action: 'set_speed', value: 10 };
        }

        // Increase stroke
        if (t.match(/\b(increase stroke|longer stroke|full stroke)\b/)) {
            return { action: 'increase_stroke' };
        }

        // Decrease stroke
        if (t.match(/\b(decrease stroke|shorter stroke|short stroke)\b/)) {
            return { action: 'decrease_stroke' };
        }

        // Full stroke range
        if (t.match(/\b(full stroke|maximum stroke)\b/)) {
            return { action: 'full_stroke' };
        }

        return null;
    }

    /**
     * Update status
     */
    updateStatus(message, type) {
        if (this.onStatusCallback) {
            this.onStatusCallback(message, type);
        }
    }

    /**
     * Set command callback
     */
    onCommand(callback) {
        this.onCommandCallback = callback;
    }

    /**
     * Set status callback
     */
    onStatus(callback) {
        this.onStatusCallback = callback;
    }

    /**
     * Check if voice recognition is supported
     */
    static isSupported() {
        return ('webkitSpeechRecognition' in window) || ('SpeechRecognition' in window);
    }
}

// Export for use in other scripts
window.VoiceController = VoiceController;
