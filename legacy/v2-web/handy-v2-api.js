/**
 * Handy V2 API Client
 * Uses the Handy REST API v2 for firmware v3+ devices
 */

class HandyV2API {
    constructor() {
        this.baseUrl = 'https://www.handyfeeling.com/api/handy/v2';
        this.connectionKey = null;
        this.isConnected = false;
        this.currentSpeed = 50;
        this.currentStroke = 100;
        this.onLog = null; // Callback for debug logging
    }

    setConnectionKey(key) {
        this.connectionKey = key;
        localStorage.setItem('handy_v2_connection_key', key);
    }

    loadConnectionKey() {
        this.connectionKey = localStorage.getItem('handy_v2_connection_key');
        return this.connectionKey;
    }

    clearConnectionKey() {
        this.connectionKey = null;
        this.isConnected = false;
        localStorage.removeItem('handy_v2_connection_key');
    }

    log(entry) {
        if (this.onLog) {
            this.onLog(entry);
        }
    }

    async request(endpoint, options = {}) {
        if (!this.connectionKey) {
            throw new Error('Connection key not set');
        }

        const method = options.method || 'GET';
        const body = options.body ? JSON.parse(options.body) : null;

        this.log({
            type: 'request',
            method,
            endpoint,
            body
        });

        const headers = {
            'X-Connection-Key': this.connectionKey,
            'Accept': 'application/json',
            'Content-Type': 'application/json',
            ...options.headers
        };

        const response = await fetch(`${this.baseUrl}${endpoint}`, {
            ...options,
            headers
        });

        const data = await response.json();

        if (!response.ok) {
            const errorMsg = data.error?.message || `API error: ${response.status}`;
            this.log({
                type: 'error',
                endpoint,
                error: errorMsg
            });
            throw new Error(errorMsg);
        }

        this.log({
            type: 'response',
            endpoint,
            data
        });

        return data;
    }

    async checkConnection() {
        try {
            const result = await this.request('/connected');
            this.isConnected = result.connected === true;
            return this.isConnected;
        } catch (error) {
            this.isConnected = false;
            throw error;
        }
    }

    async getInfo() {
        return await this.request('/info');
    }

    async setMode(mode) {
        return await this.request('/mode', {
            method: 'PUT',
            body: JSON.stringify({ mode })
        });
    }

    async setHAMPMode() {
        return await this.setMode(0);
    }

    async start() {
        return await this.request('/hamp/start', {
            method: 'PUT'
        });
    }

    async stop() {
        return await this.request('/hamp/stop', {
            method: 'PUT'
        });
    }

    async setSpeed(velocity) {
        this.currentSpeed = Math.max(0, Math.min(100, velocity));
        return await this.request('/hamp/velocity', {
            method: 'PUT',
            body: JSON.stringify({ velocity: this.currentSpeed })
        });
    }

    async getSpeed() {
        const result = await this.request('/hamp/velocity');
        this.currentSpeed = result.velocity;
        return result.velocity;
    }

    async setStroke(percent) {
        this.currentStroke = Math.max(10, Math.min(100, percent));
        const fraction = this.currentStroke / 100;
        const min = (1 - fraction) / 2;
        const max = 1 - min;

        await this.request('/slide/min', {
            method: 'PUT',
            body: JSON.stringify({ min: min * 100 })
        });

        await this.request('/slide/max', {
            method: 'PUT',
            body: JSON.stringify({ max: max * 100 })
        });

        return { min, max };
    }

    async setStrokeRange(minPercent, maxPercent) {
        const min = Math.max(0, Math.min(100, minPercent));
        const max = Math.max(0, Math.min(100, maxPercent));

        await this.request('/slide', {
            method: 'PUT',
            body: JSON.stringify({ min, max })
        });

        return { min, max };
    }

    async getSlide() {
        return await this.request('/slide');
    }

    async getStatus() {
        return await this.request('/status');
    }

    adjustSpeed(delta) {
        const newSpeed = Math.max(0, Math.min(100, this.currentSpeed + delta));
        return this.setSpeed(newSpeed);
    }

    async connect() {
        const connected = await this.checkConnection();
        if (!connected) {
            throw new Error('Device not connected. Make sure your Handy is online.');
        }

        const info = await this.getInfo();
        
        if (info.fwStatus === 2) {
            throw new Error('Firmware update required. Visit handyfeeling.com to update.');
        }

        await this.setHAMPMode();

        try {
            const velocity = await this.getSpeed();
            this.currentSpeed = velocity;
        } catch (e) {
            this.currentSpeed = 50;
        }

        return info;
    }

    async disconnect() {
        try {
            await this.stop();
        } catch (e) {
            // Ignore stop errors on disconnect
        }
        this.isConnected = false;
    }
}

window.HandyV2API = HandyV2API;
