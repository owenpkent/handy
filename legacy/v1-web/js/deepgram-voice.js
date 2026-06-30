/**
 * Deepgram Voice Recognition Handler
 * Superior accuracy for non-standard voices and speech patterns
 */

class DeepgramVoiceController {
    constructor() {
        this.socket = null;
        this.mediaRecorder = null;
        this.stream = null;
        this.isListening = false;
        this.apiKey = null;
        this.onCommandCallback = null;
        this.onStatusCallback = null;
        this.onTranscriptCallback = null;
    }

    /**
     * Set Deepgram API key
     */
    setApiKey(apiKey) {
        this.apiKey = apiKey;
        localStorage.setItem('deepgram_api_key', apiKey);
    }

    /**
     * Load API key from storage
     */
    loadApiKey() {
        this.apiKey = localStorage.getItem('deepgram_api_key');
        return !!this.apiKey;
    }

    /**
     * Clear API key
     */
    clearApiKey() {
        this.apiKey = null;
        localStorage.removeItem('deepgram_api_key');
    }

    /**
     * Start listening with Deepgram
     */
    async start() {
        if (!this.apiKey) {
            this.updateStatus('Deepgram API key not set', 'error');
            return false;
        }

        try {
            // Get user microphone
            this.stream = await navigator.mediaDevices.getUserMedia({ audio: true });
            
            // Create media recorder
            this.mediaRecorder = new MediaRecorder(this.stream, {
                mimeType: 'audio/webm'
            });

            // Connect to Deepgram
            this.connectToDeepgram();

            // Handle data available from microphone
            this.mediaRecorder.addEventListener('dataavailable', (event) => {
                if (event.data.size > 0 && this.socket && this.socket.readyState === WebSocket.OPEN) {
                    this.socket.send(event.data);
                }
            });

            this.isListening = true;
            this.updateStatus('Listening with Deepgram...', 'listening');
            
            return true;
        } catch (error) {
            console.error('Failed to start Deepgram:', error);
            this.updateStatus(`Error: ${error.message}`, 'error');
            return false;
        }
    }

    /**
     * Connect to Deepgram WebSocket
     */
    connectToDeepgram() {
        // Build WebSocket URL with parameters for better accuracy
        const params = new URLSearchParams({
            punctuate: 'true',
            interim_results: 'true',
            endpointing: '300',  // ms of silence before finalizing
            vad_events: 'true',  // Voice activity detection
            model: 'nova-2',     // Latest model
            language: 'en-US'
        });

        const wsUrl = `wss://api.deepgram.com/v1/listen?${params.toString()}`;
        
        this.socket = new WebSocket(wsUrl, ['token', this.apiKey]);

        this.socket.onopen = () => {
            console.log('Deepgram connection opened');
            this.updateStatus('Connected to Deepgram', 'connected');
            
            // Start recording once connected
            this.mediaRecorder.start(250); // Send data every 250ms
        };

        this.socket.onmessage = (message) => {
            this.handleDeepgramResponse(message);
        };

        this.socket.onclose = () => {
            console.log('Deepgram connection closed');
            if (this.isListening) {
                // Attempt to reconnect if we're supposed to be listening
                setTimeout(() => {
                    if (this.isListening) {
                        this.connectToDeepgram();
                    }
                }, 1000);
            }
        };

        this.socket.onerror = (error) => {
            console.error('Deepgram WebSocket error:', error);
            this.updateStatus('Connection error', 'error');
        };
    }

    /**
     * Handle response from Deepgram
     */
    handleDeepgramResponse(message) {
        try {
            const data = JSON.parse(message.data);
            
            // Handle different message types
            if (data.type === 'Results') {
                const transcript = data.channel?.alternatives?.[0]?.transcript;
                const isFinal = data.is_final;
                const confidence = data.channel?.alternatives?.[0]?.confidence;

                if (transcript) {
                    // Show interim results for feedback
                    if (!isFinal && this.onTranscriptCallback) {
                        this.onTranscriptCallback(transcript, false);
                    }

                    // Process final transcripts
                    if (isFinal && transcript.trim()) {
                        console.log('Deepgram final transcript:', transcript, 'Confidence:', confidence);
                        
                        if (this.onTranscriptCallback) {
                            this.onTranscriptCallback(transcript, true);
                        }
                        
                        this.updateStatus(`Heard: "${transcript}"`, 'processing');
                        this.processCommand(transcript);
                    }
                }
            } else if (data.type === 'Metadata') {
                console.log('Deepgram metadata:', data);
            } else if (data.type === 'SpeechStarted') {
                this.updateStatus('Speech detected...', 'detecting');
            }
        } catch (error) {
            console.error('Error parsing Deepgram response:', error);
        }
    }

    /**
     * Stop listening
     */
    stop() {
        this.isListening = false;

        if (this.mediaRecorder && this.mediaRecorder.state !== 'inactive') {
            this.mediaRecorder.stop();
        }

        if (this.socket && this.socket.readyState === WebSocket.OPEN) {
            this.socket.close();
        }

        if (this.stream) {
            this.stream.getTracks().forEach(track => track.stop());
            this.stream = null;
        }

        this.updateStatus('Inactive', 'inactive');
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
        if (t.match(/\b(start|begin|go|turn on)\b/)) {
            return { action: 'start' };
        }

        // Stop commands
        if (t.match(/\b(stop|halt|pause|emergency stop|turn off)\b/)) {
            return { action: 'stop' };
        }

        // Speed up
        if (t.match(/\b(speed up|faster|increase speed|go faster)\b/)) {
            return { action: 'speed_up', value: 10 };
        }

        // Slow down
        if (t.match(/\b(slow down|slower|decrease speed|go slower)\b/)) {
            return { action: 'speed_down', value: 10 };
        }

        // Set specific speed - handle both numeric and word forms
        const speedMatch = t.match(/\b(?:set speed to|speed|go to)\s+(\d+|ten|twenty|thirty|forty|fifty|sixty|seventy|eighty|ninety|one hundred)\b/);
        if (speedMatch) {
            const speedStr = speedMatch[1];
            const speedMap = {
                'ten': 10, 'twenty': 20, 'thirty': 30, 'forty': 40, 'fifty': 50,
                'sixty': 60, 'seventy': 70, 'eighty': 80, 'ninety': 90, 'one hundred': 100
            };
            const speed = speedMap[speedStr] || parseInt(speedStr);
            return { action: 'set_speed', value: speed };
        }

        // Full speed
        if (t.match(/\b(full speed|maximum speed|max speed|one hundred percent)\b/)) {
            return { action: 'set_speed', value: 100 };
        }

        // Half speed
        if (t.match(/\b(half speed|fifty percent|50 percent)\b/)) {
            return { action: 'set_speed', value: 50 };
        }

        // Minimum speed
        if (t.match(/\b(minimum speed|min speed|slowest|lowest)\b/)) {
            return { action: 'set_speed', value: 10 };
        }

        // Increase stroke
        if (t.match(/\b(increase stroke|longer stroke|bigger stroke)\b/)) {
            return { action: 'increase_stroke' };
        }

        // Decrease stroke
        if (t.match(/\b(decrease stroke|shorter stroke|smaller stroke|short stroke)\b/)) {
            return { action: 'decrease_stroke' };
        }

        // Full stroke range
        if (t.match(/\b(full stroke|maximum stroke|max stroke)\b/)) {
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
     * Set transcript callback (for showing interim results)
     */
    onTranscript(callback) {
        this.onTranscriptCallback = callback;
    }

    /**
     * Check if Deepgram is supported (requires WebSocket and MediaRecorder)
     */
    static isSupported() {
        return 'WebSocket' in window && 'MediaRecorder' in window;
    }
}

// Export for use in other scripts
window.DeepgramVoiceController = DeepgramVoiceController;
