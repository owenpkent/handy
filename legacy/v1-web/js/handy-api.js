/**
 * Handy API v3 Integration
 * Handles authentication and device control via REST API
 */

class HandyAPI {
    constructor() {
        this.baseUrl = 'https://www.handyfeeling.com/api/handy-rest/v3';
        this.apiKey = null;
        this.connectionKey = null;
        this.clientToken = null;
        this.tokenExpiry = null;
    }

    /**
     * Set credentials
     */
    setCredentials(apiKey, connectionKey) {
        this.apiKey = apiKey;
        this.connectionKey = connectionKey;
        
        // Save to localStorage
        localStorage.setItem('handy_api_key', apiKey);
        localStorage.setItem('handy_connection_key', connectionKey);
    }

    /**
     * Load credentials from localStorage
     */
    loadCredentials() {
        this.apiKey = localStorage.getItem('handy_api_key');
        this.connectionKey = localStorage.getItem('handy_connection_key');
        return this.apiKey && this.connectionKey;
    }

    /**
     * Clear credentials
     */
    clearCredentials() {
        this.apiKey = null;
        this.connectionKey = null;
        this.clientToken = null;
        this.tokenExpiry = null;
        localStorage.removeItem('handy_api_key');
        localStorage.removeItem('handy_connection_key');
    }

    /**
     * Issue a client token for API authentication
     */
    async issueToken() {
        if (!this.apiKey) {
            throw new Error('API key not set');
        }

        try {
            const response = await fetch(`${this.baseUrl}/auth/token`, {
                method: 'GET',
                headers: {
                    'X-API-Key': this.apiKey,
                    'Accept': 'application/json'
                }
            });

            if (!response.ok) {
                const error = await response.json();
                throw new Error(error.message || 'Failed to issue token');
            }

            const data = await response.json();
            this.clientToken = data.token;
            
            // Set expiry (default 1 hour)
            this.tokenExpiry = Date.now() + (3600 * 1000);
            
            return data;
        } catch (error) {
            console.error('Token issue error:', error);
            throw error;
        }
    }

    /**
     * Ensure we have a valid token
     */
    async ensureToken() {
        if (!this.clientToken || Date.now() >= this.tokenExpiry) {
            await this.issueToken();
        }
    }

    /**
     * Make authenticated API request
     */
    async request(endpoint, options = {}) {
        await this.ensureToken();

        const headers = {
            'Authorization': `Bearer ${this.clientToken}`,
            'X-Connection-Key': this.connectionKey,
            'Accept': 'application/json',
            'Content-Type': 'application/json',
            ...options.headers
        };

        const response = await fetch(`${this.baseUrl}${endpoint}`, {
            ...options,
            headers
        });

        if (!response.ok) {
            const error = await response.json();
            throw new Error(error.message || `API error: ${response.status}`);
        }

        return await response.json();
    }

    /**
     * Get device information
     */
    async getDeviceInfo() {
        return await this.request('/info/device');
    }

    /**
     * Get HAMP state
     */
    async getHampState() {
        return await this.request('/hamp/state');
    }

    /**
     * Set HAMP state
     */
    async setHampState(state) {
        return await this.request('/hamp/state', {
            method: 'PUT',
            body: JSON.stringify(state)
        });
    }

    /**
     * Start device
     */
    async start() {
        return await this.request('/hamp/start', {
            method: 'PUT'
        });
    }

    /**
     * Stop device
     */
    async stop() {
        return await this.request('/hamp/stop', {
            method: 'PUT'
        });
    }

    /**
     * Set speed (0-100)
     */
    async setSpeed(speed) {
        const clampedSpeed = Math.max(0, Math.min(100, speed));
        return await this.setHampState({
            velocity: clampedSpeed
        });
    }

    /**
     * Set stroke range (min and max 0.0-1.0)
     */
    async setStrokeRange(min, max) {
        const clampedMin = Math.max(0, Math.min(1, min));
        const clampedMax = Math.max(0, Math.min(1, max));
        
        return await this.setHampState({
            stroke: {
                min: clampedMin,
                max: clampedMax
            }
        });
    }

    /**
     * Get current status
     */
    async getStatus() {
        try {
            const [deviceInfo, hampState] = await Promise.all([
                this.getDeviceInfo(),
                this.getHampState()
            ]);

            return {
                connected: true,
                device: deviceInfo,
                state: hampState
            };
        } catch (error) {
            console.error('Status error:', error);
            return {
                connected: false,
                error: error.message
            };
        }
    }
}

// Export for use in other scripts
window.HandyAPI = HandyAPI;
